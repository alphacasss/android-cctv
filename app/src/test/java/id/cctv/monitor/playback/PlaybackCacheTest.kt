package id.cctv.monitor.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaybackCacheTest {
    @Test fun fileFor_isStableAndSanitized() {
        val clip = RecordingClip(101, 1763970000000L, 1763970300000L)
        val a = PlaybackCache.fileFor(File("/cache"), clip)
        val b = PlaybackCache.fileFor(File("/cache"), clip)
        assertEquals(a.path, b.path)
        assertTrue(a.name.matches(Regex("[a-zA-Z0-9._-]+")))
    }
}
