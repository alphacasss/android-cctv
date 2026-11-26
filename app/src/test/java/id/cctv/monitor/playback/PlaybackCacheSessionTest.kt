package id.cctv.monitor.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PlaybackCacheSessionTest {
    private val clip = RecordingClip(101, 1_000L, 61_000L)
    private val key = PlaybackClipKey(7, clip.trackId, clip.startMillis)

    @Test fun plan_downloadsFirstClipAndComputesOffset() {
        val session = PlaybackCacheSession()
        assertEquals(PlaybackCacheAction.DOWNLOAD, session.plan(key, clip, 31_000L).action)
        assertEquals(30_000L, session.plan(key, clip, 31_000L).offsetMillis)
    }

    @Test fun plan_reusesSameClip() {
        val session = PlaybackCacheSession()
        val file = File("clip.mp4")
        session.adopt(key, file)

        val plan = session.plan(key, clip, 41_000L)

        assertEquals(PlaybackCacheAction.REUSE, plan.action)
        assertEquals(40_000L, plan.offsetMillis)
        assertEquals(file, session.loadedFile())
    }

    @Test fun plan_downloadsWhenCameraChanges() {
        val session = PlaybackCacheSession()
        session.adopt(key, File("old.mp4"))

        val plan = session.plan(key.copy(cameraId = 8), clip, 10_000L)

        assertEquals(PlaybackCacheAction.DOWNLOAD, plan.action)
        assertEquals(9_000L, plan.offsetMillis)
    }

    @Test fun plan_atClipEndStartsFromZero() {
        val session = PlaybackCacheSession()
        session.adopt(key, File("clip.mp4"))

        val plan = session.plan(key, clip, clip.endMillis)

        assertEquals(PlaybackCacheAction.REUSE, plan.action)
        assertEquals(0L, plan.offsetMillis)
    }

    @Test fun reset_forcesDownload() {
        val session = PlaybackCacheSession()
        session.adopt(key, File("clip.mp4"))
        session.reset()

        assertNull(session.loadedFile())
        assertNull(session.loadedKey())
        assertEquals(PlaybackCacheAction.DOWNLOAD, session.plan(key, clip, 1_000L).action)
    }

    @Test fun adopt_returnsPreviousFileForCleanup() {
        val session = PlaybackCacheSession()
        val old = File("old.mp4")
        val next = File("next.mp4")
        session.adopt(key, old)

        assertEquals(old, session.adopt(key, next))
        assertEquals(next, session.loadedFile())
    }
}
