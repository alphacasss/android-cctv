package id.cctv.monitor.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Network + disk layer for release checks and APK downloads. */
object UpdateRepository {

    const val LATEST_URL =
        "https://api.github.com/repos/alphacasss/android-cctv/releases/latest"

    private const val BUFFER_SIZE = 64 * 1024
    private const val TIMEOUT_MS = 15_000

    suspend fun latest(url: String = LATEST_URL): ReleaseInfo? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "cctv-monitor")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = connection.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            }
            ReleaseParser.parseLatest(body)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Downloads [url] into [dest], replacing any existing file. Throws on short reads. */
    fun download(url: String, dest: File, onProgress: (percent: Int) -> Unit): File {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "cctv-monitor")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val expected = connection.contentLengthLong
            dest.parentFile?.mkdirs()
            if (dest.exists() && !dest.delete()) throw IOException("Gagal menghapus berkas lama")
            var total = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        if (expected > 0) {
                            val percent = ((total * 100) / expected).toInt().coerceAtMost(100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
            if (expected > 0 && total != expected) {
                throw IOException("Unduh tidak lengkap: $total dari $expected byte")
            }
            if (total == 0L) throw IOException("Unduh kosong")
            if (lastPercent < 100) onProgress(100)
            return dest
        } catch (e: Exception) {
            if (dest.exists()) dest.delete()
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
