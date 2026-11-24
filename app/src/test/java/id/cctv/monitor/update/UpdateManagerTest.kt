package id.cctv.monitor.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    private fun release(version: String) = ReleaseInfo(
        version = version,
        notes = "Catatan",
        apkUrl = "https://example.test/app.apk",
        apkName = "cctv-monitor-$version-release.apk",
        sizeBytes = 1L,
        sha256 = null
    )

    @Test
    fun decide_available_when_remote_is_newer() {
        val result = UpdateManager.decide(release("1.1.5"), "1.1.4")
        assertTrue(result is UpdateManager.CheckResult.Available)
        assertEquals("1.1.5", (result as UpdateManager.CheckResult.Available).release.version)
    }

    @Test
    fun decide_up_to_date_when_same_or_malformed() {
        assertTrue(UpdateManager.decide(release("1.1.4"), "1.1.4") is UpdateManager.CheckResult.UpToDate)
        assertTrue(UpdateManager.decide(release("bogus"), "1.1.4") is UpdateManager.CheckResult.UpToDate)
    }

    @Test
    fun decide_failed_when_no_release() {
        assertTrue(UpdateManager.decide(null, "1.1.4") is UpdateManager.CheckResult.Failed)
    }
}
