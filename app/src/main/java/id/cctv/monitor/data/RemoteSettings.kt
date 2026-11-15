package id.cctv.monitor.data

/**
 * Setelan akses luar jaringan untuk semua kamera.
 *
 * [host] adalah alamat tetap opsional (DDNS). Bila kosong, aplikasi memakai
 * [publicIp] yang dideteksi otomatis. Port luar dihitung dari [httpBase] /
 * [rtspBase] ditambah nomor kamera, sehingga tidak perlu diisi per kamera.
 */
data class RemoteSettings(
    val enabled: Boolean = true,
    val host: String = "",
    val httpBase: Int = DEFAULT_HTTP_BASE,
    val rtspBase: Int = DEFAULT_RTSP_BASE,
    val publicIp: String = ""
) {
    /** Alamat yang dipakai: DDNS bila diisi, jika tidak IP publik terdeteksi. */
    val effectiveHost: String
        get() = host.ifBlank { publicIp }

    val hasHost: Boolean get() = effectiveHost.isNotBlank()

    fun httpPort(cameraId: Int): Int = (httpBase + cameraId).coerceIn(1, 65535)

    fun rtspPort(cameraId: Int): Int = (rtspBase + cameraId).coerceIn(1, 65535)

    companion object {
        const val DEFAULT_HTTP_BASE = 8080
        const val DEFAULT_RTSP_BASE = 8550

        /** Batas atas agar `base + id` tetap ≤ 65535. */
        const val MIN_BASE = 1
        const val MAX_BASE = 65000
    }
}
