package id.cctv.monitor.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import id.cctv.monitor.CctvApp
import id.cctv.monitor.cloud.CloudStreamSource
import id.cctv.monitor.cloud.stream.CloudPlayer
import id.cctv.monitor.cloud.stream.CloudVideoPlayer
import id.cctv.monitor.cloud.stream.OfficialSdkPlayer
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.CameraFlags
import id.cctv.monitor.net.Endpoint
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.NetworkType
import id.cctv.monitor.net.PublicIp
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

import java.net.URLEncoder

/**
 * Satu pemutar per tile. Rute yang dipakai:
 *  - **cloud P2P** (`ysproto`) bila kamera terikat ke Hik-Connect dan ponsel
 *    berada di luar subnet kamera — tanpa port forwarding,
 *  - **RTSP/HTTP** (LibVLC) untuk jaringan lokal, DDNS/IP publik, VPN, atau
 *    URL stream khusus.
 *
 * Grid memakai sub-stream (102), layar penuh memakai main stream (101).
 */
class PlayerManager(context: Context, private val prefs: AppPrefs) {

    enum class State { IDLE, LOADING, PLAYING, ERROR, STOPPED }

    interface Listener {
        fun onPlayerState(cameraId: Int, state: State)

        /** Pesan tambahan (mis. kode verifikasi kosong). Boleh diabaikan. */
        fun onPlayerMessage(cameraId: Int, message: String?) = Unit
    }

    var listener: Listener? = null

    private class Slot(
        val camera: Camera,
        val player: MediaPlayer,
        val layout: VLCVideoLayout,
        val cloudSurface: SurfaceView?,
        var wantPlay: Boolean = false,
        var mainStream: Boolean = false,
        var retry: Runnable? = null,
        var cloudPlayer: CloudPlayer? = null,
        var vlcAttached: Boolean = false,
        /** True selama rute cloud aktif; event LibVLC diabaikan. */
        var cloudActive: Boolean = false,
        /** True setelah sesi relay cloud dimulai untuk [cloudStreamMain]. */
        var cloudStarted: Boolean = false,
        /** Jenis stream (main/sub) yang sedang dibuka rute cloud. */
        var cloudStreamMain: Boolean = false,
        /** RTSP gagal di LAN: paksa relay cloud untuk slot ini. */
        var forceCloud: Boolean = false,
        /** Waktu (epoch) sebelum rute cloud boleh dicoba lagi. */
        var cloudBlockedUntil: Long = 0L,
        /** Stream dihentikan karena kebijakan paket data seluler. */
        var blockedByCellular: Boolean = false
    )

    // Satu instance LibVLC dipakai bersama seluruh layar (grid & fullscreen).
    // Membuat ulang LibVLC sangat mahal dan membuat aplikasi lambat merespons.
    private val appContext = context.applicationContext
    private val app: CctvApp? = appContext as? CctvApp
    private val libVlc = SharedVlc.get(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val slots = HashMap<Int, Slot>()
    private val states = HashMap<Int, State>()
    private var released = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerNetworkCallback()
    }

    fun attach(camera: Camera, layout: VLCVideoLayout, cloudSurface: SurfaceView? = null) {
        if (released) return
        slots.remove(camera.id)?.let { releaseSlot(it) }
        val player = MediaPlayer(libVlc)
        player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        player.setEventListener { event -> onEvent(camera.id, event.type) }
        slots[camera.id] = Slot(camera, player, layout, cloudSurface)
        states[camera.id] = State.IDLE
    }

    fun hasSlot(cameraId: Int): Boolean = slots.containsKey(cameraId)

    /** Lepas player + surface kamera (dipakai untuk kamera di halaman yang tidak terlihat). */
    fun detach(cameraId: Int) {
        val slot = slots.remove(cameraId) ?: return
        states.remove(cameraId)
        releaseSlot(slot)
    }

