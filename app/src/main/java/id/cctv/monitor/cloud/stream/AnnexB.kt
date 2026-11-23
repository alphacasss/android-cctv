package id.cctv.monitor.cloud.stream

/**
 * Utilitas stream Annex-B (H.264/H.265) yang dipakai demuxer dan dekripsi
 * video Hikvision.
 */
internal object AnnexB {

    val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** Posisi (start, end) setiap NAL unit tanpa start code. */
    fun spans(data: ByteArray): List<IntRange> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {
                starts += i
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return emptyList()
        val result = ArrayList<IntRange>(starts.size)
        for (index in starts.indices) {
            var start = starts[index] + 3
            var end = if (index + 1 < starts.size) starts[index + 1] else data.size
            if (end < data.size && data[end - 1] == 0.toByte()) end--
            if (start <= end) result += start until end
        }
        return result
    }

    fun nalTypes(data: ByteArray, codec: String): List<Int> {
        val shift = if (codec == "h265") 1 else 0
        val mask = if (codec == "h265") 0x3F else 0x1F
        return spans(data).take(8).map { range ->
            (data[range.first].toInt() ushr shift) and mask
        }
    }

    /** True bila access unit memuat IDR/IRAP (keyframe). */
    fun isKeyframe(data: ByteArray, codec: String): Boolean = spans(data).any { range ->
        val header = data[range.first].toInt() and 0xFF
        if (codec == "h265") {
            val type = (header ushr 1) and 0x3F
            type in 16..23
        } else {
            header and 0x1F == 5
        }
    }

    /**
     * Cek byte pertama VPS (H.265) atau SPS (H.264) yang ditetapkan spesifikasi.
     * Dipakai untuk membedakan video terenkripsi dari kunci yang salah.
     */
    fun paramsValid(data: ByteArray, codec: String): Boolean? {
        for (range in spans(data)) {
            val start = range.first
            val length = range.last - range.first + 1
            if (codec == "h265") {
                if (length >= 6 && (data[start].toInt() ushr 1) and 0x3F == 32) {
                    return isPlainVps(data.copyOfRange(start, start + length))
                }
            } else if (length >= 2 && data[start].toInt() and 0x1F == 7) {
                return data[start + 1].toInt() and 0xFF in H264_PROFILES
            }
        }
        return null
    }

    /**
     * True bila NAL tanpa start code adalah VPS H.265 yang benar-benar jernih.
     *
     * VPS Hikvision selalu diawali `0c 01 ff ff` tepat setelah dua byte header
     * NAL: `vps_video_parameter_set_id=0`, base layer aktif, satu layer, nol
     * sub-layer, lalu `vps_reserved_0xffff_16bits`. Setelah itu datang
     * `profile_tier_level`; `profile_space=3` adalah nilai terlarang dan
     * `general_profile_idc` yang wajar hanya 1..5 atau 9. Pemeriksaan ini
     * membedakan kunci AES yang benar dari tebakan yang kebetulan cocok pada
     * empat byte pertama (mis. hasil brute force).
     */
    fun isPlainVps(nal: ByteArray): Boolean {
        if (nal.size < 7) return false
        if ((nal[0].toInt() and 0x7E) != 0x40) return false
        if (nal[2] != 0x0C.toByte() || nal[3] != 0x01.toByte() ||
            nal[4] != 0xFF.toByte() || nal[5] != 0xFF.toByte()
        ) return false
        val ptl = nal[6].toInt() and 0xFF
        if (ptl ushr 6 == 3) return false
        val profileIdc = ptl and 0x1F
        return profileIdc in 1..5 || profileIdc == 9
    }

    /** Varian [paramsValid] untuk satu NAL tanpa start code (jalur RTP). */
    fun nalParamsValid(nal: ByteArray, hevc: Boolean): Boolean? {
        if (hevc) {
            if (nal.size >= 6 && (nal[0].toInt() ushr 1) and 0x3F == 32) {
                return isPlainVps(nal)
            }
        } else {
            if (nal.size >= 2 && nal[0].toInt() and 0x1F == 7) {
                return nal[1].toInt() and 0xFF in H264_PROFILES
            }
        }
        return null
    }

    /** Ambil NAL tertentu (mis. SPS/PPS/VPS) sebagai potongan Annex-B utuh. */
    fun params(data: ByteArray, codec: String): ByteArray? {
        val parts = ArrayList<ByteArray>()
        for (range in spans(data)) {
            val header = data[range.first].toInt() and 0xFF
            val wanted = if (codec == "h265") {
                (header ushr 1) and 0x3F in 32..34
            } else {
                header and 0x1F == 7 || header and 0x1F == 8
            }
            if (!wanted) continue
            val nal = ByteArray(4 + (range.last - range.first + 1))
            System.arraycopy(START_CODE, 0, nal, 0, 4)
            System.arraycopy(data, range.first, nal, 4, range.last - range.first + 1)
            parts += nal
            if (codec == "h264") break
        }
        if (parts.isEmpty()) return null
        val size = parts.sumOf { it.size }
        val out = ByteArray(size)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    private val H264_PROFILES = setOf(
        66, 77, 88, 100, 110, 122, 244, 44, 83, 86, 118, 128
    )
}
