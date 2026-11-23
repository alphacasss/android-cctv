package id.cctv.monitor.cloud

/**
 * Parameter yang dibutuhkan klien P2P `ysproto` untuk membuka live stream
 * cloud Hik-Connect: server VTM, kunci publik relay, token sekali pakai, dan
 * identitas kanal perangkat.
 *
 * Objek ini hanya berisi data sesi sementara (token kedaluwarsa ± beberapa
 * menit) dan tidak disimpan ke preferensi.
 */
data class CloudStreamSource(
    val vtmHost: String,
    val vtmPort: Int,
    /** Kunci publik relay (DER, siap dipakai ECDH). Null = tanpa enkripsi. */
    val publicKey: ByteArray?,
    val keyVersion: Int,
    val serial: String,
    val channel: Int,
    /** 1 = main stream, 2 = sub stream. */
    val streamType: Int,
    /** Potongan query tambahan dari `streamBizUrl` (mis. `biz=1`). */
    val bizQuery: String,
    val tokens: List<String>,
    /** True bila video perangkat dienkripsi (butuh kunci AES). */
    val encrypted: Boolean,
    val verificationCode: String,
    /** `encryptPwd` cloud (32 hex) — kunci enkripsi yang diberikan akun. */
    val encryptionPwd: String = ""
) {
    /**
     * Kandidat kunci AES (32 byte) untuk video terenkripsi, urut prioritas.
     * Pemilihan akhir dilakukan [id.cctv.monitor.cloud.stream.StreamDemuxer]
     * dengan menguji parameter set hasil dekripsi, karena firmware berbeda
     * memakai kode verifikasi atau `encryptPwd` cloud.
     */
    fun encryptionKeys(): List<ByteArray> {
        if (!encrypted) return emptyList()
        val keys = ArrayList<ByteArray>(4)
        // Kode dipakai apa adanya; sebagian perangkat membedakan huruf besar
        // dan kecil. Varian lain tetap disertakan agar firmware lama cocok.
        val code = verificationCode.trim()
        if (code.isNotEmpty()) {
            keys += padded(code.toByteArray(Charsets.UTF_8))
            val upper = code.uppercase()
            if (upper != code) keys += padded(upper.toByteArray(Charsets.UTF_8))
            val lower = code.lowercase()
            if (lower != code) keys += padded(lower.toByteArray(Charsets.UTF_8))
        }
        val pwd = encryptionPwd.trim()
        if (pwd.length == 32 && pwd.all { it.isHexDigit() }) {
            val raw = hex(pwd)
            if (raw != null) keys += padded(raw)
            keys += padded(pwd.uppercase().toByteArray(Charsets.UTF_8))
        }
        return keys.distinctBy { it.joinToString("") { b -> "%02x".format(b) } }
    }

    private fun padded(raw: ByteArray): ByteArray {
        val key = ByteArray(32)
        raw.copyInto(key, endIndex = minOf(raw.size, key.size))
        return key
    }

    private fun hex(value: String): ByteArray? = try {
        ByteArray(value.length / 2) { i ->
            value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    } catch (_: NumberFormatException) {
        null
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
