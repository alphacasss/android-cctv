package id.cctv.monitor

import id.cctv.monitor.data.MediaItem
import java.util.Locale

enum class HistoryPlaybackRoute { PLAY_IN_APP, SHOW_PHOTO, FILE_MISSING }

/** Keputusan murni saat item Riwayat diketuk. Tidak bergantung pada Activity. */
object HistoryPlaybackRouter {

    private val VIDEO_EXTENSIONS = setOf("ts", "mp4")

    fun route(item: MediaItem): HistoryPlaybackRoute {
        if (!item.isVideo) return HistoryPlaybackRoute.SHOW_PHOTO
        if (!item.file.isFile) return HistoryPlaybackRoute.FILE_MISSING
        val extension = item.file.extension.lowercase(Locale.US)
        if (extension !in VIDEO_EXTENSIONS) return HistoryPlaybackRoute.FILE_MISSING
        return HistoryPlaybackRoute.PLAY_IN_APP
    }
}
