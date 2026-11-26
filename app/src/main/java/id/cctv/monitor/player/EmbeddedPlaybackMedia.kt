package id.cctv.monitor.player

import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/** Event VLC yang dipakai controller; JVM test tidak perlu memuat native VLC. */
enum class EmbeddedPlaybackEvent {
    OPENING,
    BUFFERING,
    PLAYING,
    VOUT,
    PAUSED,
    TIME_CHANGED,
    LENGTH_CHANGED,
    END_REACHED,
    ERROR
}

/** Permukaan minimal MediaPlayer yang dipakai satu controller playback. */
interface EmbeddedPlaybackMedia {
    var listener: ((EmbeddedPlaybackEvent) -> Unit)?
    fun attachViews()
    fun detachViews()
    fun setMedia(file: File)
    fun play()
    fun pause()
    fun stop()
    fun release()
    var time: Long
    val length: Long
}

/** Adapter produksi: satu MediaPlayer VLC yang menempel di VLCVideoLayout. */
class VlcEmbeddedPlaybackMedia(
    private val libVlc: LibVLC,
    private val videoLayout: VLCVideoLayout
) : EmbeddedPlaybackMedia {

    private val player = MediaPlayer(libVlc).apply {
        setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
    }
    private var nativeListener: MediaPlayer.EventListener? = null

    override var listener: ((EmbeddedPlaybackEvent) -> Unit)? = null
        set(value) {
            field = value
            if (value == null) {
                if (nativeListener != null) player.setEventListener(null)
                nativeListener = null
            } else {
                val adapter = MediaPlayer.EventListener { event ->
                    fromNative(event.type)?.let(value)
                }
                nativeListener = adapter
                player.setEventListener(adapter)
            }
        }

    override fun attachViews() {
        player.attachViews(videoLayout, null, false, true)
    }

    override fun detachViews() {
        player.detachViews()
    }

    override fun setMedia(file: File) {
        val media = Media(libVlc, Uri.fromFile(file)).apply {
            addOption(":no-video-title-show")
            addOption(":file-caching=300")
        }
        player.setMedia(media)
        media.release()
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun stop() = player.stop()

    override fun release() = player.release()

    override var time: Long
        get() = player.time
        set(value) {
            player.time = value
        }

    override val length: Long
        get() = player.length

    private fun fromNative(type: Int): EmbeddedPlaybackEvent? = when (type) {
        MediaPlayer.Event.Opening -> EmbeddedPlaybackEvent.OPENING
        MediaPlayer.Event.Buffering -> EmbeddedPlaybackEvent.BUFFERING
        MediaPlayer.Event.Playing -> EmbeddedPlaybackEvent.PLAYING
        MediaPlayer.Event.Vout -> EmbeddedPlaybackEvent.VOUT
        MediaPlayer.Event.Paused -> EmbeddedPlaybackEvent.PAUSED
        MediaPlayer.Event.TimeChanged -> EmbeddedPlaybackEvent.TIME_CHANGED
        MediaPlayer.Event.LengthChanged -> EmbeddedPlaybackEvent.LENGTH_CHANGED
        MediaPlayer.Event.EndReached -> EmbeddedPlaybackEvent.END_REACHED
        MediaPlayer.Event.EncounteredError -> EmbeddedPlaybackEvent.ERROR
        else -> null
    }
}
