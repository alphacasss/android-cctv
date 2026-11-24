package id.cctv.monitor.update

import org.json.JSONObject

/** Metadata rilis stabil yang diperlukan untuk mengunduh dan memasang APK. */
data class ReleaseInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    val sha256: String?
)

/** Parser murni untuk respons `releases/latest` GitHub. */
object ReleaseParser {

    fun parseLatest(json: String): ReleaseInfo? {
        val release = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null

        val version = release.optString("tag_name").removePrefix("v")
        if (version.isEmpty()) return null

        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith("-release.apk")) continue
            val url = asset.optString("browser_download_url")
            if (url.isEmpty()) continue
            val digest = asset.optString("digest")
            val sha = if (digest.startsWith("sha256:")) digest.removePrefix("sha256:") else null
            return ReleaseInfo(
                version = version,
                notes = release.optString("body"),
                apkUrl = url,
                apkName = name,
                sizeBytes = asset.optLong("size"),
                sha256 = sha
            )
        }
        return null
    }

    /** `1.1.4` → 10104. Kembalikan 0 untuk format tak dikenal. */
    fun versionCode(version: String): Long {
        val parts = version.split('.')
        if (parts.size != 3) return 0L
        val numbers = parts.map { it.toLongOrNull() ?: return 0L }
        return numbers[0] * 10_000 + numbers[1] * 100 + numbers[2]
    }

    fun isNewer(remote: String, current: String): Boolean {
        val a = versionCode(remote)
        val b = versionCode(current)
        return a > 0 && b > 0 && a > b
    }
}
