package id.cctv.monitor.cloud.stream

/**
 * Dekripsi video "Image and Video Encryption" Hikvision.
 *
 * Perangkat boleh memakai beberapa varian:
 *  - mode 1: AES-128 dipotong menjadi 3 ronde (tidak disediakan library),
 *  - mode 2: AES-128-ECB biasa,
 *  - mode 3: AES-256-ECB,
 *  - 0x11..0x13: varian yang sama, tiap NAL diawali field panjang.
 *
 * Key adalah kode verifikasi perangkat (maks. 16 byte, di-pad nol sampai 32
 * byte). Seluruh isi NAL terenkripsi di-dekripsi; beberapa perangkat memasang
 * batasan blok, tetapi pola umum (dan perangkat ini) mengenkripsi seluruh NAL,
 * bukan hanya blok awal.
 */
internal object HikEncryption {

    /**
     * Tipe enkripsi dari descriptor PS/RTP `80 06 00 01 b4 b5 b6 01`.
     * 0 = tidak terenkripsi.
     */
    fun encryptType(descriptor: ByteArray): Int {
        if (descriptor.size < 8 || (descriptor[1].toInt() and 0xFF) < 6) return 0
        val algorithm = descriptor[4].toInt() and 0x0F
        val keyLength = descriptor[5].toInt() and 0x0F
        var mode = (descriptor[5].toInt() and 0xFF) ushr 4
        if (algorithm == 1 && keyLength == 3 && mode in 2..3) {
            mode = 3
        } else if (mode != 1 && mode != 2) {
            return 0
        }
        return mode + if ((descriptor[4].toInt() and 0xFF ushr 4) == 3) 0x10 else 0
    }

    /** Dekripsi satu access unit Annex-B; mengembalikan data apa adanya bila tidak terenkripsi. */
    fun decrypt(data: ByteArray, hevc: Boolean, encryptType: Int, key: ByteArray): ByteArray {
        if (encryptType == 0 || key.isEmpty()) return data
        return if (encryptType > 0x10) {
            decryptExtendedLength(data, hevc, encryptType, key)
        } else {
            val buffer = data.copyOf()
            for (range in AnnexB.spans(data)) {
                val start = range.first + if (hevc) 2 else 0
                val end = range.last + 1
                if (start + 16 <= end) {
                    val length = if (encryptType == 1) 16 else end - start
                    decryptBlocks(buffer, start, length, encryptType, key)
                }
            }
            buffer
        }
    }

    /** Dekripsi satu NAL unit tanpa start code (jalur RTP, enkripsi per paket).
     *
     * H.265: dua byte header NAL dipertahankan, isi didekripsi mulai byte 2.
     * H.264: byte header jernih dibuang karena salinan header asli ada di awal
     * blok terenkripsi. Seluruh isi NAL didekripsi; sisa yang bukan kelipatan
     * 16 byte dibiarkan apa adanya.
     */
    fun decryptNal(nal: ByteArray, hevc: Boolean, encryptType: Int, key: ByteArray): ByteArray {
        if (encryptType == 0 || key.isEmpty()) return nal
        if (encryptType > 0x10) {
            return decrypt(AnnexB.START_CODE + nal, hevc, encryptType, key)
        }
        val header = if (hevc) 2 else 1
        if (nal.size <= header) return nal
        val blocks = (nal.size - header) / 16 * 16
        if (blocks <= 0) return nal
        return if (hevc) {
            val buffer = nal.copyOf()
            decryptBlocks(buffer, header, blocks, encryptType, key)
            buffer
        } else {
            val body = nal.copyOfRange(header, header + blocks)
            decryptBlocks(body, 0, blocks, encryptType, key)
            val tail = nal.size - header - blocks
            val out = ByteArray(blocks + tail)
            System.arraycopy(body, 0, out, 0, blocks)
            System.arraycopy(nal, header + blocks, out, blocks, tail)
            out
        }
    }

    private fun decryptExtendedLength(
        data: ByteArray,
        hevc: Boolean,
        encryptType: Int,
        key: ByteArray
    ): ByteArray {
        val buffer = data.copyOf()
        val result = java.io.ByteArrayOutputStream(data.size)
        var pos = 0
        while (pos + 3 <= buffer.size &&
            (buffer[pos] == 0.toByte() && buffer[pos + 1] == 0.toByte())
        ) {
            val headerSize = if (buffer[pos + 2] == 0.toByte()) 4 else 3
            val head = pos + headerSize + if (hevc) 2 else 0
            if (head >= buffer.size) break
            val lengthSize = 1 + ((buffer[head].toInt() and 0xFF) ushr 6)
            var length = 0
            for (i in 0 until lengthSize) {
                length = (length shl 8) or (buffer[head + i].toInt() and 0xFF)
            }
            length = length and ((1 shl (8 * lengthSize - 2)) - 1)
            val payload = head + lengthSize + 1
            val searchFrom = maxOf(pos + 3, minOf(head + length, buffer.size))
            val end = nextStartCode(buffer, searchFrom)
            decryptBlocks(
                buffer,
                payload,
                if (encryptType == 0x11) 16 else length,
                encryptType,
                key
            )
            result.write(buffer, pos, head - pos)
            result.write(buffer, payload, (end - payload).coerceAtLeast(0))
            pos = end
        }
        if (pos < buffer.size) result.write(buffer, pos, buffer.size - pos)
        return result.toByteArray()
    }

