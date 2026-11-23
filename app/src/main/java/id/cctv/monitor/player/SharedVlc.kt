package id.cctv.monitor.player

import android.content.Context
import org.videolan.libvlc.LibVLC

/**
 * Satu instance LibVLC untuk seluruh aplikasi (grid, fullscreen, perekam klip).
 * Membuat LibVLC baru sangat mahal dan membuat aplikasi lambat merespons.
 */
internal object SharedVlc {

    @Volatile
    private var instance: LibVLC? = null

    fun get(context: Context): LibVLC {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: LibVLC(
                context.applicationContext,
                arrayListOf(
                    "--rtsp-tcp",
                    "--no-audio",
                    "--no-video-title-show",
                    "--network-caching=300",
                    "--live-caching=300",
                    "--drop-late-frames",
                    "--skip-frames"
                )
            ).also { instance = it }
        }
    }
}
