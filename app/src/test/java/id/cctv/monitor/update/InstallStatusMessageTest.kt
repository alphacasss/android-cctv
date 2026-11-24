package id.cctv.monitor.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallStatusMessageTest {

    @Test
    fun forStatus_usesPackageInstallerConstantsAndKeepsDetail() {
        assertEquals(
            "Pembaruan berhasil dipasang.",
            InstallStatusMessage.forStatus(PackageInstaller.STATUS_SUCCESS, null)
        )
        assertTrue(
            InstallStatusMessage.forStatus(PackageInstaller.STATUS_PENDING_USER_ACTION, null)
                .contains("Konfirmasi")
        )
        assertTrue(
            InstallStatusMessage.forStatus(PackageInstaller.STATUS_FAILURE_CONFLICT, null)
                .contains("Konflik")
        )
        assertTrue(
            InstallStatusMessage.forStatus(PackageInstaller.STATUS_FAILURE_INVALID, "Parse error")
                .contains("Parse error")
        )
        assertTrue(
            InstallStatusMessage.forStatus(99, null).contains("99")
        )
    }
}
