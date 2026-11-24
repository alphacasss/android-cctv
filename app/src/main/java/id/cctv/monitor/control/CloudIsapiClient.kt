package id.cctv.monitor.control

import android.util.Log
import id.cctv.monitor.cloud.CloudAuthManager
import id.cctv.monitor.cloud.HikVisionCloudRepository
import id.cctv.monitor.net.IsapiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Klien ISAPI yang mentransfer perintah GET/PUT ke NVR lewat passthrough
 * Hik-Connect cloud. Rute ini dipakai untuk privacy mask karena hanya jalur
 * inilah yang terbukti diterapkan lintas perangkat pada NVR yang diuji.
 *
 * Kanal/stream/RTSP tetap memakai ISAPI langsung. Cloud passthrough hanya
 * dipakai sebagai fallback untuk NVR, karena firmware menerima PUT tetapi
 * sering tidak mengubah status kanal.
 */
class CloudIsapiClient(
    private val cloudRepo: HikVisionCloudRepository,
    private val authManager: CloudAuthManager,
    private val device: id.cctv.monitor.cloud.CloudDevice
) {

    /** Sesi dibaca saat pemanggilan, bukan di-cache, agar refresh token tetap terpakai. */
    private val session get() = authManager.session.value

    /** GET lewat cloud ISAPI passthrough. */
    suspend fun get(path: String): IsapiResponse = withContext(Dispatchers.IO) {
        val current = session
            ?: throw IllegalStateException("Sesi cloud tidak tersedia.")
        val body = cloudRepo.isapiCommand(current, device, "GET", path, null)
        wrap("GET", path, body)
    }

    /** PUT lewat cloud ISAPI passthrough. */
    suspend fun put(path: String, body: String): IsapiResponse = withContext(Dispatchers.IO) {
        val current = session
            ?: throw IllegalStateException("Sesi cloud tidak tersedia.")
        val responseBody = cloudRepo.isapiCommand(current, device, "PUT", path, asXmlDocument(body))
        wrap("PUT", path, responseBody)
    }

    /** POST lewat cloud ISAPI passthrough, dipakai pencarian rekaman. */
    suspend fun post(path: String, body: String): IsapiResponse = withContext(Dispatchers.IO) {
        val current = session
            ?: throw IllegalStateException("Sesi cloud tidak tersedia.")
        val responseBody = cloudRepo.isapiCommand(current, device, "POST", path, asXmlDocument(body))
        wrap("POST", path, responseBody)
    }

    /**
     * Passthrough cloud memotong deklarasi XML dari respons GET, jadi backup
     * yang disimpan aplikasi bisa berisi elemen root tanpa deklarasi. Firmware
     * NVR menolak PUT tersebut (`Invalid XML Format / invalid start tag`).
     * Tambahkan deklarasi dan buang BOM sebelum diteruskan ke perangkat.
     */
    private fun asXmlDocument(body: String): String {
        val clean = body.removePrefix("\uFEFF").trimStart()
        if (!clean.startsWith("<") || clean.startsWith("<?xml", ignoreCase = true)) return clean
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n$clean"
    }

    /**
     * Passthrough cloud mengembalikan envelope JSON dengan `statusCode` ISAPI.
     * Jangan selalu mengembalikan HTTP 200: kalau statusCode != 1, pemanggil
     * harus tahu bahwa perangkat menolak perintah.
     */
    private fun wrap(method: String, path: String, body: String): IsapiResponse {
        val status = XML_STATUS.find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: JSON_STATUS.find(body)?.groupValues?.get(1)?.toIntOrNull()
        val ok = status == null || status == 1
        if (!ok) {
            Log.w(TAG, "$method $path ditolak perangkat (statusCode=$status): " +
                body.take(240).replace(Regex("\\s+"), " "))
        }
        return IsapiResponse(if (ok) 200 else 400, body)
    }

    companion object {
        private const val TAG = "CloudIsapiClient"
        private val XML_STATUS = Regex("<statusCode>\\s*(\\d+)\\s*</statusCode>", RegexOption.IGNORE_CASE)
        private val JSON_STATUS = Regex("\"statusCode\"\\s*:\\s*\"?(\\d+)\"?")
    }
}
