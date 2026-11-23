package id.cctv.monitor.data

/** Ringkasan status lokal yang ditampilkan di card "Aktifitas & Status". */
object ActivitySummary {

    fun format(total: Int, off: Int, backups: Int): String {
        val parts = mutableListOf("$total kamera aktif")
        parts += if (off > 0) "$off dalam status mati" else "tidak ada status mati"
        if (backups > 0) parts += "$backups backup belum dipulihkan"
        return parts.joinToString(" • ")
    }
}
