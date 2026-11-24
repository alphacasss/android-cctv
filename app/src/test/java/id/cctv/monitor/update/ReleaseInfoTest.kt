package id.cctv.monitor.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {

    private fun release(
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = APK_ASSET
    ): String = """
        {
          "tag_name": "v1.1.5",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": "Perbaikan rekaman.",
          "assets": [$assets]
        }
    """.trimIndent()

    @Test
    fun parseLatest_picks_release_apk_and_sha256() {
        val result = ReleaseParser.parseLatest(release())
        assertEquals("1.1.5", result?.version)
        assertEquals("cctv-monitor-1.1.5-release.apk", result?.apkName)
        assertEquals("https://example.test/app.apk", result?.apkUrl)
        assertEquals(110_131_103L, result?.sizeBytes)
        assertEquals("abc123", result?.sha256)
        assertEquals("Perbaikan rekaman.", result?.notes)
    }

    @Test
    fun parseLatest_returns_null_when_no_apk_asset() {
        assertNull(
            ReleaseParser.parseLatest(
                release(assets = """{"name":"notes.txt","size":12,"digest":"sha256:x","browser_download_url":"u"}""")
            )
        )
    }

    @Test
    fun parseLatest_ignores_draft_and_prerelease() {
        assertNull(ReleaseParser.parseLatest(release(draft = true)))
        assertNull(ReleaseParser.parseLatest(release(prerelease = true)))
    }

    @Test
    fun versionCode_parses_semver() {
        assertEquals(10_104L, ReleaseParser.versionCode("1.1.4"))
        assertEquals(20_000L, ReleaseParser.versionCode("2.0.0"))
        assertEquals(0L, ReleaseParser.versionCode("v1.2"))
    }

    @Test
    fun isNewer_compares_numeric_version() {
        assertTrue(ReleaseParser.isNewer("1.1.5", "1.1.4"))
        assertFalse(ReleaseParser.isNewer("1.1.4", "1.1.4"))
        assertFalse(ReleaseParser.isNewer("bogus", "1.1.4"))
    }

    private companion object {
        const val APK_ASSET = """
            {"name":"notes.txt","size":12,"browser_download_url":"n"},
            {"name":"cctv-monitor-1.1.5-release.apk","size":110131103,
             "digest":"sha256:abc123","browser_download_url":"https://example.test/app.apk"}
        """
    }
}
