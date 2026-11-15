package id.cctv.monitor.net

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Utilitas XML ISAPI. Semua pencarian memakai localName (case-insensitive)
 * agar tetap bekerja meski namespace/prefix firmware berbeda.
 */
object CameraXml {

    fun value(xml: String, localName: String): String? {
        val doc = parse(xml) ?: return null
        return doc.elements(localName).firstOrNull()?.textContent?.trim()
    }

    // ---------- privacy mask ----------

    fun setMaskEnabled(xml: String, width: Int, height: Int, enable: Boolean): String? {
        val doc = parse(xml) ?: return null
        val list = doc.elements("PrivacyMaskRegionList").firstOrNull()
            ?: doc.elements("RegionList").firstOrNull()
            ?: return null

        // Koordinat privacy mask memakai normalizedScreenSize (mis. 704x576),
        // bukan resolusi main stream.
        val normW = doc.elements("normalizedScreenWidth").firstOrNull()
            ?.textContent?.trim()?.toIntOrNull()
        val normH = doc.elements("normalizedScreenHeight").firstOrNull()
            ?.textContent?.trim()?.toIntOrNull()
        val w = if (normW != null && normW > 0) normW else width
        val h = if (normH != null && normH > 0) normH else height

        doc.elements("enabled").forEach { it.textContent = if (enable) "true" else "false" }
        if (!enable) return serialize(doc)

        // Firmware DS-2CD1321G0-I (V5.11.0) menerima region hanya bila
        // regionType="quadrilateral" dan region berisi 4 titik sudut.
        ensureRegionType(doc)

        val regions = regionElements(list)
        if (regions.isEmpty()) {
            // Firmware dapat mengembalikan daftar kosong (size="1"); buat region penuh.
            list.appendChild(createRegion(doc, list, w, h))
            return serialize(doc)
        }

        regions.forEach { region ->
            val points = doc.descendants(region).filter {
                it.localNameOrTag().equals("RegionCoordinates", ignoreCase = true)
            }
            when {
                points.size >= 4 -> {
                    setPoint(points[0], 0, 0)
                    setPoint(points[1], w - 1, 0)
                    setPoint(points[2], w - 1, h - 1)
                    setPoint(points[3], 0, h - 1)
                }
                points.size == 2 -> {
                    setPoint(points[0], 0, 0)
                    setPoint(points[1], w - 1, h - 1)
                }
                else -> doc.descendants(region).forEach { node ->
                    when (node.localNameOrTag().lowercase()) {
                        "positionx" -> node.textContent = "0"
                        "positiony" -> node.textContent = "0"
                        "width" -> node.textContent = w.toString()
                        "height" -> node.textContent = h.toString()
                    }
                }
            }
            region.directChild("enabled")?.textContent = "true"
        }
        return serialize(doc)
    }

    /** Pastikan <regionType>quadrilateral</regionType> ada (wajib di firmware ini). */
    private fun ensureRegionType(doc: Document) {
        val root = doc.documentElement ?: return
        val existing = root.directChild("regionType") ?: doc.elements("regionType").firstOrNull()
        if (existing != null) {
            existing.textContent = "quadrilateral"
            return
        }
        val ns = root.namespaceURI
        val node = if (ns.isNullOrBlank()) {
            doc.createElement("regionType")
        } else {
            doc.createElementNS(ns, "regionType")
        }
        node.textContent = "quadrilateral"
        root.appendChild(node)
    }

