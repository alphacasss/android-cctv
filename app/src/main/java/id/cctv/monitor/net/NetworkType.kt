package id.cctv.monitor.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Deteksi jaringan aktif untuk kebijakan stream.
 *
 * Dipakai supaya pemutaran RTSP/VLC bisa dihentikan otomatis saat ponsel
 * memakai paket data seluler. Pada kondisi itu jalur Hik-Connect cloud yang
 * dipakai, karena relay cloud tidak membutuhkan port forwarding atau VPN.
 */
object NetworkType {

    /** True bila jaringan yang sedang aktif memakai transport seluler. */
    fun isCellular(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