    fun play(camera: Camera, mainStream: Boolean = false) {
        if (released) return
        // Jaga alamat luar (IP publik) tetap segar saat dipakai dari luar rumah.
        if (prefs.remoteAuto && prefs.shouldRefreshPublicIp()) {
            PublicIp.refreshAsync(prefs)
        }
        val slot = slots[camera.id] ?: return
        cancelRetry(slot)
        // Jangan buka sesi relay kedua untuk kamera yang sama: setiap sesi
        // memakai token sekali pakai dan relay menutup salah satunya.
        val state = states[camera.id]
        if (slot.cloudActive && slot.cloudStarted && slot.cloudStreamMain == mainStream &&
            (state == State.LOADING || state == State.PLAYING)
        ) {
            return
        }
        // Kembali ke jaringan rumah: coba RTSP lagi, jangan terus memakai cloud.
        if (slot.forceCloud && !camera.forceRemote &&
            EndpointResolver.isLocalSubnet(camera.ip)
        ) {
            slot.forceCloud = false
        }
        slot.wantPlay = true
        slot.mainStream = mainStream
        setState(camera.id, State.LOADING)

        val endpoint = EndpointResolver.resolve(camera, prefs.remoteSettings())
        val remote = slot.forceCloud || camera.forceRemote ||
            !EndpointResolver.isLocalSubnet(camera.ip)
        val cellularBlocked = prefs.blockRtspOnCellular && NetworkType.isCellular(appContext)
        if (cellularBlocked && remote && !cloudRouteAvailable(camera, slot)) {
            try {
                slot.player.stop()
            } catch (_: Throwable) {
            }
            slot.cloudActive = false
            slot.blockedByCellular = true
            setState(camera.id, State.ERROR)
            listener?.onPlayerMessage(camera.id, CELLULAR_RTSP_MESSAGE)
            return
        }
        slot.blockedByCellular = false
        if (shouldUseCloudP2p(camera, slot, remote)) {
            startCloud(camera, slot, mainStream)
        } else if (cloudRouteAvailable(camera, slot) && remote) {
            // Binding cloud ada tetapi relay sedang backoff: jangan buang waktu
            // mencoba RTSP publik yang tidak bisa dihubungi dari luar rumah.
            setState(camera.id, State.ERROR)
            scheduleRetry(camera.id)
        } else {
            startVlc(camera, slot, mainStream, endpoint)
        }
    }

    /** Metadata binding cukup untuk relay P2P cloud? */
    private fun cloudRouteAvailable(camera: Camera, slot: Slot): Boolean {
        val cloud = camera.cloudDevice ?: return false
        if (!cloud.valid) return false
        // URL stream khusus tetap diputar lewat LibVLC.
        if (cloud.streamUrl.isNotBlank()) return false
        if (slot.cloudSurface == null) return false
        return app?.cloudAuth?.isLoggedIn == true
    }

    /** Cloud dipakai di luar subnet kamera, dan wajib saat data seluler. */
    private fun shouldUseCloudP2p(camera: Camera, slot: Slot, remote: Boolean): Boolean {
        if (!remote) return false
        if (!cloudRouteAvailable(camera, slot)) return false
        if (prefs.blockRtspOnCellular && NetworkType.isCellular(appContext)) return true
        return slot.cloudBlockedUntil <= System.currentTimeMillis()
    }

    /** Rute cloud: SDK resmi Hik-Connect, atau relay `ysproto` sebagai fallback. */
    private fun startCloud(camera: Camera, slot: Slot, mainStream: Boolean) {
        val surface = slot.cloudSurface
        if (surface == null) {
            startVlc(
                camera,
                slot,
                mainStream,
                EndpointResolver.resolve(camera, prefs.remoteSettings())
            )
            return
        }
        slot.cloudActive = true
        slot.cloudStarted = true
        slot.cloudStreamMain = mainStream
        detachVlcViews(slot)
        slot.cloudPlayer?.release()
        val listener = object : CloudVideoPlayer.Listener {
            override fun onState(state: CloudVideoPlayer.State, message: String?) {
                handler.post { handleCloudState(camera, slot, state, message) }
            }

            override fun onVideoSize(width: Int, height: Int) = Unit
        }
        val onVerificationCode: (String, String) -> Unit = { serial, code ->
            // Kanal bisa punya kode sendiri; utamakan serial kanal.
            val channelSerial = camera.cloudDevice?.channelSerial.orEmpty()
            prefs.saveCloudVerificationCode(channelSerial.ifBlank { serial }, code)
        }
        val player: CloudPlayer = if (prefs.useOfficialSdk) {
            OfficialSdkPlayer(
                surface,
                { main -> provideCloudSource(camera, main) },
                listener,
                channelSerialProvider = { camera.cloudDevice?.channelSerial.orEmpty() },
                onVerificationCode = onVerificationCode
            )
        } else {
            CloudVideoPlayer(
                surface,
                { main -> provideCloudSource(camera, main) },
                listener,
                onVerificationCode = onVerificationCode
            )
        }
        slot.cloudPlayer = player
        player.showSurface(true)
        player.start(mainStream)
    }