    private fun decryptBlocks(
        buffer: ByteArray,
        position: Int,
        length: Int,
        encryptType: Int,
        key: ByteArray
    ) {
        val limit = minOf(length, buffer.size - position)
        val end = position + (limit / 16) * 16
        if (end <= position) return
        if (encryptType and 0x0F == 1) {
            val roundKeys = roundKeys(key.copyOfRange(0, 16), 3)
            val block = ByteArray(16)
            var i = position
            while (i < end) {
                decryptBlock3Rounds(buffer, i, roundKeys, block)
                System.arraycopy(block, 0, buffer, i, 16)
                i += 16
            }
            return
        }
        val keySize = if (encryptType and 0x0F == 3) 32 else 16
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key.copyOfRange(0, keySize), "AES")
        )
        val plain = cipher.doFinal(buffer, position, end - position)
        System.arraycopy(plain, 0, buffer, position, plain.size)
    }

    private fun nextStartCode(data: ByteArray, from: Int): Int {
        var i = maxOf(0, from)
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {
                return if (i > from && data[i - 1] == 0.toByte()) i - 1 else i
            }
            i++
        }
        return data.size
    }

    // ---------------- AES-128 dengan 3 ronde ----------------

    private val sbox = IntArray(256)
    private val invSbox = IntArray(256)
    private val invMix = HashMap<Int, IntArray>()
    private val rcon = intArrayOf(
        0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36
    )

    init {
        for (x in 0 until 256) {
            val inverse = (1 until 256).firstOrNull { gmul(x, it) == 1 } ?: 0
            var value = inverse
            for (i in 1..4) {
                value = value xor (((inverse shl i) or (inverse ushr (8 - i))) and 0xFF)
            }
            sbox[x] = value xor 0x63
        }
        for (x in 0 until 256) invSbox[sbox[x]] = x
        for (m in intArrayOf(9, 11, 13, 14)) {
            invMix[m] = IntArray(256) { gmul(it, m) }
        }
    }

    private fun gmul(a: Int, b: Int): Int {
        var x = a
        var y = b
        var result = 0
        while (y != 0) {
            if (y and 1 != 0) result = result xor x
            x = ((x shl 1) xor (if (x and 0x80 != 0) 0x1B else 0)) and 0xFF
            y = y ushr 1
        }
        return result and 0xFF
    }

    private fun roundKeys(key: ByteArray, rounds: Int): Array<IntArray> {
        val words = Array(4 * (rounds + 1)) { IntArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) words[i][j] = key[4 * i + j].toInt() and 0xFF
        }
        for (i in 4 until 4 * (rounds + 1)) {
            var word = words[i - 1].copyOf()
            if (i % 4 == 0) {
                val rc = rcon[i / 4 - 1]
                word = intArrayOf(
                    sbox[word[1]] xor rc,
                    sbox[word[2]],
                    sbox[word[3]],
                    sbox[word[0]]
                )
            }
            for (j in 0 until 4) words[i][j] = words[i - 4][j] xor word[j]
        }
        return Array(rounds + 1) { r ->
            IntArray(16) { index -> words[4 * r + index / 4][index % 4] }
        }
    }

    private fun decryptBlock3Rounds(
        source: ByteArray,
        offset: Int,
        keys: Array<IntArray>,
        out: ByteArray
    ) {
        val state = IntArray(16)
        val last = keys[keys.size - 1]
        for (i in 0 until 16) {
            state[i] = (source[offset + i].toInt() and 0xFF) xor last[i]
        }
        for (r in keys.size - 2 downTo 0) {
            val shifted = IntArray(16)
            for (col in 0 until 4) {
                for (row in 0 until 4) {
                    val sourceIndex = 4 * ((col - row + 4) % 4) + row
                    shifted[4 * col + row] = invSbox[state[sourceIndex]]
                }
            }
            for (i in 0 until 16) state[i] = shifted[i] xor keys[r][i]
            if (r != 0) {
                val m9 = invMix.getValue(9)
                val m11 = invMix.getValue(11)
                val m13 = invMix.getValue(13)
                val m14 = invMix.getValue(14)
                val mixed = IntArray(16)
                for (col in 0 until 4) {
                    val a0 = state[4 * col]
                    val a1 = state[4 * col + 1]
                    val a2 = state[4 * col + 2]
                    val a3 = state[4 * col + 3]
                    mixed[4 * col] = m14[a0] xor m11[a1] xor m13[a2] xor m9[a3]
                    mixed[4 * col + 1] = m9[a0] xor m14[a1] xor m11[a2] xor m13[a3]
                    mixed[4 * col + 2] = m13[a0] xor m9[a1] xor m14[a2] xor m11[a3]
                    mixed[4 * col + 3] = m11[a0] xor m13[a1] xor m9[a2] xor m14[a3]
                }
                System.arraycopy(mixed, 0, state, 0, 16)
            }
        }
        for (i in 0 until 16) out[i] = state[i].toByte()
    }
}
