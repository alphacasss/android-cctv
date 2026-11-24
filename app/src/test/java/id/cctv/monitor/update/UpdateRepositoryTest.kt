package id.cctv.monitor.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

class UpdateRepositoryTest {

    @Test
    fun latest_parses_release_from_endpoint() {
        val payload = """{"tag_name":"v1.1.5","draft":false,"prerelease":false,"body":"catatan","assets":[{"name":"cctv-monitor-1.1.5-release.apk","size":4,"digest":"sha256:aa","browser_download_url":"http://x"}]}"""
        withServer(payload) { port ->
            runBlocking {
                val result = UpdateRepository.latest("http://127.0.0.1:$port/latest")
                assertEquals("1.1.5", result?.version)
                assertEquals("aa", result?.sha256)
            }
        }
    }

    @Test
    fun latest_returns_null_on_http_error() {
        withServer("", status = 500) { port ->
            runBlocking {
                assertEquals(null, UpdateRepository.latest("http://127.0.0.1:$port/latest"))
            }
        }
    }

    @Test
    fun download_writes_bytes_and_reports_progress() {
        val body = "APK-BYTES"
        withServer(body) { port ->
            val dir = Files.createTempDirectory("update-test").toFile()
            val dest = File(dir, "app.apk")
            val seen = ArrayList<Int>()
            val result = UpdateRepository.download("http://127.0.0.1:$port/apk", dest) { seen += it }
            assertEquals(body, result.readText())
            assertTrue(seen.isNotEmpty())
            assertEquals(100, seen.last())
        }
    }

    @Test
    fun sha256_matches_known_digest() {
        val dir = Files.createTempDirectory("update-test").toFile()
        val file = File(dir, "app.apk")
        file.writeBytes("abc".toByteArray(StandardCharsets.UTF_8))
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("abc".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, UpdateRepository.sha256(file))
    }

    private fun withServer(body: String, status: Int = 200, block: (Int) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val reason = if (status == 200) "OK" else "ERROR"
                    val header = "HTTP/1.1 $status $reason\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(header.toByteArray(StandardCharsets.UTF_8) + bytes)
                    socket.getOutputStream().flush()
                }
            } catch (_: Throwable) {
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            block(server.localPort)
        } finally {
            server.close()
            thread.join(1_000)
        }
    }
}