    private suspend fun provideCloudSource(
        camera: Camera,
        mainStream: Boolean
    ): CloudStreamSource? {
        val application = app ?: return null
        val device = camera.cloudDevice ?: return null
        val source = application.cloudRepo.streamSource(
            application.cloudAuth.session.value,
            device,
            mainStream
        )
        if (source != null) {
            val key = "${device.channelSerial.ifBlank { device.serial }}|${device.channel}|$mainStream"
            application.cloudSourceCache[key] = source
            application.cloudSourceCache["${device.serial}|${device.channel}|$mainStream"] = source
        }
        return source
    }

    private fun handleCloudState(
        camera: Camera,
        slot: Slot,
        state: CloudVideoPlayer.State,
        message: String?
    ) {
        if (released || slots[camera.id] !== slot) return
        when (state) {
            CloudVideoPlayer.State.CONNECTING -> setState(camera.id, State.LOADING)
            CloudVideoPlayer.State.PLAYING -> {
                // Batalkan retry yang mungkin sudah terjadwal dari event VLC
                // sebelum rute cloud aktif.
                cancelRetry(slot)
                setState(camera.id, State.PLAYING)
            }
            CloudVideoPlayer.State.ERROR -> {
                slot.cloudStarted = false
                setState(camera.id, State.ERROR)
                listener?.onPlayerMessage(camera.id, message)
                if (slot.wantPlay) {
                    // Beri jeda agar rute RTSP/DDNS sempat dicoba lebih dulu.
                    slot.cloudBlockedUntil = System.currentTimeMillis() + CLOUD_BACKOFF_MS
                    scheduleRetry(camera.id)
                }
            }

            CloudVideoPlayer.State.STOPPED -> {
                slot.cloudStarted = false
                if (!slot.wantPlay) setState(camera.id, State.STOPPED)
            }

            CloudVideoPlayer.State.IDLE -> Unit
        }
    }

    private fun startVlc(
        camera: Camera,
        slot: Slot,
        mainStream: Boolean,
        endpoint: Endpoint
    ) {
        // SurfaceView cloud selalu di atas VLC; sembunyikan sebelum fallback,
        // kalau tidak video RTSP tertutup permukaan kosong.
        slot.cloudActive = false
        slot.cloudStarted = false
        slot.cloudSurface?.visibility = View.GONE
        slot.cloudPlayer?.release()
        slot.cloudPlayer = null
        val cloudDevice = camera.cloudDevice
        val cloudUrl = cloudDevice?.streamUrl?.trim().orEmpty()
        val url: String = if (cloudUrl.isNotEmpty()) {
            // Penyedia stream cloud/SDK memberi URL lengkap (rtsp/http/https).
            withCredentials(
                cloudUrl,
                cloudDevice?.username.orEmpty(),
                cloudDevice?.password.orEmpty()
            )
        } else {
            // Tanpa stream cloud: fallback ke rute ISAPI/RTSP biasa
            // (LAN, IP publik/DDNS, atau VPN).
            camera.rtspUrl(
                main = mainStream,
                user = prefs.effectiveUser(camera),
                pass = prefs.effectivePass(camera),
                host = endpoint.host,
                rtspPort = endpoint.rtspPort
            )
        }
        val media = Media(libVlc, Uri.parse(url))
        if (url.startsWith("rtsp://", ignoreCase = true)) media.addOption(":rtsp-tcp")
        media.addOption(":network-caching=300")
        media.addOption(":live-caching=300")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")
        // Night boost: pencerahan gambar di decoder (pengganti IR bila kamera
        // tidak mendukung mode malam). Filter "adjust" bawaan VLC.
        if (prefs.flags(camera.id).nightBoost) {
            media.addOption(":video-filter=adjust")
            media.addOption(":brightness=1.5")
            media.addOption(":contrast=1.3")
            media.addOption(":gamma=2.0")
            media.addOption(":saturation=0.45")
        }
        try {
            media.setHWDecoderEnabled(true, false)
        } catch (_: Throwable) {
            // fallback ke software decoder
        }
        if (!slot.vlcAttached) {
            try {
                slot.player.attachViews(slot.layout, null, false, true)
                slot.vlcAttached = true
            } catch (t: Throwable) {
                android.util.Log.w("PlayerManager", "attach VLC gagal: ${t.message}")
            }
        }
        slot.player.setMedia(media)
        media.release()
        slot.player.play()
    }

    /** Lepas surface LibVLC sebelum memakai SurfaceView cloud. */
    private fun detachVlcViews(slot: Slot) {
        if (!slot.vlcAttached) return
        try {
            slot.player.stop()
        } catch (_: Throwable) {
        }
        try {
            slot.player.detachViews()
        } catch (_: Throwable) {
        }
        slot.vlcAttached = false
    }

