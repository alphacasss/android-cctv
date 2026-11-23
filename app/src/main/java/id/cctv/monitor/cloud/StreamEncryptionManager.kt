package id.cctv.monitor.cloud

import android.util.Log
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.IsapiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mematikan "stream encryption" pada NVR melalui ISAPI lokal.
 *
 * Enkripsi stream membuat relay cloud mengirim bitstream yang tidak bisa
 * didekode aplikasi pihak ketiga (hijau/corrupt). Aplikasi Hik-Connect resmi
 * memakai SDK native berlisensi untuk membukanya; dengan mematikan opsi ini
 * sekali saat ponsel berada di jaringan rumah, semua kanal menjadi jernih dan
 * bisa diputar dari jaringan mana pun tanpa SDK tersebut.
 */
object StreamEncryptionManager {

    private const val TAG = "StreamEncryption"
    private const val PATH = "/ISAPI/System/Network/EZVIZ"
    private const val INTERVAL_MS = 60L * 60 * 1000
    private const val FAIL_RETRY_MS = 10L * 60 * 1000

    suspend fun disableIfOnLan(prefs: AppPrefs) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = prefs.lastStreamEncryptionDisableAt
        Log.d(TAG, "check now=$now last=$last")
        if (now - last < INTERVAL_MS) return@withContext
        val cameras = prefs.cameras()
        val nvr = cameras.firstOrNull { it.isNvr } ?: cameras.firstOrNull() ?: return@withContext
        val host = nvr.ip.trim()
        val local = host.isNotBlank() && EndpointResolver.isLocalSubnet(host)
        Log.d(TAG, "target host=$host local=$local")
        if (!local) return@withContext
        val user = prefs.effectiveUser(nvr)
        val pass = prefs.effectivePass(nvr)
        if (user.isBlank() || pass.isBlank()) return@withContext

        fun markFailure() {
            // Tandai sehingga retry berikutnya paling cepat 10 menit lagi.
            prefs.lastStreamEncryptionDisableAt = now - INTERVAL_MS + FAIL_RETRY_MS
        }

        try {
            val client = IsapiClient("http://$host", user, pass)
            val current = client.get(PATH)
            if (current.code !in 200..299) {
                Log.w(TAG, "ISAPI EZVIZ HTTP ${current.code}")
                markFailure()
                return@withContext
            }
            val tag = Regex(
                "<streamEncrypteEnabled>\\s*true\\s*</streamEncrypteEnabled>",
                RegexOption.IGNORE_CASE
            )
            if (!tag.containsMatchIn(current.body)) {
                Log.d(TAG, "Enkripsi stream sudah nonaktif di $host")
                prefs.lastStreamEncryptionDisableAt = now
                return@withContext
            }
            val updated = current.body.replace(tag, "<streamEncrypteEnabled>false</streamEncrypteEnabled>")
            val put = client.put(PATH, updated)
            if (put.code in 200..299) {
                prefs.lastStreamEncryptionDisableAt = now
                Log.d(TAG, "Enkripsi stream dimatikan di $host")
            } else {
                Log.w(TAG, "Gagal mematikan enkripsi stream: HTTP ${put.code}")
                markFailure()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ISAPI enkripsi stream gagal: ${e.message}")
            markFailure()
        }
    }
}
