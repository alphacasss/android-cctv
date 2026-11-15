package id.cctv.monitor.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.CameraFlags
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.PublicIp
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Satu LibVLC + satu MediaPlayer per tile. Grid memakai sub-stream (102),
 * layar penuh memakai main stream (101).
 */
class PlayerManager(context: Context, private val prefs: AppPrefs) {

    enum class State { IDLE, LOADING, PLAYING, ERROR, STOPPED }

    interface Listener {
        fun onPlayerState(cameraId: Int, state: State)
    }

    var listener: Listener? = null

    private class Slot(
        val player: MediaPlayer,
        val layout: VLCVideoLayout,
        var wantPlay: Boolean = false,
        var mainStream: Boolean = false,
        var retry: Runnable? = null
    )

    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--rtsp-tcp",
            "--no-audio",
            "--no-video-title-show",
            "--network-caching=600",
            "--live-caching=600",
            "--drop-late-frames",
            "--skip-frames"
        )
    )
    private val handler = Handler(Looper.getMainLooper())
    private val slots = HashMap<Int, Slot>()
    private val states = HashMap<Int, State>()
    private var released = false

    fun attach(camera: Camera, layout: VLCVideoLayout) {
        if (released) return
        slots.remove(camera.id)?.let { releaseSlot(it) }
        val player = MediaPlayer(libVlc)
        player.attachViews(layout, null, false, true)
        player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        player.setEventListener { event -> onEvent(camera.id, event.type) }
        slots[camera.id] = Slot(player, layout)
        states[camera.id] = State.IDLE
    }

    fun play(camera: Camera, mainStream: Boolean = false) {
        if (released) return
        // Jaga alamat luar (IP publik) tetap segar saat dipakai dari luar rumah.
        PublicIp.refreshAsync(prefs)
        val slot = slots[camera.id] ?: return
        cancelRetry(slot)
        slot.wantPlay = true
        slot.mainStream = mainStream
        setState(camera.id, State.LOADING)

        val endpoint = EndpointResolver.resolve(camera, prefs.remoteSettings())
        val url = camera.rtspUrl(
            main = mainStream,
            user = prefs.effectiveUser(camera),
            pass = prefs.effectivePass(camera),
            host = endpoint.host,
            rtspPort = endpoint.rtspPort
        )
        val media = Media(libVlc, Uri.parse(url))
        media.addOption(":rtsp-tcp")
        media.addOption(":network-caching=600")
        media.addOption(":live-caching=600")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")
        try {
            media.setHWDecoderEnabled(true, false)
        } catch (_: Throwable) {
            // fallback ke software decoder
        }
        slot.player.setMedia(media)
        media.release()
        slot.player.play()
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
        cancelRetry(slot)
        slot.player.stop()
        setState(cameraId, State.STOPPED)
    }

    fun stopAll() {
        slots.keys.toList().forEach { stop(it) }
    }

    fun isActive(cameraId: Int): Boolean = slots[cameraId]?.wantPlay == true

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        slots.values.forEach { releaseSlot(it) }
        slots.clear()
        states.clear()
        libVlc.release()
    }

    private fun releaseSlot(slot: Slot) {
        cancelRetry(slot)
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

    private fun cancelRetry(slot: Slot) {
        slot.retry?.let { handler.removeCallbacks(it) }
        slot.retry = null
    }

    private fun onEvent(cameraId: Int, type: Int) {
        handler.post {
            if (released) return@post
            val slot = slots[cameraId] ?: return@post
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
                    setState(cameraId, State.ERROR)
                    if (slot.wantPlay) scheduleRetry(cameraId)
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
        handler.postDelayed(retry, 5_000)
    }
}
