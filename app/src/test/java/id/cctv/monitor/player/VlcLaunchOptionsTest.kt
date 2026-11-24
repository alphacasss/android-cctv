package id.cctv.monitor.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcLaunchOptionsTest {

    @Test
    fun base_capsDecoderThreadsAndKeepsExistingFlags() {
        assertEquals(2, VlcLaunchOptions.DECODER_THREADS)
        val options = VlcLaunchOptions.base()
        assertTrue(options.contains("--no-audio"))
        assertTrue(options.contains("--avcodec-threads=2"))
        assertTrue(options.contains("--drop-late-frames"))
    }
}
