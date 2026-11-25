package id.cctv.monitor.playback

import id.cctv.monitor.net.Endpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.deleteRecursively
import kotlin.io.path.createTempDirectory

class PlaybackDownloaderTest {

    @Test fun download_writesClipAndClearRemovesIt() {
        val cacheDir = createTempDirectory("nvr-playback").toFile()
        val uri = "rtsp://192.168.100.34/Streaming/tracks/101/?starttime=20261123T165250Z&endtime=20261123T170946Z"
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newSingleThreadExecutor()
        val served = executor.submit {
            repeat(2) { index ->
                server.accept().use { socket ->
                    val (line, body) = readRequest(socket)
                    requests += line
                    if (index == 0) {
                        assertTrue(body.contains("<playbackURI>rtsp://192.168.100.34/Streaming/tracks/101/?starttime=20261123T165250Z&amp;endtime=20261123T170946Z</playbackURI>"))
                        assertTrue(body.contains("xmlns=\"http://www.isapi.org/ver20/XMLSchema\""))
                        assertFalse(body.contains("<trackID>"))
                        respond(socket, "<DownloadResponse><downloadId>clip-42</downloadId></DownloadResponse>")
                    } else {
                        respond(socket, "MP4-BYTES")
                    }
                }
            }
        }
        try {
            val endpoint = Endpoint("127.0.0.1", server.localPort, 554, remote = false)
            val downloader = PlaybackDownloader(cacheDir)
            val clip = RecordingClip(101, 1_700_000_000_000L, 1_700_000_300_000L, playbackUri = uri)
            val file = runBlocking { downloader.download(clip, endpoint, "u", "p").getOrThrow() }
            served.get(5, TimeUnit.SECONDS)

            assertEquals(PlaybackCache.fileFor(cacheDir, clip).path, file.path)
            assertEquals("MP4-BYTES", file.readText())
            assertTrue(requests[0].startsWith("POST /ISAPI/ContentMgmt/download"))
            assertTrue(requests[1].startsWith("GET /ISAPI/ContentMgmt/download/clip-42"))

            downloader.clear()
            assertFalse(file.exists())
        } finally {
            server.close()
            executor.shutdownNow()
            cacheDir.deleteRecursively()
        }
    }

    @Test fun download_deviceRejects_returnsFailure() {
        val cacheDir = createTempDirectory("nvr-playback").toFile()
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val served = executor.submit {
            server.accept().use { socket ->
                readRequest(socket)
                val body = "<DownloadResponse><statusString>rejected</statusString></DownloadResponse>"
                val bytes = body.toByteArray()
                socket.getOutputStream().write(
                    "HTTP/1.1 400 Bad Request\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes
                )
                socket.getOutputStream().flush()
            }
        }
        try {
            val endpoint = Endpoint("127.0.0.1", server.localPort, 554, remote = false)
            val downloader = PlaybackDownloader(cacheDir)
            val clip = RecordingClip(
                101, 1_700_000_000_000L, 1_700_000_300_000L,
                playbackUri = "rtsp://nvr/test"
            )
            assertTrue(runBlocking { downloader.download(clip, endpoint, "u", "p").isFailure })
            served.get(5, TimeUnit.SECONDS)
            assertFalse(PlaybackCache.fileFor(cacheDir, clip).exists())
        } finally {
            server.close()
            executor.shutdownNow()
            cacheDir.deleteRecursively()
        }
    }

    @Test fun download_blankPlaybackUri_failsClearly() {
        val cacheDir = createTempDirectory("nvr-playback").toFile()
        val downloader = PlaybackDownloader(cacheDir)
        val clip = RecordingClip(101, 1_700_000_000_000L, 1_700_000_300_000L)
        val endpoint = Endpoint("127.0.0.1", 1, 554, remote = false)
        val error = runBlocking { downloader.download(clip, endpoint, "u", "p").exceptionOrNull() }
        assertTrue(error?.message?.contains("playbackURI") == true)
        cacheDir.deleteRecursively()
    }

    private fun readRequest(socket: Socket): Pair<String, String> {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val line = reader.readLine() ?: ""
        var length = 0
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                length = header.substringAfter(':').trim().toInt()
            }
        }
        val chars = CharArray(length)
        var total = 0
        while (total < length) {
            val read = reader.read(chars, total, length - total)
            if (read < 0) break
            total += read
        }
        return line to String(chars, 0, total)
    }

    private fun respond(socket: Socket, body: String) {
        val bytes = body.toByteArray()
        socket.getOutputStream().write(
            "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes
        )
        socket.getOutputStream().flush()
    }
}
