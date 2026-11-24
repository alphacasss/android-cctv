package id.cctv.monitor.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPlaybackTimeTest {

    @Test
    fun format_showsMinutesAndSeconds() {
        assertEquals("00:00", RecordingPlaybackTime.format(0L))
        assertEquals("01:01", RecordingPlaybackTime.format(61_000L))
    }

    @Test
    fun format_showsHoursWhenNeeded() {
        assertEquals("1:01:01", RecordingPlaybackTime.format(3_661_000L))
    }

    @Test
    fun seek_mapsProgressToClampedMillis() {
        assertEquals(5_000L, RecordingPlaybackTime.seekMillis(10_000L, 50, 100))
        assertEquals(0L, RecordingPlaybackTime.seekMillis(10_000L, -5, 100))
        assertEquals(10_000L, RecordingPlaybackTime.seekMillis(10_000L, 200, 100))
    }

    @Test
    fun seek_withoutDuration_staysAtZero() {
        assertEquals(0L, RecordingPlaybackTime.seekMillis(0L, 50, 100))
        assertEquals(0L, RecordingPlaybackTime.seekMillis(-1L, 50, 100))
    }
}
