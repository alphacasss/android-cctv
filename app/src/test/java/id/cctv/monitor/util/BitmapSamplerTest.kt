package id.cctv.monitor.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapSamplerTest {

    @Test
    fun sampleSizeFor_returnsPowerOfTwoWithinMaxDimension() {
        assertEquals(16, BitmapSampler.sampleSizeFor(1920, 1080, 160))
        assertEquals(4, BitmapSampler.sampleSizeFor(640, 480, 160))
        assertEquals(2, BitmapSampler.sampleSizeFor(100, 200, 160))
        assertEquals(2, BitmapSampler.sampleSizeFor(511, 511, 256))
        assertEquals(1, BitmapSampler.sampleSizeFor(0, 0, 160))
    }
}
