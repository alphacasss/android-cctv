package id.cctv.monitor.cloud.stream

import java.io.ByteArrayOutputStream

/** Nilai hasil decode: [Long] untuk varint, [ByteArray] untuk string/bytes. */
internal typealias ProtoFields = Map<Int, List<Any>>

/**
 * Encoder/decoder protobuf minimal yang dipakai protokol `ysproto` Hikvision.
 * Pesan stream hanya memakai varint (wire type 0) dan length-delimited
 * (wire type 2), jadi implementasi penuh protobuf tidak diperlukan.
 */
internal object Proto {

    fun varint(value: Int): ByteArray {
        val out = ByteArrayOutputStream(5)
        var n = value
        while (true) {
            out.write((n and 0x7F) or (if (n > 0x7F) 0x80 else 0))
            n = n ushr 7
            if (n == 0) break
        }
        return out.toByteArray()
    }

    fun pbStr(field: Int, value: String): ByteArray =
        pbStr(field, value.toByteArray(Charsets.UTF_8))

    fun pbStr(field: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(value.size + 8)
        out.write((field shl 3) or 2)
        out.write(varint(value.size))
        out.write(value)
        return out.toByteArray()
    }

    fun pbInt(field: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream(6)
        out.write(field shl 3)
        out.write(varint(value))
        return out.toByteArray()
    }

    fun decode(data: ByteArray): ProtoFields {
        val result = LinkedHashMap<Int, MutableList<Any>>()
        var i = 0
        while (i < data.size) {
            var key = 0L
            var shift = 0
            while (true) {
                val b = data[i++].toInt() and 0xFF
                key = key or ((b and 0x7F).toLong() shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            val field = (key ushr 3).toInt()
            when (key.toInt() and 7) {
                0 -> {
                    var value = 0L
                    var bits = 0
                    while (true) {
                        val b = data[i++].toInt() and 0xFF
                        value = value or ((b and 0x7F).toLong() shl bits)
                        bits += 7
                        if (b and 0x80 == 0) break
                    }
                    result.getOrPut(field) { ArrayList() }.add(value)
                }

                2 -> {
                    var length = 0
                    var bits = 0
                    while (true) {
                        val b = data[i++].toInt() and 0xFF
                        length = length or ((b and 0x7F) shl bits)
                        bits += 7
                        if (b and 0x80 == 0) break
                    }
                    val end = minOf(data.size, i + length)
                    result.getOrPut(field) { ArrayList() }.add(data.copyOfRange(i, end))
                    i = end
                }

                else -> throw IllegalArgumentException("Wire type protobuf tidak didukung.")
            }
        }
        return result
    }

    fun result(fields: ProtoFields): Int = (fields[1]?.firstOrNull() as? Long)?.toInt() ?: 0

    fun text(fields: ProtoFields, field: Int): String {
        val raw = fields[field]?.firstOrNull() ?: return ""
        return when (raw) {
            is ByteArray -> String(raw, Charsets.UTF_8)
            else -> raw.toString()
        }
    }

    fun bytes(fields: ProtoFields, field: Int): ByteArray? =
        fields[field]?.firstOrNull() as? ByteArray
}
