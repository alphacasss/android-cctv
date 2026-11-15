package id.cctv.monitor.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

class IsapiException(
    message: String,
    val code: Int? = null,
    cause: Throwable? = null
) : IOException(message, cause)

data class IsapiResponse(val code: Int, val body: String)

/**
 * Klien ISAPI minimal: GET/PUT ke kamera lewat HTTP dengan Digest auth.
 * Dibuat mengikuti perilaku CCTV-Control.ps1 di PC.
 */
class IsapiClient(
    private val baseUrl: String,
    private val user: String,
    private val pass: String
) {

    fun get(path: String): IsapiResponse = request("GET", path, null)

    fun put(path: String, body: String): IsapiResponse = request("PUT", path, body)

    private fun request(method: String, path: String, body: String?): IsapiResponse {
        var auth: String? = null
        var attempt = 0
        while (attempt < 2) {
            attempt++
            val conn = try {
                URL(baseUrl + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            } catch (e: Exception) {
                throw IsapiException("Alamat kamera tidak valid: $baseUrl$path", null, e)
            }
            try {
                conn.requestMethod = method
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "CCTV-Monitor-Android/1.0")
                conn.setRequestProperty("Accept", "*/*")
                auth?.let { conn.setRequestProperty("Authorization", it) }

                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/xml; charset=\"UTF-8\"")
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (code == 401 && auth == null) {
                    val challenge = conn.getHeaderField("WWW-Authenticate")
                    if (challenge.isNullOrBlank()) {
                        throw IsapiException("Kamera menolak (401) tanpa memberi skema autentikasi.", 401)
                    }
                    auth = try {
                        DigestAuth.authorization(challenge, method, path, user, pass)
                    } catch (e: Exception) {
                        throw IsapiException("Skema autentikasi kamera tidak dikenal.", 401, e)
                    }
                    continue
                }
                if (code == 401) {
                    throw IsapiException(
                        "Autentikasi kamera gagal (HTTP 401). Periksa user/password kamera. " +
                            "Hikvision bisa mengunci akun sementara bila login gagal berulang kali, " +
                            "jadi jangan dicoba berkali-kali.",
                        401
                    )
                }
                return IsapiResponse(code, text)
            } catch (e: IsapiException) {
                throw e
            } catch (e: Exception) {
                throw IsapiException(
                    "Tidak bisa menghubungi kamera: ${e.message ?: e.javaClass.simpleName}",
                    null,
                    e
                )
            } finally {
                conn.disconnect()
            }
        }
        throw IsapiException("Autentikasi kamera gagal (401). Periksa user/password.", 401)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
    }
}

/** Pesan error ISAPI dalam bahasa Indonesia, setara Get-IsapiError di PC. */
fun isapiError(response: IsapiResponse): String {
    when (response.code) {
        401 -> return "Autentikasi kamera gagal (HTTP 401). Periksa user/password kamera."
        403 -> return "Kamera menolak (HTTP 403). Akun ini tidak punya izin konfigurasi (ISAPI)."
    }
    val status = Regex("<statusString>(.*?)</statusString>", RegexOption.DOT_MATCHES_ALL)
        .find(response.body)?.groupValues?.get(1)
    val detail = status ?: response.body.take(160)
    return "Kamera menolak: HTTP ${response.code}" + if (detail.isNotBlank()) " - $detail" else ""
}
