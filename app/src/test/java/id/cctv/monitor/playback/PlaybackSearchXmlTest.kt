package id.cctv.monitor.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSearchXmlTest {
    @Test fun parse_sortedClipsAndEmptyBody() {
        val xml = """<?xml version="1.0"?><CMSearchResult><matchList>
          <searchMatchItem><trackID>201</trackID><timeSpan>
            <startTime>2026-11-24T01:00:00Z</startTime><endTime>2026-11-24T01:05:00Z</endTime>
          </timeSpan></searchMatchItem>
          <searchMatchItem><trackID>101</trackID><timeSpan>
            <startTime>2026-11-23T23:00:00Z</startTime><endTime>2026-11-23T23:03:00Z</endTime>
          </timeSpan></searchMatchItem>
        </matchList></CMSearchResult>"""
        val clips = PlaybackSearchXml.parse(xml)
        assertEquals(listOf(101, 201), clips.map { it.trackId })
        assertEquals(0, PlaybackSearchXml.parse("").size)
    }

    @Test fun request_usesExtendedIsoStamps() {
        val xml = PlaybackSearchXml.request(101, "2026-11-23T17:00:00Z", "2026-11-24T17:00:00Z")
        assertEquals(true, xml.contains("<startTime>2026-11-23T17:00:00Z</startTime>"))
        assertEquals(true, xml.contains("<endTime>2026-11-24T17:00:00Z</endTime>"))
    }

    @Test fun request_usesGuidSearchId() {
        val xml = PlaybackSearchXml.request(101, "2026-11-23T17:00:00Z", "2026-11-24T17:00:00Z")
        val id = Regex("<searchID>([^<]+)</searchID>").find(xml)?.groupValues?.get(1).orEmpty()
        assertEquals(
            true,
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .matches(id)
        )
    }
}
