package id.cctv.monitor.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSearchXmlTest {
    @Test fun parse_sortedClipsAndEmptyBody() {
        val xml = """<?xml version="1.0"?><CMSearchResult><matchList>
          <searchMatchItem><trackID>201</trackID><timeSpan>
            <startTime>20261124T010000Z</startTime><endTime>20261124T010500Z</endTime>
          </timeSpan></searchMatchItem>
          <searchMatchItem><trackID>101</trackID><timeSpan>
            <startTime>20261123T230000Z</startTime><endTime>20261123T230300Z</endTime>
          </timeSpan></searchMatchItem>
        </matchList></CMSearchResult>"""
        val clips = PlaybackSearchXml.parse(xml)
        assertEquals(listOf(101, 201), clips.map { it.trackId })
        assertEquals("", PlaybackSearchXml.parse("").size.toString().let { if (it == "0") "" else it })
    }
}