    private fun setPoint(coordinates: Element, x: Int, y: Int) {
        val children = coordinates.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                when (child.localNameOrTag().lowercase()) {
                    "positionx" -> child.textContent = x.toString()
                    "positiony" -> child.textContent = y.toString()
                }
            }
        }
    }

    private fun regionElements(list: Element): List<Element> {
        val result = ArrayList<Element>()
        val children = list.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                val name = child.localNameOrTag().lowercase()
                if (name == "privacymaskregion" || name == "region") result.add(child)
            }
        }
        return result
    }

    private fun createRegion(doc: Document, list: Element, width: Int, height: Int): Element {
        val ns = list.namespaceURI
        fun element(name: String): Element =
            if (ns.isNullOrBlank()) doc.createElement(name) else doc.createElementNS(ns, name)

        fun point(x: Int, y: Int): Element {
            val coordinates = element("RegionCoordinates")
            coordinates.appendChild(element("positionX").apply { textContent = x.toString() })
            coordinates.appendChild(element("positionY").apply { textContent = y.toString() })
            return coordinates
        }

        val region = element("PrivacyMaskRegion")
        region.appendChild(element("id").apply { textContent = "1" })
        region.appendChild(element("enabled").apply { textContent = "true" })
        val coordinatesList = element("RegionCoordinatesList")
        // Empat titik sudut (kiri-atas, kanan-atas, kanan-bawah, kiri-bawah).
        coordinatesList.appendChild(point(0, 0))
        coordinatesList.appendChild(point(width - 1, 0))
        coordinatesList.appendChild(point(width - 1, height - 1))
        coordinatesList.appendChild(point(0, height - 1))
        region.appendChild(coordinatesList)
        return region
    }

    fun maskHasEnabled(xml: String): Boolean? {
        val doc = parse(xml) ?: return null
        val nodes = doc.elements("enabled")
        if (nodes.isEmpty()) return null
        return nodes.any { it.textContent.trim().matches(Regex("^(true|1)$", RegexOption.IGNORE_CASE)) }
    }

    // ---------- stream channel / NVR channel ----------

    fun setRootEnabled(xml: String, enable: Boolean): String? {
        val doc = parse(xml) ?: return null
        val root = doc.documentElement ?: return null
        val node = root.directChild("enabled") ?: doc.elements("enabled").firstOrNull() ?: return null
        node.textContent = if (enable) "true" else "false"
        return serialize(doc)
    }

    /** Tambahkan <enabled> bila belum ada, lalu set nilainya. */
    fun setOrAddRootEnabled(xml: String, enable: Boolean): String? {
        val doc = parse(xml) ?: return null
        val root = doc.documentElement ?: return null
        val value = if (enable) "true" else "false"
        val node = root.directChild("enabled") ?: doc.elements("enabled").firstOrNull()
        if (node != null) {
            node.textContent = value
            return serialize(doc)
        }
        val ns = root.namespaceURI
        val created = if (ns.isNullOrBlank()) {
            doc.createElement("enabled")
        } else {
            doc.createElementNS(ns, "enabled")
        }
        created.textContent = value
        root.appendChild(created)
        return serialize(doc)
    }

    fun setValue(xml: String, localName: String, value: String): String? {
        val doc = parse(xml) ?: return null
        val nodes = doc.elements(localName)
        if (nodes.isEmpty()) return null
        nodes.forEach { it.textContent = value }
        return serialize(doc)
    }

    fun rootEnabled(xml: String): Boolean? {
        val doc = parse(xml) ?: return null
        val root = doc.documentElement ?: return null
        val node = root.directChild("enabled") ?: doc.elements("enabled").firstOrNull() ?: return null
        return node.textContent.trim().matches(Regex("^(true|1)$", RegexOption.IGNORE_CASE))
    }

    // ---------- layanan RTSP di /ISAPI/Security/adminAccesses ----------

    fun setRtspEnabled(xml: String, enable: Boolean): String? {
        val doc = parse(xml) ?: return null
        val target = rtspEntry(doc) ?: return null
        var enabled = target.directChild("enabled")
        if (enabled == null) {
            val ns = target.namespaceURI
            enabled = if (ns.isNullOrBlank()) {
                doc.createElement("enabled")
            } else {
                doc.createElementNS(ns, "enabled")
            }
            target.appendChild(enabled)
        }
        enabled.textContent = if (enable) "true" else "false"
        return serialize(doc)
    }

    fun rtspEnabled(xml: String): Boolean? {
        val doc = parse(xml) ?: return null
        val target = rtspEntry(doc) ?: return null
        val enabled = target.directChild("enabled") ?: return null
        return enabled.textContent.trim().matches(Regex("^(true|1)$", RegexOption.IGNORE_CASE))
    }

    private fun rtspEntry(doc: Document): Element? {
        val protocol = doc.elements("protocol").firstOrNull {
            it.textContent.trim().equals("RTSP", ignoreCase = true)
        } ?: return null
        return protocol.parentNode as? Element
    }

    // ---------- helper DOM ----------

    private fun parse(xml: String): Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    } catch (t: Throwable) {
        null
    }

    private fun serialize(doc: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        transformer.setOutputProperty(OutputKeys.INDENT, "no")
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    private fun Document.elements(name: String): List<Element> =
        allElements().filter { it.localNameOrTag().equals(name, ignoreCase = true) }

    private fun Document.allElements(): List<Element> {
        val result = ArrayList<Element>()
        val root = documentElement ?: return result
        fun walk(element: Element) {
            result.add(element)
            val children = element.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child is Element) walk(child)
            }
        }
        walk(root)
        return result
    }

    private fun Document.descendants(element: Element): List<Element> {
        val result = ArrayList<Element>()
        fun walk(current: Element) {
            val children = current.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child is Element) {
                    result.add(child)
                    walk(child)
                }
            }
        }
        walk(element)
        return result
    }

    private fun Element.directChild(name: String): Element? {
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.localNameOrTag().equals(name, ignoreCase = true)) {
                return child
            }
        }
        return null
    }

    private fun Node.localNameOrTag(): String = localName ?: (nodeName ?: "").substringAfter(':')
}
