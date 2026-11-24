package id.cctv.monitor

import id.cctv.monitor.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryPlaybackRouterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun existingVideo_routesToInAppPlayer() {
        val file = temp.newFile("clip.ts")
        val item = item(file, isVideo = true)

        assertEquals(HistoryPlaybackRoute.PLAY_IN_APP, HistoryPlaybackRouter.route(item))
    }

    @Test
    fun photo_routesToPhotoDialog() {
        val file = temp.newFile("snapshot.jpg")
        val item = item(file, isVideo = false)

        assertEquals(HistoryPlaybackRoute.SHOW_PHOTO, HistoryPlaybackRouter.route(item))
    }

    @Test
    fun missingVideo_routesToFileMissingMessage() {
        val file = File(temp.root, "deleted.mp4")
        val item = item(file, isVideo = true)

        assertEquals(HistoryPlaybackRoute.FILE_MISSING, HistoryPlaybackRouter.route(item))
    }

    private fun item(file: File, isVideo: Boolean) = MediaItem(
        file = file,
        name = file.name,
        isVideo = isVideo,
        sizeBytes = file.length(),
        lastModified = file.lastModified(),
        cameraName = "CAM-01"
    )
}
