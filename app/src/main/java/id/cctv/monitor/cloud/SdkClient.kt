package id.cctv.monitor.cloud

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Klien HTTP ringan untuk API cloud Hik-Connect (tanpa OkHttp supaya tidak
 * menambah dependensi). Semua request memakai header yang dipakai aplikasi
 * resmi: `clientType`, `lang`, `featureCode`, dan `sessionId`.
 */
class SdkClient(apiDomain: String = CloudAuthManager.DEFAULT_DOMAIN) {

    var apiDomain: String = apiDomain
        private set

    private var sessionId: String? = null

    fun switchDomain(domain: String) {
        val clean = domain.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
        if (clean.isNotBlank()) apiDomain = clean
    }

    fun setSessionId(id: String?) {
        sessionId = id?.takeIf { it.isNotBlank() }
    }

    fun postForm(path: String, body: String): JSONObject =
        execute("POST", path, body, keepSession = true)

    fun putForm(path: String, body: String): JSONObject =
        execute("PUT", path, body, keepSession = true)

    /** PUT tanpa header `sessionId` (dipakai refresh token). */
    fun putFormNoSession(path: String, body: String): JSONObject =
        execute("PUT", path, body, keepSession = false)

    fun getJson(path: String): JSONObject = execute("GET", path, null, keepSession = true)

    /** PUT tanpa body (mis. trigger capture snapshot cloud). */
    fun putEmpty(path: String): JSONObject = execute("PUT", path, null, keepSession = true)

    /** Unduh gambar/stream dari URL absolut tanpa mengirim session cloud. */
    fun getBytes(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) {
            Log.w(TAG, "GET gambar gagal: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun execute(
        method: String,
        path: String,
        body: String?,
        keepSession: Boolean
    ): JSONObject {
        val url = "https://$apiDomain${path.ensureLeadingSlash()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("clientType", "55")
            setRequestProperty("lang", "en-US")
            setRequestProperty("featureCode", FEATURE_CODE)
            setRequestProperty("Accept", "application/json")
            if (keepSession) sessionId?.let { setRequestProperty("sessionId", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            throw IOException("Tidak bisa menghubungi cloud Hik-Connect: ${e.message}", e)
        }
        val text = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } catch (_: Exception) {
            ""
        } finally {
            conn.disconnect()
        }
        if (text.isBlank()) {
            throw CloudApiException(code, "Cloud mengembalikan respons kosong (HTTP $code).")
        }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw CloudApiException(code, "Respons cloud tidak bisa dibaca (HTTP $code).")
        }
        if (code !in 200..299) {
            throw CloudApiException(code, metaMessage(json) ?: "Cloud menolak permintaan (HTTP $code).")
        }
        return json
    }

    private fun metaMessage(json: JSONObject): String? {
        val meta = json.optJSONObject("meta") ?: return null
        val message = meta.optString("message", "").trim()
        val code = meta.optInt("code", 0)
        return "kode $code${if (message.isNotBlank()) " — $message" else ""}"
    }

    companion object {
        private const val TAG = "SdkClient"
        private const val USER_AGENT = "Hik-Connect-Android/5.0 (android)"
        private const val FEATURE_CODE = "deadbeef"

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun String.ensureLeadingSlash(): String =
            if (startsWith("/")) this else "/$this"
    }
}

/** Kesalahan dari API cloud (HTTP atau `meta.code`). */
class CloudApiException(
    val httpCode: Int,
    message: String,
    val metaCode: Int = 0
) : IOException(message)
