package id.cctv.monitor.update

/** Pesan pengguna untuk kode status PackageInstaller (angka mengikuti konstanta Android). */
internal object InstallStatusMessage {

    fun forStatus(status: Int, detail: String?): String {
        val base = when (status) {
            1 -> "Pembaruan berhasil dipasang."
            0 -> "Pemasangan gagal."
            2 -> "Pemasangan dibatalkan."
            3 -> "Pemasangan diblokir oleh perangkat."
            4 -> "Konflik dengan aplikasi yang sudah terpasang."
            5 -> "APK tidak kompatibel dengan perangkat ini."
            6 -> "APK tidak valid atau rusak."
            7 -> "Ruang penyimpanan tidak cukup."
            8 -> "Izin pemasangan ditolak."
            else -> "Pemasangan gagal (kode $status)."
        }
        return if (detail.isNullOrBlank()) base else "$base Detail: $detail"
    }
}
