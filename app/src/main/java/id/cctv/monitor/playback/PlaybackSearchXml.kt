package id.cctv.monitor.playback

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

object PlaybackSearchXml {

    fun request(trackId: Int, startUtc: String, endUtc: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<CMSearchDescription>
  <searchID>${UUID.randomUUID().toString().uppercase()}</searchID>
  <trackIDList><trackID>$trackId</trackID></trackIDList>
  <timeSpanList><timeSpan>
    <startTime>$startUtc</startTime>
    <endTime>$endUtc</endTime>
  </timeSpan></timeSpanList>
  <maxResults>100</maxResults>
</CMSearchDescription>"""

    fun parse(body: String): List<RecordingClip> {
        if (body.isBlank()) return emptyList()
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            val document = factory.newDocumentBuilder()
                .parse(InputSource(StringReader(body.removePrefix("\uFEFF"))))
            val clips = mutableListOf<RecordingClip>()
            val items = document.getElementsByTagName("searchMatchItem")
            for (i in 0 until items.length) {
                val item = items.item(i) as? Element ?: continue
                val trackId = item.text("trackID")?.toIntOrNull() ?: continue
                val startMillis = item.text("startTime")?.let(PlaybackTime::parseStamp) ?: continue
                val endMillis = item.text("endTime")?.let(PlaybackTime::parseStamp) ?: continue
                clips += RecordingClip(trackId, startMillis, endMillis)
            }
            clips.sortedBy { it.startMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun Element.text(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
