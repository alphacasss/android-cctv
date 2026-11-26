package id.cctv.monitor.player

import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

data class EmbeddedPlaybackUiState(
    val overlay: String?,
    val playing: Boolean
)

/** Satu MediaPlayer untuk video playback yang menempel di layar PlaybackActivity. */
class EmbeddedPlaybackController internal constructor(
    private val media: EmbeddedPlaybackMedia,
    private val dispatch: (() -> Unit) -> Unit,
    private val onUi: (EmbeddedPlaybackUiState) -> Unit
) {

    private var viewsAttached = false
    private var overlay: String? = null
    private var playing = false
    private var playingSeen = false
    private var finished = false
    private var pendingOffsetMillis = 0L
    private var offsetApplied = false

    init {
        media.listener = { event -> dispatch { onMediaEvent(event) } }
    }

    val isPlaying: Boolean get() = playing

    fun load(file: File, offsetMillis: Long) {
        pendingOffsetMillis = offsetMillis.coerceAtLeast(0L)
        offsetApplied = false
        playing = false
        playingSeen = false
        finished = false
        overlay = "Memuat…"
        emit()

        media.stop()
        if (!viewsAttached) {
            media.attachViews()
            viewsAttached = true
        }
        media.setMedia(file)
        media.play()
    }

    /** Geser slider: video yang sama dipindah, including saat pause atau setelah selesai. */
    fun seek(offsetMillis: Long) {
        val target = offsetMillis.coerceAtLeast(0L)
        if (finished) {
            pendingOffsetMillis = target
            offsetApplied = false
            finished = false
            playingSeen = false
            overlay = null
            media.stop()
            media.play()
        } else {
            media.time = target
            pendingOffsetMillis = 0L
            offsetApplied = true
        }
        emit()
    }

    fun playPause() {
        if (finished) {
            finished = false
            playingSeen = false
            overlay = null
            media.stop()
            media.play()
            emit()
            return
        }
        if (playing) media.pause() else media.play()
    }

    fun pause() {
        if (playing) media.pause()
    }

    fun resume() {
        if (!playing && !finished) media.play()
    }

    fun stop() {
        media.stop()
        playing = false
        playingSeen = false
        finished = false
        pendingOffsetMillis = 0L
        offsetApplied = true
        overlay = null
        emit()
    }

    fun release() {
        media.listener = null
        media.stop()
        if (viewsAttached) media.detachViews()
        media.release()
        viewsAttached = false
        playing = false
        playingSeen = false
        finished = false
        pendingOffsetMillis = 0L
        offsetApplied = true
        overlay = null
        emit()
    }

    private fun onMediaEvent(event: EmbeddedPlaybackEvent) {
        when (event) {
            EmbeddedPlaybackEvent.OPENING,
            EmbeddedPlaybackEvent.BUFFERING -> {
                if (!playingSeen) {
                    overlay = "Memuat…"
                    emit()
                }
            }

            EmbeddedPlaybackEvent.PLAYING,
            EmbeddedPlaybackEvent.VOUT -> {
                playingSeen = true
                playing = true
                finished = false
                overlay = null
                if (!offsetApplied && pendingOffsetMillis > 0L) {
                    media.time = pendingOffsetMillis
                }
                pendingOffsetMillis = 0L
                offsetApplied = true
                emit()
            }

            EmbeddedPlaybackEvent.PAUSED -> {
                playing = false
                emit()
            }

            EmbeddedPlaybackEvent.TIME_CHANGED,
            EmbeddedPlaybackEvent.LENGTH_CHANGED -> Unit

            EmbeddedPlaybackEvent.END_REACHED -> {
                playingSeen = false
                playing = false
                finished = true
                overlay = "Selesai"
                emit()
            }

            EmbeddedPlaybackEvent.ERROR -> {
                playingSeen = false
                playing = false
                overlay = "Gagal memutar rekaman"
                emit()
            }
        }
    }

    private fun emit() {
        onUi(EmbeddedPlaybackUiState(overlay, playing))
    }

    companion object {
        fun create(
            libVlc: LibVLC,
            videoLayout: VLCVideoLayout,
            onUi: (EmbeddedPlaybackUiState) -> Unit
        ): EmbeddedPlaybackController {
            val handler = Handler(Looper.getMainLooper())
            return EmbeddedPlaybackController(
                VlcEmbeddedPlaybackMedia(libVlc, videoLayout),
                { action -> handler.post(action) },
                onUi
            )
        }
    }
}
