package id.cctv.monitor.cloud

/**
 * Antarmuka penghubung aplikasi dengan layanan cloud Hik-Connect.
 *
 * Implementasi [HikVisionCloudRepository] memakai API web Hik-Connect
 * (login v3 + pagelist device) dan menyiapkan relay live P2P `ysproto://`
 * lewat [streamSource]. Pemutaran relay ditangani
 * [id.cctv.monitor.cloud.stream.CloudVideoPlayer].
 */
interface HikVisionCloudApi {

    /** Login ke cloud. Kembalikan token sesi (dan simpan ke penyimpanan aman). */
    suspend fun login(email: String, password: String): CloudSession

    /** Ambil daftar perangkat/kanal yang terikat ke akun. */
    suspend fun syncDevices(session: CloudSession): List<CloudDevice>

    /** URL stream siap pakai; kosong berarti memakai relay cloud atau RTSP. */
    suspend fun getStreamUrl(device: CloudDevice): String

    /**
     * Parameter relay P2P untuk live stream cloud (VTM/VTDU, token sekali
     * pakai, dan kunci verifikasi perangkat).
     *
     * @return null bila sesi/perangkat tidak siap atau cloud menolak.
     */
    suspend fun streamSource(
        session: CloudSession?,
        device: CloudDevice,
        mainStream: Boolean
    ): CloudStreamSource?

    /** Perbarui token yang hampir kedaluwarsa. */
    suspend fun refreshToken(session: CloudSession): CloudSession

    /** Logout dan hapus kredensial cloud dari perangkat. */
    suspend fun logout(session: CloudSession)
}

/** Sesi login yang diberikan cloud ke lapisan aplikasi. */
data class CloudSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val userId: String,
    val expiresAt: Long,
    /** Domain API region, mis. `api.hik-connect.com` / `apiieu.hik-connect.com`. */
    val apiDomain: String = CloudAuthManager.DEFAULT_DOMAIN
) {
    /** Anggap kedaluwarsa 5 menit lebih awal agar token tidak mati di tengah request. */
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAt - 5 * 60_000L
}
