package id.cctv.monitor.playback

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object PlaybackSearchXml {

    private val dtdMarker = Regex("""<!\s*(?:DOCTYPE|ENTITY)\b""", RegexOption.IGNORE_CASE)

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
        // Android tidak menjamin semua fitur XML di bawah didukung, jadi DTD/entity
        // ditolak eksplisit di sini sebelum parser dipanggil.
        if (body.isBlank() || dtdMarker.containsMatchIn(body)) return emptyList()
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            // Satu fitur yang tidak didukung tidak boleh menggagalkan seluruh parse.
            safeConfig { factory.isNamespaceAware = false }
            safeConfig { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            safeConfig {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            safeConfig {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            safeConfig {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            safeConfig {
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            safeConfig { factory.isXIncludeAware = false }
            safeConfig { factory.isExpandEntityReferences = false }

            val document = factory.newDocumentBuilder()
                .parse(InputSource(StringReader(body.removePrefix("\uFEFF"))))
            val clips = mutableListOf<RecordingClip>()
            val items = document.getElementsByTagName("searchMatchItem")
            for (i in 0 until items.length) {
                val item = items.item(i) as? Element ?: continue
                val trackId = item.text("trackID")?.toIntOrNull() ?: continue
                val startMillis = item.text("startTime")?.let(PlaybackTime::parseStamp) ?: continue
                val endMillis = item.text("endTime")?.let(PlaybackTime::parseStamp) ?: continue
                if (endMillis <= startMillis) continue
                val playbackUri = item.text("playbackURI").orEmpty()
                val eventKind = PlaybackEventKind.from(item.text("eventType") ?: item.text("event"))
                clips += RecordingClip(trackId, startMillis, endMillis, playbackUri, eventKind)
            }
            clips.sortedBy { it.startMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private inline fun safeConfig(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Parser Android/Java bisa menolak fitur tertentu; proteksi utama ada di dtdMarker.
        }
    }

    private fun Element.text(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
