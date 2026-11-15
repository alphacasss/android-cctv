package id.cctv.monitor.net

import java.security.MessageDigest
import java.util.UUID

/** Digest auth (MD5 / qop=auth) untuk ISAPI Hikvision, plus fallback Basic. */
object DigestAuth {

    fun authorization(
        challenge: String,
        method: String,
        path: String,
        user: String,
        pass: String
    ): String {
        if (!challenge.contains("Digest", ignoreCase = true)) {
            if (challenge.contains("Basic", ignoreCase = true)) {
                val raw = "$user:$pass".toByteArray(Charsets.UTF_8)
                return "Basic " + android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
            }
            throw IllegalArgumentException("Skema autentikasi kamera tidak dikenal.")
        }

        val params = HashMap<String, String>()
        Regex("(\\w+)\\s*=\\s*\"([^\"]*)\"").findAll(challenge).forEach {
            params[it.groupValues[1].lowercase()] = it.groupValues[2]
        }
        Regex("""(\w+)\s*=\s*([^,\s"]+)""").findAll(challenge).forEach {
            val key = it.groupValues[1].lowercase()
            if (!params.containsKey(key)) params[key] = it.groupValues[2]
        }

        val realm = params["realm"] ?: ""
        val nonce = params["nonce"] ?: ""
        val opaque = params["opaque"]
        val qopOffered = params["qop"]?.split(",")?.any { it.trim().equals("auth", true) } == true

        val nc = "00000001"
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val ha1 = md5Hex("$user:$realm:$pass")
        val ha2 = md5Hex("$method:$path")

        val header = StringBuilder()
            .append("Digest username=\"").append(user)
            .append("\", realm=\"").append(realm)
            .append("\", nonce=\"").append(nonce)
            .append("\", uri=\"").append(path)
            .append("\", algorithm=MD5")

        if (qopOffered) {
            val response = md5Hex("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
            header.append(", qop=auth, nc=").append(nc)
                .append(", cnonce=\"").append(cnonce)
                .append("\", response=\"").append(response).append("\"")
        } else {
            val response = md5Hex("$ha1:$nonce:$ha2")
            header.append(", response=\"").append(response).append("\"")
        }
        if (!opaque.isNullOrBlank()) {
            header.append(", opaque=\"").append(opaque).append("\"")
        }
        return header.toString()
    }

    fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        bytes.forEach { sb.append("%02x".format(it.toInt() and 0xFF)) }
        return sb.toString()
    }
}
