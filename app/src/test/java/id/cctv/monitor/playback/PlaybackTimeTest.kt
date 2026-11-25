package id.cctv.monitor.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test fun xmlStamp_usesExtendedIso() {
        val zone = TimeZone.getTimeZone("Asia/Jakarta")
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.parse("2026-11-24")!!.time
        val (start, _) = PlaybackTime.dayRange(day, zone)
        assertEquals("2026-11-23T17:00:00Z", PlaybackTime.xmlStamp(start))
    }

    @Test fun parseStamp_acceptsBasicAndExtendedIso() {
        val basic = "20261123T170000Z"
        val extended = "2026-11-23T17:00:00Z"
        assertEquals(PlaybackTime.parseStamp(basic), PlaybackTime.parseStamp(extended))
    }

    @Test fun dayLabel_usesHariIniOnlyForToday() {
        val zone = TimeZone.getTimeZone("Asia/Jakarta")
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.parse("2026-11-24")!!.time
        assertEquals("Hari ini", PlaybackTime.dayLabel(day, day + 3_600_000L, zone))
        val other = PlaybackTime.dayLabel(day - 86_400_000L, day, zone)
        assertNotEquals("Hari ini", other)
    }
}
