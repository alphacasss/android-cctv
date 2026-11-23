package id.cctv.monitor.cloud.stream

/**
 * ChaCha20 (RFC 7539) tanpa padding, dipakai sebagai lapisan enkripsi
 * amplop ECDH pada relay stream Hik-Connect.
 *
 * Implementasi manual karena `Cipher("ChaCha20")` baru tersedia mulai
 * Android 9 (API 28), sedangkan aplikasi ini mendukung Android 7 (API 24).
 * Nonce 96-bit diisi nomor urut paket; counter selalu mulai dari 0.
 */
internal object ChaCha20 {

    private val CONSTANTS = intArrayOf(
        0x61707865, 0x3320646e, 0x79622d32, 0x6b206574
    )

    /** XOR [data] dengan keystream ChaCha20 untuk nomor urut [sequence]. */
    fun xor(key: ByteArray, sequence: Int, data: ByteArray): ByteArray {
        require(key.size == 32) { "Kunci ChaCha20 harus 32 byte." }
        val state = IntArray(16)
        System.arraycopy(CONSTANTS, 0, state, 0, 4)
        for (i in 0 until 8) state[4 + i] = littleEndian(key, i * 4)
        state[12] = 0
        state[13] = sequence
        state[14] = 0
        state[15] = 0

        val out = ByteArray(data.size)
        val block = ByteArray(64)
        var offset = 0
        while (offset < data.size) {
            val working = state.copyOf()
            repeat(10) {
                quarter(working, 0, 4, 8, 12)
                quarter(working, 1, 5, 9, 13)
                quarter(working, 2, 6, 10, 14)
                quarter(working, 3, 7, 11, 15)
                quarter(working, 0, 5, 10, 15)
                quarter(working, 1, 6, 11, 12)
                quarter(working, 2, 7, 8, 13)
                quarter(working, 3, 4, 9, 14)
            }
            for (i in 0 until 16) {
                val value = working[i] + state[i]
                block[i * 4] = value.toByte()
                block[i * 4 + 1] = (value ushr 8).toByte()
                block[i * 4 + 2] = (value ushr 16).toByte()
                block[i * 4 + 3] = (value ushr 24).toByte()
            }
            val count = minOf(64, data.size - offset)
            for (i in 0 until count) {
                out[offset + i] = (data[offset + i].toInt() xor block[i].toInt()).toByte()
            }
            state[12]++
            offset += 64
        }
        return out
    }

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    private fun littleEndian(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
