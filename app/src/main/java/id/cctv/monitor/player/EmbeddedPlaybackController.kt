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
    private var mediaLoaded = false
    private var released = false
    private var overlay: String? = null
    private var playing = false
    private var playingSeen = false
    private var finished = false
    private var pendingOffsetMillis = 0L
    private var offsetApplied = false

    init {
        media.listener = { event -> dispatch { onMediaEvent(event) } }
    }

    /** State tombol mengikuti niat user, bukan event VLC yang masih antre. */
    val isPlaying: Boolean get() = playing

    fun load(file: File, offsetMillis: Long) {
        if (released) return
        pendingOffsetMillis = offsetMillis.coerceAtLeast(0L)
        offsetApplied = false
        playing = true
        playingSeen = false
        finished = false
        mediaLoaded = true
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
        if (!mediaLoaded || released) return
        val target = offsetMillis.coerceAtLeast(0L)
        if (finished) {
            pendingOffsetMillis = target
            offsetApplied = false
            finished = false
            playingSeen = false
            playing = true
            overlay = null
            emit()
            media.stop()
            media.play()
        } else if (!playingSeen) {
            // VLC mengabaikan setTime sebelum input ada; simpan dan pakai saat PLAYING.
            pendingOffsetMillis = target
            offsetApplied = false
            emit()
        } else {
            media.time = target
            pendingOffsetMillis = 0L
            offsetApplied = true
            emit()
        }
    }

    fun playPause() {
        if (!mediaLoaded || released) return
        if (finished) {
            finished = false
            playingSeen = false
            playing = true
            overlay = null
            emit()
            media.stop()
            media.play()
            return
        }
        playing = !playing
        if (playing) media.play() else media.pause()
        emit()
    }

    fun pause() {
        if (!mediaLoaded || released || !playing) return
        playing = false
        media.pause()
        emit()
    }

    fun resume() {
        if (!mediaLoaded || released || finished || playing) return
        playing = true
        media.play()
        emit()
    }

    fun stop() {
        media.stop()
        mediaLoaded = false
        playing = false
        playingSeen = false
        finished = false
        pendingOffsetMillis = 0L
        offsetApplied = true
        overlay = null
        emit()
    }

    fun release() {
        released = true
        media.listener = null
        media.stop()
        if (viewsAttached) media.detachViews()
        media.release()
        viewsAttached = false
        mediaLoaded = false
        playing = false
        playingSeen = false
        finished = false
        pendingOffsetMillis = 0L
        offsetApplied = true
        overlay = null
    }

    private fun onMediaEvent(event: EmbeddedPlaybackEvent) {
        if (released) return
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
                finished = false
                overlay = null
                if (!offsetApplied && pendingOffsetMillis > 0L) {
                    media.time = pendingOffsetMillis
                }
                pendingOffsetMillis = 0L
                offsetApplied = true
                emit()
            }

            // PAUSED bisa antre dari tap lama; jangan timpa niat play terbaru.
            EmbeddedPlaybackEvent.PAUSED -> Unit

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
        if (released) return
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
