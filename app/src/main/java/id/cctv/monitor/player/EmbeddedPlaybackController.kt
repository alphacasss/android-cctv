package id.cctv.monitor.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

data class EmbeddedPlaybackUiState(
    val overlay: String?,
    val playing: Boolean,
    val elapsedMillis: Long,
    val durationMillis: Long
)

/** Satu MediaPlayer untuk video playback yang menempel di layar PlaybackActivity. */
class EmbeddedPlaybackController(
    private val libVlc: LibVLC,
    private val videoLayout: VLCVideoLayout,
    private val onUi: (EmbeddedPlaybackUiState) -> Unit
) {

    private var player: MediaPlayer? = null
    private var viewsAttached = false
    private var overlay: String? = null
    private var playing = false
    private var playingSeen = false
    private var finished = false
    private var elapsedMillis = 0L
    private var durationMillis = 0L
    private var pendingOffsetMillis = 0L
    private var offsetApplied = false
    private var lastTickAt = 0L

    private val clock = Handler(Looper.getMainLooper())
    private val timeTick = object : Runnable {
        override fun run() {
            tickTime()
            clock.postDelayed(this, 500L)
        }
    }

    fun load(file: File, offsetMillis: Long) {
        val target = offsetMillis.coerceAtLeast(0L)
        pendingOffsetMillis = target
        offsetApplied = false
        elapsedMillis = target
        durationMillis = 0L
        playing = false
        playingSeen = false
        finished = false
        overlay = "Memuat…"
        emit()

        val active = player ?: createPlayer()
        runCatching { active.stop() }
        val media = Media(libVlc, Uri.fromFile(file)).apply {
            addOption(":no-video-title-show")
            addOption(":file-caching=300")
        }
        active.setMedia(media)
        media.release()
        active.play()

        clock.removeCallbacks(timeTick)
        clock.post(timeTick)
    }

    fun seek(offsetMillis: Long) {
        val target = offsetMillis.coerceAtLeast(0L)
        pendingOffsetMillis = target
        elapsedMillis = target
        offsetApplied = false
        val active = player
        if (active != null && playing) {
            runCatching { active.time = target }
            pendingOffsetMillis = 0L
            offsetApplied = true
        }
        emit()
    }

    fun playPause() {
        val active = player ?: return
        if (finished) {
            runCatching { active.stop() }
            finished = false
            playingSeen = false
            elapsedMillis = 0L
            pendingOffsetMillis = 0L
            offsetApplied = true
            active.play()
            return
        }
        if (playing) active.pause() else active.play()
    }

    fun pause() {
        if (playing) player?.pause()
    }

    fun resume() {
        val active = player
        if (active != null && !playing && !finished) active.play()
    }

    fun stop() {
        clock.removeCallbacks(timeTick)
        runCatching { player?.stop() }
        playing = false
        playingSeen = false
        lastTickAt = 0L
        emit()
    }

    fun release() {
        clock.removeCallbacks(timeTick)
        val active = player ?: return
        runCatching { active.stop() }
        if (viewsAttached) {
            runCatching { active.detachViews() }
        }
        runCatching { active.release() }
        player = null
        viewsAttached = false
    }

    private fun createPlayer(): MediaPlayer {
        val active = MediaPlayer(libVlc)
        active.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        active.setEventListener { event -> clock.post { onPlayerEvent(event.type) } }
        active.attachViews(videoLayout, null, false, true)
        viewsAttached = true
        player = active
        return active
    }

    private fun tickTime() {
        val active = player ?: return
        if (finished) return
        val now = System.currentTimeMillis()
        val vlcLength = runCatching { active.length }.getOrDefault(0L)
        if (vlcLength > 0L) durationMillis = vlcLength
        val vlcTime = runCatching { active.time }.getOrDefault(0L)
        if (vlcTime > 0L) {
            elapsedMillis = vlcTime
        } else if (playing && lastTickAt > 0L) {
            elapsedMillis += now - lastTickAt
        }
        lastTickAt = now
        emit()
    }

    private fun onPlayerEvent(type: Int) {
        when (type) {
            MediaPlayer.Event.Opening,
            MediaPlayer.Event.Buffering -> {
                if (!playingSeen) {
                    overlay = "Memuat…"
                    emit()
                }
            }

            MediaPlayer.Event.Playing,
            MediaPlayer.Event.Vout -> {
                playingSeen = true
                playing = true
                finished = false
                overlay = null
                if (!offsetApplied && pendingOffsetMillis > 0L) {
                    runCatching { player?.time = pendingOffsetMillis }
                    elapsedMillis = pendingOffsetMillis
                }
                pendingOffsetMillis = 0L
                offsetApplied = true
                emit()
            }

            MediaPlayer.Event.Paused -> {
                playing = false
                emit()
            }

            MediaPlayer.Event.TimeChanged -> {
                if (!finished) {
                    elapsedMillis = runCatching { player?.time ?: 0L }.getOrDefault(0L)
                    emit()
                }
            }

            MediaPlayer.Event.LengthChanged -> {
                val length = runCatching { player?.length ?: 0L }.getOrDefault(0L)
                if (length > 0L) durationMillis = length
                emit()
            }

            MediaPlayer.Event.EndReached -> {
                playingSeen = false
                playing = false
                finished = true
                elapsedMillis = durationMillis
                overlay = "Selesai"
                emit()
            }

            MediaPlayer.Event.EncounteredError -> {
                playingSeen = false
                playing = false
                overlay = "Gagal memutar rekaman"
                emit()
            }
        }
    }

    private fun emit() {
        onUi(EmbeddedPlaybackUiState(overlay, playing, elapsedMillis, durationMillis))
    }
}
