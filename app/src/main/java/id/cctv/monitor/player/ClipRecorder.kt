package id.cctv.monitor.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.Camera
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.NetworkType
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Merekam satu stream kamera ke file .ts memakai jalur "sout" VLC.
 * Tidak memakai surface, jadi bisa dipakai tanpa mengganggu grid.
 */
object ClipRecorder {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var currentCameraId = -1
    private var target: File? = null
    private var callback: ((File?, String?) -> Unit)? = null

    fun isRecording(cameraId: Int): Boolean = player != null && currentCameraId == cameraId

    fun isBusy(): Boolean = player != null

    /** Kamera yang sedang direkam, atau -1 bila tidak ada rekaman. */
    fun activeCameraId(): Int = if (player != null) currentCameraId else -1

    fun start(
        context: Context,
        prefs: AppPrefs,
        camera: Camera,
        dir: File,
        onDone: (File?, String?) -> Unit
    ) {
        if (player != null) {
            onDone(null, "Rekaman lain masih berjalan. Hentikan dulu.")
            return
        }
        if (prefs.blockRtspOnCellular && NetworkType.isCellular(context.applicationContext)) {
            onDone(null, "Rekam RTSP diblokir saat paket data seluler.")
            return
        }
        val endpoint = EndpointResolver.resolve(camera, prefs.remoteSettings())
        val url = camera.rtspUrl(
            main = true,
            user = prefs.effectiveUser(camera),
            pass = prefs.effectivePass(camera),
            host = endpoint.host,
            rtspPort = endpoint.rtspPort
        )
        dir.mkdirs()
        val name = "cctv_${camera.id}_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.ts"
        val file = File(dir, name)
        val vlc = SharedVlc.get(context)
        val mp = MediaPlayer(vlc)
        val media = Media(vlc, Uri.parse(url))
        val out = file.absolutePath.replace('\\', '/')
        media.addOption(":rtsp-tcp")
        media.addOption(":no-audio")
        media.addOption(":network-caching=500")
        media.addOption(":sout=#std{access=file,mux=ts,dst='$out'}")
        media.addOption(":sout-keep")
        mp.setMedia(media)
        media.release()
        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EncounteredError ->
                    finish("Kamera menolak/memutus stream rekaman.")
                MediaPlayer.Event.EndReached -> finish(null)
            }
        }
        player = mp
        currentCameraId = camera.id
        target = file
        callback = onDone
        try {
            mp.play()
        } catch (t: Throwable) {
            finish("Gagal memulai rekaman: ${t.message}")
        }
    }

    fun stop(onDone: ((File?, String?) -> Unit)? = null) {
        if (player == null) {
            onDone?.invoke(null, "Tidak ada rekaman yang berjalan.")
            return
        }
        if (onDone != null) callback = onDone
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        // Beri waktu VLC menutup file sebelum dilaporkan.
        handler.postDelayed({ finish(null) }, 800)
    }

    private fun finish(error: String?) {
        val mp = player
        player = null
        currentCameraId = -1
        val file = target
        target = null
        try {
            mp?.release()
        } catch (_: Throwable) {
        }
        val ok = error == null && file != null && file.exists() && file.length() > 10_000
        val message = when {
            error != null -> error
            !ok -> "Rekaman gagal: file kosong/tidak terbentuk."
            else -> "Rekaman disimpan (${file!!.length() / 1024} KB)."
        }
        val cb = callback
        callback = null
        cb?.invoke(if (ok) file else null, message)
    }
}
