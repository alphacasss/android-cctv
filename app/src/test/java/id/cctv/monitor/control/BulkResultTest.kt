package id.cctv.monitor.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regresi dialog "RTSP dimatikan" yang muncul walau sebagian kanal gagal. */
class BulkResultTest {

    @Test
    fun from_menandaiKanalYangTidakDikonfirmasiSebagaiGagal() {
        val result = BulkResult.from(
            listOf(
                BulkOutcome("CAM-01", applied = true),
                BulkOutcome("CAM-02", applied = false)
            )
        )

        assertEquals(
            listOf("CAM-02: perangkat tidak mengonfirmasi perubahan"),
            result.errors
        )
        assertFalse(result.ok)
    }

    @Test
    fun from_ok_bilaSemuaKanalDikonfirmasi() {
        val result = BulkResult.from(
            listOf(BulkOutcome("CAM-01"), BulkOutcome("CAM-02"))
        )

        assertTrue(result.ok)
        assertEquals(emptyList<String>(), result.errors)
    }

    @Test
    fun from_menyimpanPesanKegagalanRute() {
        val result = BulkResult.from(
            listOf(BulkOutcome("CAM-03", error = "timeout"))
        )

        assertEquals(listOf("CAM-03: timeout"), result.errors)
    }
}
