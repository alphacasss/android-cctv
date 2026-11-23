package id.cctv.monitor.cloud.stream

import android.util.Base64
import android.util.Log

/** Satu access unit video siap didekode (Annex-B, PTS 90 kHz). */
data class VideoUnit(
    val codec: String,
    val pts: Long,
    val data: ByteArray,
    val keyframe: Boolean
)

/**
 * Demuxer paket media relay Hik-Connect: MPEG-PS atau RTP yang membawa
 * H.264/H.265, sekaligus mendekripsi video perangkat berenkripsi.
 */
internal class StreamDemuxer(
    streamHead: ByteArray,
    keys: List<ByteArray> = emptyList(),
    /**
     * Dipanggil sekali saat parameter set terbukti terenkripsi tetapi tidak
     * cocok dengan kunci mana pun. Pemanggil dapat memulihkan kode verifikasi
     * lalu menambahkannya lewat [addKey].
     */
    private val onEncryptedParam: ((ByteArray, Boolean) -> Unit)? = null
) {

    private val hint: String?
    private val rtp: RtpDepay
    private val ps = PsDemux()
    private val keyCandidates = ArrayList<ByteArray>()

    /** Parameter set jernih terakhir per tipe NAL (Annex-B, dengan start code). */
    private val paramCache = HashMap<Int, ByteArray>()

    /** Parameter set terenkripsi yang belum bisa dibuka (tipe NAL -> NAL). */
    private val pendingParams = HashMap<Int, PendingParam>()

    private val paramLock = Any()

    /** Kunci terpilih; null selama belum terbukti ada unit terenkripsi. */
    @Volatile
    private var chosenKey: ByteArray? = null

    /**
     * True bila payload terbukti dikirim jernih. Deskriptor enkripsi sering
     * tetap dikirim perangkat walau stream-nya tidak dienkripsi, jadi isi
     * parameter set yang dipakai sebagai bukti, bukan deskriptor.
     */
    @Volatile
    private var plainStream = false

    /** True setelah parameter set terenkripsi gagal didekripsi. */
    @Volatile
    private var encryptedParamSeen = false

    var container: String? = null
        private set
    var encryptType: Int = 0
        private set

    /** Indeks kunci yang dipakai (untuk log), -1 bila tidak ada kunci. */
    @Volatile
    var keyIndex: Int = -1
        private set

    /** True bila stream terbukti jernih atau kunci AES sudah terverifikasi. */
    val keyVerified: Boolean
        get() = plainStream || chosenKey != null

    /** True bila terbukti terenkripsi tetapi kunci belum tersedia. */
    val awaitingKey: Boolean
        get() = encryptedParamSeen && !keyVerified

    private var loggedDescriptor = false
    private var loggedNoKey = false

    init {
        keyCandidates += keys.filter { candidate -> candidate.any { it != 0.toByte() } }
        val parsed = parseStreamHead(streamHead)
        hint = parsed.first
        container = if (parsed.first == "rtp") "rtp" else null
        rtp = RtpDepay(parsed.second)
        if (keyCandidates.isNotEmpty()) keyIndex = 0
    }

    /**
     * Tambahkan kandidat kunci AES (kode verifikasi yang baru dipulihkan).
     * [verified] menandai kunci yang sudah diuji pada parameter set sehingga
     * langsung dipakai tanpa menunggu VPS berikutnya.
     */
    fun addKey(key: ByteArray, verified: Boolean = false) {
        if (key.isEmpty() || key.all { it == 0.toByte() }) return
        val index = synchronized(keyCandidates) {
            val existing = keyCandidates.indexOfFirst { it.contentEquals(key) }
            if (existing >= 0) {
                existing
            } else {
                keyCandidates += key
                keyCandidates.size - 1
            }
        }
        if (verified) {
            // Jangan langsung memakai kunci "terverifikasi": hanya terima bila
            // ia benar-benar membuka parameter set yang tertahan, supaya hasil
            // pemulihan yang keliru tidak mendekripsi seluruh stream.
            if (unlockParams(key)) {
                chosenKey = key
                keyIndex = index
                encryptedParamSeen = true
            }
        }
    }

    /**
     * Parameter set lengkap yang sudah berhasil didekripsi (Annex-B, urut
     * baku VPS/SPS/PPS). Dipakai untuk "prime" decoder bila parameter set
     * hanya dikirim sekali di awal stream, sebelum kunci tersedia.
     */
    fun parameterSets(codec: String): ByteArray? {
        val hevc = codec == "h265"
        val order = if (hevc) intArrayOf(32, 33, 34) else intArrayOf(7, 8)
        synchronized(paramLock) {
            if (order.any { paramCache[it] == null }) return null
            val out = java.io.ByteArrayOutputStream()
            for (type in order) out.write(paramCache.getValue(type))
            return out.toByteArray()
        }
    }

    /** Buang data acak yang sempat dianggap parameter set terenkripsi. */
    private fun unlockParams(key: ByteArray): Boolean {
        val pending: List<PendingParam>
        synchronized(paramLock) {
            pending = pendingParams.values.toList()
            pendingParams.clear()
        }
        var matched = false
        for (param in pending) {
            val plain = HikEncryption.decryptNal(param.nal, param.hevc, param.type, key)
            if (AnnexB.nalParamsValid(plain, param.hevc) == true) {
                storeParam(plain, param.hevc)
                matched = true
            }
        }
        return matched
    }

    /** Simpan parameter set jernih agar bisa dipakai ulang oleh decoder. */
    private fun storeParam(nal: ByteArray, hevc: Boolean) {
        val type = paramType(nal, hevc) ?: return
        // NAL dari RTP belum punya start code.
        val annexB = if (startsWithCode(nal)) {
            nal
        } else {
            AnnexB.START_CODE + nal
        }
        synchronized(paramLock) { paramCache[type] = annexB }
    }

    /** Simpan parameter set terenkripsi sampai kunci tersedia. */
    private fun rememberParam(nal: ByteArray, hevc: Boolean, type: Int) {
        val paramType = paramType(nal, hevc) ?: return
        synchronized(paramLock) {
            if (paramCache[paramType] == null) {
                pendingParams[paramType] = PendingParam(nal.copyOf(), hevc, type)
            }
        }
    }

    private fun paramType(nal: ByteArray, hevc: Boolean): Int? {
        if (nal.isEmpty()) return null
        val header = nal[0].toInt() and 0xFF
        val type = if (hevc) (header ushr 1) and 0x3F else header and 0x1F
        val valid = if (hevc) type in 32..34 else type == 7 || type == 8
        return if (valid) type else null
    }

    private fun startsWithCode(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()

    private class PendingParam(val nal: ByteArray, val hevc: Boolean, val type: Int)

    fun feed(packet: ByteArray): List<VideoUnit> {
        if (container == null) {
            container = when {
                packet.size >= 3 && packet[0] == 0.toByte() &&
                    packet[1] == 0.toByte() && packet[2] == 1.toByte() -> "ps"

                packet.size >= 12 && (packet[0].toInt() and 0xFF ushr 6) == 2 -> "rtp"
                else -> hint
            }
        }
        return when {
            container == "ps" -> feedPs(packet)
            container == "rtp" && packet.size >= 12 &&
                (packet[0].toInt() and 0xFF ushr 6) == 2 -> feedRtp(packet)

            else -> emptyList()
        }
    }

    /** Demux MPEG-PS dan teruskan tipe enkripsi ke [encryptType]. */
    private fun feedPs(packet: ByteArray): List<VideoUnit> {
        val units = ps.feed(packet)
        if (ps.encryptType != 0) {
            encryptType = ps.encryptType
            if (!loggedDescriptor) {
                loggedDescriptor = true
                Log.d(TAG, "ps descriptor ${ps.descriptorHex} tipe=${ps.encryptType}")
            }
        }
        return decryptUnits(units, ps.encryptType)
    }

    private fun feedRtp(packet: ByteArray): List<VideoUnit> {
        var start = 12 + 4 * (packet[0].toInt() and 0x0F)
        // Enkripsi Hikvision bersifat per paket RTP: paket tanpa header
        // extension `0x4000` tetap jernih walau paket lain terenkripsi.
        var packetType = 0
        if (packet[0].toInt() and 0x10 != 0 && packet.size >= start + 4) {
            val extensionLength = ((packet[start + 2].toInt() and 0xFF) shl 8) or
                (packet[start + 3].toInt() and 0xFF)
            val end = start + 4 + 4 * extensionLength
            if (packet[start] == 0x40.toByte() && packet[start + 1] == 0x00.toByte()) {
                val descriptor = packet.copyOfRange(start + 4, minOf(end, packet.size))
                // Profil 0x4000 menandakan NAL terenkripsi. Deskriptor yang
                // tidak dikenali tetap dianggap AES-128-ECB (mode 2).
                packetType = HikEncryption.encryptType(descriptor).let { if (it == 0) 2 else it }
                encryptType = packetType
                if (!loggedDescriptor) {
                    loggedDescriptor = true
                    Log.d(
                        TAG,
                        "descriptor ${descriptor.joinToString("") { "%02x".format(it) }} " +
                            "tipe=$packetType"
                    )
                }
            }
            start = minOf(end, packet.size)
        }
        val padding = if (packet[0].toInt() and 0x20 != 0) {
            packet[packet.size - 1].toInt() and 0xFF
        } else {
            0
        }
        val end = packet.size - padding
        val payloadType = packet[1].toInt() and 0x7F
        if (end <= start || payloadType != RTP_VIDEO_PAYLOAD_TYPE) return emptyList()
        val payload = packet.copyOfRange(start, end)
        if (payload.size >= 4 && payload[0] == 0.toByte() && payload[1] == 0.toByte() &&
            payload[2] == 1.toByte() && payload[3] == 0xBA.toByte()
        ) {
            return feedPs(payload)
        }
        val timestamp = ((packet[4].toLong() and 0xFF) shl 24) or
            ((packet[5].toLong() and 0xFF) shl 16) or
            ((packet[6].toLong() and 0xFF) shl 8) or
            (packet[7].toLong() and 0xFF)
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val marker = packet[1].toInt() and 0x80 != 0
        val raws = rtp.feed(timestamp, seq, marker, payload, packetType)
        return raws.map { raw ->
            VideoUnit(
                codec = raw.codec,
                pts = raw.pts ?: 0L,
                data = raw.data,
                keyframe = AnnexB.isKeyframe(raw.data, raw.codec)
            )
        }
    }

    private fun decryptUnits(units: List<RawUnit>, type: Int): List<VideoUnit> =
        units.mapNotNull { unit ->
            val data = decode(unit, type) ?: return@mapNotNull null
            VideoUnit(
                codec = unit.codec,
                pts = unit.pts ?: 0L,
                data = data,
                keyframe = AnnexB.isKeyframe(data, unit.codec)
            )
        }

    /**
     * Putuskan sekali apakah payload benar-benar terenkripsi, lalu dekripsi
     * bila perlu.
     *
     * Deskriptor `8006000111210201` ternyata ikut dikirim walau stream jernih,
     * jadi penerapannya tidak boleh tanpa bukti. Bukti yang dipakai adalah
     * parameter set: VPS/SPS jernih selalu lolos [AnnexB.paramsValid],
     * sedangkan yang terenkripsi gagal dan baru lolos setelah didekripsi
     * dengan kunci yang benar. Selama belum ada bukti, unit dibiarkan apa
     * adanya agar stream jernih tidak dirusak.
     *
     * @return data siap dekode, atau null bila unit ditahan karena stream
     *   terenkripsi dan kuncinya belum tersedia.
     */
    private fun decode(unit: RawUnit, type: Int): ByteArray? {
        if (plainStream) {
            storeParams(unit.data, unit.codec)
            return unit.data
        }
        val hevc = unit.codec == "h265"
        val params = AnnexB.paramsValid(unit.data, unit.codec)
        if (params == true) {
            // Parameter set jernih = bukti stream tidak dienkripsi. Keputusan
            // ini juga berlaku untuk paket berikutnya yang membawa deskriptor.
            plainStream = true
            storeParams(unit.data, unit.codec)
            Log.d(TAG, "stream jernih; dekripsi tidak dipakai")
            return unit.data
        }
        val current = chosenKey
        if (current != null) {
            val mode = if (type != 0) type else AES_MODE
            val plain = HikEncryption.decrypt(unit.data, hevc, mode, current)
            storeParams(plain, unit.codec)
            return plain
        }
        if (type == 0 || params == null) return unit.data
        matchingKey(unit.data, hevc, type, annexB = true)?.let { (_, plain) ->
            Log.d(TAG, "stream terenkripsi; kunci #$keyIndex/${keyCandidates.size}")
            storeParams(plain, unit.codec)
            return plain
        }
        encryptedParamSeen = true
        if (!loggedNoKey) {
            loggedNoKey = true
            Log.d(TAG, "parameter set terenkripsi; menunggu kunci verifikasi")
        }
        rememberParams(unit.data, unit.codec, type)
        onEncryptedParam?.invoke(unit.data, hevc)
        return null
    }

    /** Cache semua parameter set jernih dari satu access unit Annex-B. */
    private fun storeParams(data: ByteArray, codec: String) {
        val hevc = codec == "h265"
        for (range in AnnexB.spans(data)) {
            if (range.first > range.last) continue
            storeParam(data.copyOfRange(range.first, range.last + 1), hevc)
        }
    }

    /** Simpan parameter set terenkripsi dari access unit Annex-B. */
    private fun rememberParams(data: ByteArray, codec: String, type: Int) {
        val hevc = codec == "h265"
        for (range in AnnexB.spans(data)) {
            if (range.first > range.last) continue
            rememberParam(data.copyOfRange(range.first, range.last + 1), hevc, type)
        }
    }

    /** Cari kandidat kunci yang membuat parameter set valid. */
    private fun matchingKey(
        data: ByteArray,
        hevc: Boolean,
        type: Int,
        annexB: Boolean
    ): Pair<ByteArray, ByteArray>? {
        val candidates = synchronized(keyCandidates) { keyCandidates.toList() }
        val mode = if (type != 0) type else AES_MODE
        for ((index, candidate) in candidates.withIndex()) {
            val attempt = if (annexB) {
                HikEncryption.decrypt(data, hevc, mode, candidate)
            } else {
                HikEncryption.decryptNal(data, hevc, mode, candidate)
            }
            // Jalur RTP memberi NAL tanpa start code, jadi tidak boleh diuji
            // dengan [AnnexB.paramsValid] yang mencari start code.
            val valid = if (annexB) {
                AnnexB.paramsValid(attempt, if (hevc) "h265" else "h264")
            } else {
                AnnexB.nalParamsValid(attempt, hevc)
            }
            if (valid == true) {
                chosenKey = candidate
                keyIndex = index
                return candidate to attempt
            }
        }
        return null
    }

    private class RawUnit(val codec: String, val pts: Long?, val data: ByteArray)

    // ---------------------------------------------------------------- RTP

    private inner class RtpDepay(codecHint: String?) {
        private var codec: String? = codecHint
        private var sniffed = codecHint != null
        private var timestamp: Long? = null
        private val accessUnit = java.io.ByteArrayOutputStream(8192)
        private var keyState = STATE_UNKNOWN
        private var loggedKey = false

        /**
         * Buffer fragmen yang bertahan lintas pergantian timestamp. Relay
         * cloud memberi timestamp berbeda hampir tiap paket, jadi NAL yang
         * terpecah tidak boleh dirakit di [accessUnit] yang ikut di-reset.
         */
        private val fragment = java.io.ByteArrayOutputStream(8192)
        private var fragmentEnc = 0
        private var lastSeq: Int? = null

        // Data seluler bisa menjatuhkan paket RTP. Jangan pernah meneruskan
        // access unit yang incomplete ke decoder: itu yang menimbulkan mozaik.
        private var discardAu = false
        private var awaitingKeyframe = false

        fun feed(
            ts: Long,
            seq: Int,
            marker: Boolean,
            payload: ByteArray,
            encryptType: Int
        ): List<RawUnit> {
            val out = ArrayList<RawUnit>(1)
            if (!sniffed) {
                val sniff = sniffCodec(payload)
                if (sniff != null) {
                    codec = sniff
                    sniffed = true
                }
            }
            val current = codec ?: return out
            if (timestamp != null && ts != timestamp && accessUnit.size() > 0) {
                flush(current, out)
            }
            timestamp = ts
            // Urutan paket putus: fragmen lama tidak mungkin dilanjutkan dan
            // access unit yang sedang berjalan pasti tidak lengkap.
            if (lastSeq != null && seq != ((lastSeq!! + 1) and 0xFFFF)) {
                fragment.reset()
                discardAu = true
                awaitingKeyframe = true
            }
            lastSeq = seq
            depay(payload, current == "h265", encryptType)
            if (marker && accessUnit.size() > 0) {
                flush(current, out)
            }
            return out
        }

        private fun flush(codec: String, out: MutableList<RawUnit>) {
            // NAL yang masih terfragmentasi berarti access unit terpotong.
            if (fragment.size() > 0) {
                discardAu = true
                awaitingKeyframe = true
            }
            val ts = timestamp ?: 0L
            if (!discardAu) {
                val data = accessUnit.toByteArray()
                if (!awaitingKeyframe || AnnexB.isKeyframe(data, codec)) {
                    out += RawUnit(codec, ts, data)
                    awaitingKeyframe = false
                }
            } else {
                // Access unit tidak lengkap; tunggu keyframe berikutnya.
            }
            accessUnit.reset()
            discardAu = false
        }

        /**
         * Tulis satu NAL utuh (tanpa start code) ke access unit.
         *
         * Relay cloud tidak selalu menyertakan extension `0x4000` pada paket
         * pembawa VPS/SPS walau isinya terenkripsi, jadi status enkripsi
         * diputuskan sekali dari parameter set. VPS jernih selalu sesuai
         * spesifikasi; VPS terenkripsi baru cocok setelah didekripsi dengan
         * kunci yang benar.
         */
        private fun emit(nal: ByteArray, packetType: Int, hevc: Boolean) {
            val type = if (hevc) {
                (nal[0].toInt() ushr 1) and 0x3F
            } else {
                nal[0].toInt() and 0x1F
            }
            val isParam = if (hevc) type == 32 else type == 7
            val plain: ByteArray
            when (keyState) {
                STATE_CLEAR -> plain = nal

                STATE_ENCRYPTED -> {
                    val current = chosenKey
                    if (current != null) {
                        val mode = if (packetType != 0) packetType else AES_MODE
                        // Paket tanpa extension 0x4000 benar-benar jernih, termasuk
                        // P-frame. Jangan enkripsi ulang dengan kunci yang sudah
                        // dipilih hanya karena parameter set awal terbukti terenkripsi.
                        val mustDecrypt = packetType != 0 ||
                            (isParam && AnnexB.nalParamsValid(nal, hevc) != true)
                        plain = if (mustDecrypt) {
                            HikEncryption.decryptNal(nal, hevc, mode, current)
                        } else {
                            nal
                        }
                    } else if (isParam) {
                        // Kunci bisa tiba setelah status enkripsi terdeteksi.
                        val match = matchingKey(nal, hevc, packetType, annexB = false)
                        if (match == null) {
                            rememberParam(nal, hevc, packetType)
                            requestKey(nal, hevc)
                            return
                        }
                        plain = match.second
                    } else {
                        // Terenkripsi tanpa kunci: jangan kirim data acak ke
                        // decoder, tunggu kode verifikasi pulih.
                        return
                    }
                }

                else -> {
                    if (isParam) {
                        if (AnnexB.nalParamsValid(nal, hevc) == true) {
                            keyState = STATE_CLEAR
                            if (!loggedKey) {
                                loggedKey = true
                                Log.d(TAG, "stream jernih (parameter set valid)")
                            }
                            plain = nal
                        } else {
                            val match = matchingKey(nal, hevc, packetType, annexB = false)
                            if (match != null) {
                                keyState = STATE_ENCRYPTED
                                if (!loggedKey) {
                                    loggedKey = true
                                    Log.d(TAG, "stream terenkripsi; kunci cocok")
                                }
                                plain = match.second
                            } else {
                                keyState = STATE_ENCRYPTED
                                rememberParam(nal, hevc, packetType)
                                requestKey(nal, hevc)
                                return
                            }
                        }
                    } else if (packetType != 0) {
                        val current = chosenKey ?: return
                        plain = HikEncryption.decryptNal(nal, hevc, packetType, current)
                    } else {
                        plain = nal
                    }
                }
            }
            accessUnit.write(AnnexB.START_CODE)
            accessUnit.write(plain)
            storeParam(plain, hevc)
        }

        /** Minta pemulihan kode sekali untuk parameter set terenkripsi ini. */
        private fun requestKey(nal: ByteArray, hevc: Boolean) {
            if (!encryptedParamSeen) {
                encryptedParamSeen = true
                Log.d(TAG, "parameter set terenkripsi; meminta pemulihan kode")
            }
            onEncryptedParam?.invoke(nal, hevc)
        }

        private fun depay(payload: ByteArray, hevc: Boolean, encryptType: Int) {
            val headerSize = if (hevc) 2 else 1
            if (payload.size <= headerSize) return
            val nalType = if (hevc) {
                (payload[0].toInt() ushr 1) and 0x3F
            } else {
                payload[0].toInt() and 0x1F
            }
            when (nalType) {
                if (hevc) 48 else 24 -> {
                    // Aggregation packet (STAP-A / AP).
                    var pos = headerSize
                    while (pos + 2 <= payload.size) {
                        val size = ((payload[pos].toInt() and 0xFF) shl 8) or
                            (payload[pos + 1].toInt() and 0xFF)
                        val end = minOf(pos + 2 + size, payload.size)
                        if (end > pos + 2) {
                            emit(payload.copyOfRange(pos + 2, end), encryptType, hevc)
                        }
                        pos += 2 + size
                    }
                }

                if (hevc) 49 else 28 -> {
                    // Fragmentation unit (FU-A / FU). Fragmen dirakit dulu,
                    // baru ditempel ke access unit saat bit END datang.
                    val fu = payload[headerSize].toInt() and 0xFF
                    val dataStart = headerSize + 1
                    if (fu and 0x80 != 0) {
                        fragment.reset()
                        if (hevc) {
                            fragment.write((payload[0].toInt() and 0x81) or ((fu and 0x3F) shl 1))
                            fragment.write(payload[1].toInt() and 0xFF)
                        } else {
                            fragment.write((payload[0].toInt() and 0xE0) or (fu and 0x1F))
                        }
                        fragmentEnc = encryptType
                    } else if (fragment.size() == 0) {
                        // Fragmen awal tidak pernah diterima; lewati sisa NAL.
                        return
                    }
                    fragment.write(payload, dataStart, payload.size - dataStart)
                    if (fu and 0x40 != 0 && fragment.size() > 0) {
                        emit(fragment.toByteArray(), fragmentEnc, hevc)
                        fragment.reset()
                    }
                }

                else -> emit(payload, encryptType, hevc)
            }
        }

        private fun sniffCodec(payload: ByteArray): String? {
            if (payload.size < 2 || payload[0].toInt() and 0x80 != 0) return null
            val first = payload[0].toInt() and 0xFF
            val hevcType = first ushr 1
            if (hevcType in 32..34 && payload[1] == 1.toByte()) return "h265"
            val avcType = first and 0x1F
            if (avcType == 7 || avcType == 24) return "h264"
            return null
        }
    }

    // ---------------------------------------------------------------- MPEG-PS

    private class PsDemux {
        private val buffer = java.io.ByteArrayOutputStream(16384)
        private val elementary = java.io.ByteArrayOutputStream(16384)
        private var pts: Long? = null
        private var codec: String? = null
        var encryptType: Int = 0
            private set
        var descriptorHex: String = ""
            private set

        fun feed(data: ByteArray): List<RawUnit> {
            val out = ArrayList<RawUnit>(1)
            buffer.write(data)
            val bytes = buffer.toByteArray()
            var pos = 0
            while (true) {
                val index = indexOf(bytes, PS_START, pos)
                if (index < 0) {
                    pos = maxOf(pos, bytes.size - 2)
                    break
                }
                pos = index
                if (index + 6 > bytes.size) break
                val streamId = bytes[index + 3].toInt() and 0xFF
                if (streamId < 0xBA) {
                    pos = index + 3
                    continue
                }
                var end = index + 6 + (((bytes[index + 4].toInt() and 0xFF) shl 8) or
                    (bytes[index + 5].toInt() and 0xFF))
                if (streamId == 0xBA) {
                    end = index + 14 + if (index + 14 <= bytes.size) {
                        bytes[index + 13].toInt() and 7
                    } else {
                        0
                    }
                }
                if (end > bytes.size) break
                val body = bytes.copyOfRange(index + 6, end)
                when {
                    streamId == 0xBA -> out += flush()
                    streamId == 0xBC -> parseMap(body)
                    streamId in 0xE0..0xEF && body.size > 3 -> {
                        if (body[1].toInt() and 0x80 != 0 && body.size >= 8 && pts == null) {
                            pts = ((body[3].toLong() ushr 1 and 7) shl 30) or
                                ((body[4].toLong() and 0xFF) shl 22) or
                                ((body[5].toLong() ushr 1) shl 15) or
                                ((body[6].toLong() and 0xFF) shl 7) or
                                (body[7].toLong() ushr 1)
                        }
                        val headerSize = 3 + (body[2].toInt() and 0xFF)
                        if (headerSize < body.size) {
                            elementary.write(body, headerSize, body.size - headerSize)
                        }
                    }
                }
                pos = end
            }
            val remaining = bytes.copyOfRange(minOf(pos, bytes.size), bytes.size)
            buffer.reset()
            buffer.write(remaining)
            return out
        }

        private fun parseMap(body: ByteArray) {
            if (body.size < 4) return
            val descriptorAt = indexOf(body, MAP_DESCRIPTOR, 0)
            val type = if (descriptorAt >= 0) {
                val descriptor = body.copyOfRange(
                    descriptorAt,
                    minOf(descriptorAt + 8, body.size)
                )
                descriptorHex = descriptor.joinToString("") { "%02x".format(it) }
                HikEncryption.encryptType(descriptor)
            } else {
                0
            }
            var found = codec
            var pos = 6 + (((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF))
            while (pos + 4 <= body.size - 4) {
                val streamType = body[pos].toInt() and 0xFF
                if (streamType == 0x1B) {
                    found = "h264"
                    break
                }
                if (streamType == 0x24) {
                    found = "h265"
                    break
                }
                pos += 4 + (((body[pos + 2].toInt() and 0xFF) shl 8) or
                    (body[pos + 3].toInt() and 0xFF))
            }
            encryptType = type
            codec = found
        }

        private fun flush(): List<RawUnit> {
            val out = ArrayList<RawUnit>(1)
            val current = codec
            if (elementary.size() > 0 && current != null) {
                out += RawUnit(current, pts, elementary.toByteArray())
            }
            elementary.reset()
            pts = null
            return out
        }
    }

    companion object {
        private const val TAG = "CloudDemux"
        private const val AES_MODE = 2
        private const val STATE_UNKNOWN = 0
        private const val STATE_CLEAR = 1
        private const val STATE_ENCRYPTED = 2
        private const val RTP_VIDEO_PAYLOAD_TYPE = 96
        private val PS_START = byteArrayOf(0, 0, 1)
        private val MAP_DESCRIPTOR = byteArrayOf(0x80.toByte(), 6, 0, 1)

        private fun parseStreamHead(streamHead: ByteArray): Pair<String?, String?> {
            val head = try {
                Base64.decode(String(streamHead, Charsets.US_ASCII).trim(), Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
            if (head == null || head.size < 12 || head[0] != 'I'.code.toByte() ||
                head[1] != 'M'.code.toByte() || head[2] != 'K'.code.toByte() ||
                head[3] != 'H'.code.toByte()
            ) {
                return null to null
            }
            val system = (head[8].toInt() and 0xFF) or ((head[9].toInt() and 0xFF) shl 8)
            val video = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
            return when (system) {
                2 -> "ps"
                4 -> "rtp"
                else -> null
            } to when (video) {
                1, 0x100 -> "h264"
                5 -> "h265"
                else -> null
            }
        }

        private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
            var i = maxOf(0, from)
            outer@ while (i + needle.size <= data.size) {
                for (j in needle.indices) {
                    if (data[i + j] != needle[j]) {
                        i++
                        continue@outer
                    }
                }
                return i
            }
            return -1
        }
    }
}
