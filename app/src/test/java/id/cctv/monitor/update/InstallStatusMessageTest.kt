package id.cctv.monitor.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallStatusMessageTest {

    @Test
    fun forStatus_mapsKnownCodesAndKeepsDetail() {
        assertEquals(
            "Pembaruan berhasil dipasang.",
            InstallStatusMessage.forStatus(1, null)
        )
        assertTrue(InstallStatusMessage.forStatus(4, null).contains("Konflik"))
        assertTrue(InstallStatusMessage.forStatus(6, "Parse error").contains("Parse error"))
        assertTrue(InstallStatusMessage.forStatus(99, null).contains("99"))
    }
}
