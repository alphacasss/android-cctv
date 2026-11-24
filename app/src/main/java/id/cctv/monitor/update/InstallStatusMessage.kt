package id.cctv.monitor.update

import android.content.pm.PackageInstaller

/** Pesan pengguna untuk kode status PackageInstaller. */
internal object InstallStatusMessage {

    fun forStatus(status: Int, detail: String?): String {
        val base = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Pembaruan berhasil dipasang."
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Konfirmasi pemasangan diperlukan."
            PackageInstaller.STATUS_FAILURE -> "Pemasangan gagal."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Pemasangan diblokir oleh perangkat."
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Pemasangan dibatalkan."
            PackageInstaller.STATUS_FAILURE_INVALID -> "APK tidak valid atau rusak."
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Konflik dengan aplikasi yang sudah terpasang."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Ruang penyimpanan tidak cukup."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK tidak kompatibel dengan perangkat ini."
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> "Pemasangan timeout. Coba lagi."
            else -> "Pemasangan gagal (kode $status)."
        }
        return if (detail.isNullOrBlank()) base else "$base Detail: $detail"
    }
}