    /** Mulai hanya bila belum aktif; tidak me-restart stream yang sedang berjalan. */
    fun ensurePlaying(camera: Camera, flags: CameraFlags) {
        val slot = slots[camera.id] ?: return
        if (!camera.enabled || flags.blocked) {
            stop(camera.id)
            return
        }
        if (!slot.wantPlay) play(camera, false)
    }

    fun stop(cameraId: Int) {
        val slot = slots[cameraId] ?: return
        slot.wantPlay = false
        slot.cloudActive = false
        cancelRetry(slot)
        slot.cloudPlayer?.stop()
        slot.cloudPlayer?.showSurface(false)
        slot.player.stop()
        setState(cameraId, State.STOPPED)
    }

    fun stopAll() {
        slots.keys.toList().forEach { stop(it) }
    }

    fun isActive(cameraId: Int): Boolean = slots[cameraId]?.wantPlay == true

    /** Mulai merekam stream kamera ini ke folder [dir] (format .ts dari VLC). */
    fun startRecording(cameraId: Int, dir: java.io.File): Boolean {
        val slot = slots[cameraId] ?: return false
        if (prefs.blockRtspOnCellular && NetworkType.isCellular(appContext)) {
            listener?.onPlayerMessage(cameraId, CELLULAR_RECORD_MESSAGE)
            return false
        }
        return try {
            dir.mkdirs()
            slot.player.record(dir.absolutePath)
        } catch (t: Throwable) {
            android.util.Log.w("PlayerManager", "record gagal: ${t.message}")
            false
        }
    }

    /** Hentikan rekaman kamera (bila didukung firmware/VLC). */
    fun stopRecording(cameraId: Int): Boolean {
        val slot = slots[cameraId] ?: return false
        return try {
            slot.player.record(null)
        } catch (t: Throwable) {
            android.util.Log.w("PlayerManager", "stop record gagal: ${t.message}")
            false
        }
    }

    fun release() {
        if (released) return
        released = true
        networkCallback?.let { callback ->
            try {
                connectivity?.unregisterNetworkCallback(callback)
            } catch (_: Throwable) {
            }
        }
        networkCallback = null
        handler.removeCallbacksAndMessages(null)
        slots.values.forEach { releaseSlot(it) }
        slots.clear()
        states.clear()
        // LibVLC adalah singleton aplikasi; jangan direlease agar layar berikutnya
        // tidak perlu inisialisasi ulang (penyebab aplikasi terasa lambat).
    }

