package id.cctv.monitor.cloud

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pembaca kode verifikasi perangkat dari respons ISAPI.
 *
 * Endpoint `/ISAPI/System/Network/EZVIZ` mengembalikan XML (atau JSON bila
 * `?format=json`) dengan node `secretKey`. Nilai ini sama dengan "Stream
 * Encryption" pada halaman Platform Access dan dipakai untuk membuka stream
 * serta snapshot terenkripsi. Beberapa firmware memakai nama field berbeda,
 * jadi semua varian umum diterima.
 */
internal object HikCodeParser {

    private val KEYS = setOf(
        "secretkey",
        "verificationcode",
        "encryptkey",
        "securitycode"
    )

    private val PAIRS = listOf(
        Regex(
            "(?i)\"?\\b(secretKey|verificationCode|encryptKey|securityCode)\"?\\s*[:=]\\s*\"?([A-Za-z0-9]{4,32})"
        ),
        Regex(
            "(?i)<(secretKey|verificationCode|encryptKey|securityCode)>\\s*([A-Za-z0-9]{4,32})\\s*</"
        )
    )

    fun extract(text: String): String {
        if (text.isBlank()) return ""
        for (pattern in PAIRS) {
            val match = pattern.find(text) ?: continue
            val code = normalize(match.groupValues[2])
            if (code.isNotBlank()) return code
        }
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return ""
        return find(json)
    }

    private fun find(obj: JSONObject): String {
        val names = obj.keys()
        while (names.hasNext()) {
            val name = names.next()
            val value = obj.opt(name)
            if (name.lowercase() in KEYS) {
                val code = normalize(value as? String)
                if (code.isNotBlank()) return code
            }
            when (value) {
                is JSONObject -> find(value).takeIf { it.isNotBlank() }?.let { return it }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.optJSONObject(i) ?: continue
                        find(item).takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return ""
    }

    private fun normalize(raw: String?): String {
        val code = raw?.trim().orEmpty()
        if (code.length < 4 || code.length > 32) return ""
        // Cloud menutupi nilai rahasia dengan bintang; jangan pernah dipakai.
        if (!code.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }) return ""
        if (code.lowercase() in NON_CODES) return ""
        // Huruf besar/kecil dipertahankan: kunci AES memakai kode apa adanya.
        return code
    }

    private val NON_CODES = setOf("null", "true", "false", "none")
}
