package id.cctv.monitor.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PlaybackTimeTest {
    @Test fun dayRange_usesZoneAndUtcStamp() {
        val zone = TimeZone.getTimeZone("Asia/Jakarta")
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.parse("2026-11-24")!!.time
        val (start, end) = PlaybackTime.dayRange(day, zone)
        assertEquals("20261123T170000Z", PlaybackTime.utcStamp(start))
        assertEquals("20261124T170000Z", PlaybackTime.utcStamp(end))
        assertEquals("00:00–00:05", PlaybackTime.label(start, start + 5L * 60_000, zone))
    }
}
