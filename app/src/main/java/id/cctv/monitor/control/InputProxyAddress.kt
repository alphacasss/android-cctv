package id.cctv.monitor.control

/**
 * Alamat sumber TEST-NET-1 yang dipakai untuk memutus kanal NVR.
 *
 * Tiap kanal harus memakai alamat berbeda: firmware DS-7608NI-Q1 menolak PUT
 * kedua kanal dengan alamat yang sama (`ModifiedIpCannotBeDuplicate`).
 */
object InputProxyAddress {
    private const val PREFIX = "192.0.2."

    fun dummyIp(channel: Int): String = PREFIX + channel.coerceIn(1, 254)

    fun isDummy(ip: String?): Boolean {
        if (ip == null || !ip.startsWith(PREFIX)) return false
        val host = ip.removePrefix(PREFIX).toIntOrNull() ?: return false
        return host in 1..254
    }

    /**
     * Alamat dummy peninggalan versi lama (satu IP untuk semua kanal) harus
     * ditulis ulang ke alamat kanonik kanal ini, jika tidak NVR menolaknya sebagai
     * duplikat saat kanal lain memakai alamat yang sama.
     */
    fun needsRewrite(ip: String?, channel: Int): Boolean =
        isDummy(ip) && ip != dummyIp(channel)
}
