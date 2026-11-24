package id.cctv.monitor.player

import id.cctv.monitor.playback.SeekMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingMediaResolverTest {
    @Test fun uriWinsAndDisablesSeek() {
        val media = RecordingMediaResolver.resolve(
            " rtsp://admin:pw@192.168.1.64:554/Streaming/tracks/101?starttime=a&endtime=b ",
            "/sdcard/clip.mp4"
        )
        assertEquals("rtsp://admin:pw@192.168.1.64:554/Streaming/tracks/101?starttime=a&endtime=b", media.mrl)
        assertEquals(SeekMode.DISABLED, media.seekMode)
    }

    @Test fun filePathKeepsSeek() {
        val media = RecordingMediaResolver.resolve(null, " /sdcard/clip.mp4 ")
        assertEquals("/sdcard/clip.mp4", media.mrl)
        assertEquals(SeekMode.ENABLED, media.seekMode)
    }

    @Test fun blankExtrasAreEmpty() {
        val media = RecordingMediaResolver.resolve("  ", null)
        assertEquals("", media.mrl)
        assertEquals(SeekMode.ENABLED, media.seekMode)
    }
}
