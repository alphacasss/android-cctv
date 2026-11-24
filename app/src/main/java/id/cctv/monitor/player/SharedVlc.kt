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
                VlcLaunchOptions.base()
            ).also { instance = it }
        }
    }
}