    /** Sisipkan kredensial ke URL yang belum punya userinfo. */
    private fun withCredentials(raw: String, user: String, pass: String): String {
        val value = raw.trim()
        val scheme = value.substringBefore("://", "")
        if (scheme.isEmpty() || user.isEmpty()) return value
        val rest = value.substringAfter("://")
        if (rest.substringBefore('/').contains('@')) return value
        val auth = "${encode(user)}:${encode(pass)}@"
        return "$scheme://$auth$rest"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun releaseSlot(slot: Slot) {
        cancelRetry(slot)
        try {
            slot.cloudPlayer?.release()
        } catch (_: Throwable) {
        }
        try {
            slot.player.stop()
        } catch (_: Throwable) {
        }
        try {
            slot.player.detachViews()
        } catch (_: Throwable) {
        }
        try {
            slot.player.release()
        } catch (_: Throwable) {
        }
    }

    /** Dengarkan perpindahan jaringan agar RTSP langsung dihentikan saat data aktif. */
    private fun registerNetworkCallback() {
        val manager = connectivity ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = applyNetworkPolicy()
            override fun onLost(network: Network) = applyNetworkPolicy()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) = applyNetworkPolicy()
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            android.util.Log.w(TAG, "network callback gagal: ${e.message}")
        }
    }

    /**
     * Kebijakan data seluler: hentikan slot yang masih memakai VLC/RTSP,
     * lalu buka ulang lewat cloud bila kamera punya binding Hik-Connect.
     */
    private fun applyNetworkPolicy() {
        handler.post {
            if (released) return@post
            val cellular = prefs.blockRtspOnCellular && NetworkType.isCellular(appContext)
            if (cellular && ClipRecorder.isBusy()) {
                val recordingId = ClipRecorder.activeCameraId()
                ClipRecorder.stop()
                if (recordingId >= 0) {
                    listener?.onPlayerMessage(recordingId, CELLULAR_RECORD_MESSAGE)
                }
            }
            for (slot in slots.values.toList()) {
                if (!slot.wantPlay) continue
                if (!cellular) {
                    // Kembali ke Wi-Fi: nyalakan lagi tile yang sempat diblokir.
                    if (slot.blockedByCellular) {
                        slot.blockedByCellular = false
                        play(slot.camera, slot.mainStream)
                    }
                    continue
                }
                if (slot.cloudActive) continue
                try {
                    slot.player.stop()
                } catch (_: Throwable) {
                }
                slot.cloudActive = false
                if (cloudRouteAvailable(slot.camera, slot)) {
                    slot.blockedByCellular = false
                    play(slot.camera, slot.mainStream)
                } else {
                    slot.blockedByCellular = true
                    setState(slot.camera.id, State.ERROR)
                    listener?.onPlayerMessage(slot.camera.id, CELLULAR_RTSP_MESSAGE)
                }
            }
        }
    }

    private fun cancelRetry(slot: Slot) {
        slot.retry?.let { handler.removeCallbacks(it) }
        slot.retry = null
    }

    private fun onEvent(cameraId: Int, type: Int) {
        handler.post {
            if (released) return@post
            val slot = slots[cameraId] ?: return@post
            // Saat rute cloud aktif, event dari MediaPlayer VLC yang baru
            // dihentikan (Stopped/EndReached) tidak boleh memicu retry —
            // itu akan menutup dan membuka ulang stream cloud.
            if (slot.cloudActive) return@post
            when (type) {
                MediaPlayer.Event.Opening -> setState(cameraId, State.LOADING)

                // Buffering sering dikirim berulang pada stream live; jangan
                // menurunkan status yang sudah Playing.
                MediaPlayer.Event.Buffering -> {
                    if (states[cameraId] != State.PLAYING) setState(cameraId, State.LOADING)
                }

                MediaPlayer.Event.Playing,
                MediaPlayer.Event.Vout -> setState(cameraId, State.PLAYING)

                MediaPlayer.Event.EncounteredError,
                MediaPlayer.Event.EndReached -> {
                    // RTSP gagal padahal kamera terikat cloud: pindah ke relay
                    // P2P untuk percobaan berikutnya, bukan mengulang RTSP.
                    if (!slot.forceCloud && canFallbackToCloud(cameraId)) {
                        slot.forceCloud = true
                        setState(cameraId, State.LOADING)
                        scheduleRetry(cameraId)
                    } else {
                        setState(cameraId, State.ERROR)
                        if (slot.wantPlay) scheduleRetry(cameraId)
                    }
                }

                MediaPlayer.Event.Stopped -> {
                    if (slot.wantPlay) {
                        scheduleRetry(cameraId)
                    } else {
                        setState(cameraId, State.STOPPED)
                    }
                }
            }
        }
    }

    private fun setState(cameraId: Int, state: State) {
        if (states[cameraId] == state) return
        states[cameraId] = state
        listener?.onPlayerState(cameraId, state)
    }

    /** Kamera bisa dipindah ke relay cloud bila RTSP gagal? */
    private fun canFallbackToCloud(cameraId: Int): Boolean {
        val slot = slots[cameraId] ?: return false
        if (slot.cloudSurface == null) return false
        val camera = prefs.cameras().firstOrNull { it.id == cameraId } ?: return false
        val cloud = camera.cloudDevice ?: return false
        if (!cloud.valid || cloud.streamUrl.isNotBlank()) return false
        return app?.cloudAuth?.isLoggedIn == true
    }

    private fun scheduleRetry(cameraId: Int) {
        val slot = slots[cameraId] ?: return
        cancelRetry(slot)
        val retry = Runnable {
            if (released) return@Runnable
            val current = slots[cameraId] ?: return@Runnable
            current.retry = null
            if (!current.wantPlay) return@Runnable
            val camera = prefs.cameras().firstOrNull { it.id == cameraId } ?: return@Runnable
            play(camera, current.mainStream)
        }
        slot.retry = retry
        val wait = (slot.cloudBlockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        handler.postDelayed(retry, maxOf(5_000L, wait + 500L))
    }

    companion object {
        const val TAG = "PlayerManager"
        const val CLOUD_BACKOFF_MS = 20_000L
        const val CELLULAR_RTSP_MESSAGE =
            "RTSP diblokir saat paket data seluler. Hubungkan Wi-Fi atau login Hik-Connect."
        const val CELLULAR_RECORD_MESSAGE =
            "Rekam RTSP diblokir saat paket data seluler."
    }
}
