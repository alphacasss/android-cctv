package id.cctv.monitor.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regresi ringkasan status lokal pada card "Aktifitas & Status". */
class ActivitySummaryTest {

    @Test
    fun tanpaStatusMati() {
        assertEquals(
            "8 kamera aktif • tidak ada status mati",
            ActivitySummary.format(8, 0, 0)
        )
    }

    @Test
    fun denganStatusMatiDanBackup() {
        assertEquals(
            "8 kamera aktif • 2 dalam status mati • 1 backup belum dipulihkan",
            ActivitySummary.format(8, 2, 1)
        )
    }
}
