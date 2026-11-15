package id.cctv.monitor.net

import android.os.Handler
import android.os.Looper
import id.cctv.monitor.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mendeteksi IP publik **rumah** lewat layanan HTTPS, lalu menyimpannya di
 * [AppPrefs]. Dipakai otomatis saat ponsel berada di luar jaringan lokal,
 * sehingga pengguna tidak perlu mengetik alamat IP.
 *
 * Deteksi hanya dilakukan saat ponsel tersambung ke jaringan rumah (salah satu
 * kamera berada di subnet yang sama). Bila dilakukan di jaringan lain — mis.
 * data seluler — yang terbaca adalah IP operator, bukan IP rumah, sehingga
 * hasilnya tidak boleh dipakai.
 *
 * Deteksi dibatasi (maksimal sekali per beberapa menit) dan tidak pernah
 * memblokir UI. Bila semua layanan gagal, alamat terakhir yang tersimpan tetap
 * dipakai.
 */
object PublicIp {

    private const val MIN_INTERVAL_MS = 5 * 60_000L
    private const val REFRESH_INTERVAL_MS = 10 * 60_000L // every 10 minutes while on home network
    private const val USER_AGENT = "CCTV-Monitor-Android/1.1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    /** Diset setiap kali IP publik rumah berubah. */
    var onIpChanged: (() -> Unit)? = null

    /** True bila ponsel sedang di jaringan yang sama dengan salah satu kamera. */
    fun isAtHome(prefs: AppPrefs): Boolean =
        prefs.cameras().any { it.enabled && EndpointResolver.isLocalSubnet(it.ip) }

    /**
     * @param force abaikan batas interval (tombol "Perbarui"). Tetap tidak
     *   melakukan apa pun bila ponsel tidak berada di jaringan rumah.
     * @param onDone selalu dipanggil di thread utama; null bila belum ada
     *   alamat publik rumah yang tersimpan.
     */
    fun refreshAsync(prefs: AppPrefs, force: Boolean = false, onDone: ((String?) -> Unit)? = null) {
        val cached = prefs.publicIp
        val fresh = cached.isNotBlank() &&
            System.currentTimeMillis() - prefs.publicIpAt < MIN_INTERVAL_MS

        if (!force && (fresh || !prefs.remoteAuto)) {
            finish(cached.ifBlank { null }, onDone)
            return
        }
        if (!isAtHome(prefs)) {
            // Jangan deteksi dari jaringan lain: hasilnya IP operator.
            finish(cached.ifBlank { null }, onDone)
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            val detected = fetch()
            if (!detected.isNullOrBlank()) {
                prefs.setHomePublicIp(detected)
            }
            inFlight.set(false)
            if (!detected.isNullOrBlank() && detected != cached) {
                // IP berubah -> beri tahu listener.
                main.post { onIpChanged?.invoke() }
            }
            if (onDone != null) {
                withContext(Dispatchers.Main) { onDone(detected) }
            }
        }
    }

    private fun finish(ip: String?, onDone: ((String?) -> Unit)?) {
        if (onDone != null) {
            main.post { onDone(ip) }
        }
    }

    private fun fetch(): String? {
        for (url in ENDPOINTS) {
            val result = try {
                val conn = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                } finally {
                    conn.disconnect()
                }
            } catch (_: Throwable) {
                null
            }
            if (result != null && isValidIpv4(result)) return result
        }
        return null
    }

    private fun isValidIpv4(text: String): Boolean {
        val parts = text.split(".")
        if (parts.size != 4) return false
        if (text.length > 15) return false
        return parts.all { part ->
            val value = part.toIntOrNull()
            part.isNotEmpty() && value != null && value in 0..255
        }
    }

    /** Dicoba berurutan sampai ada yang menjawab. */
    private val ENDPOINTS = listOf(
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://icanhazip.com"
    )
}
