package id.cctv.monitor.net

import id.cctv.monitor.playback.DirectPlaybackIsapi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IsapiClientPostTest {

    @Test fun post_sendsXmlBody() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        var received = ""
        val served = executor.submit {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toInt()
                    }
                }
                val body = CharArray(contentLength)
                var total = 0
                while (total < contentLength) {
                    val read = reader.read(body, total, contentLength - total)
                    if (read < 0) break
                    total += read
                }
                received = String(body, 0, total)
                socket.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()
                )
                socket.getOutputStream().flush()
            }
        }
        try {
            val client = IsapiClient("http://127.0.0.1:${server.localPort}", "u", "p")
            val response = client.post("/search", "<xml/>")
            served.get(5, TimeUnit.SECONDS)
            assertEquals(200, response.code)
            assertEquals("<xml/>", received)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun adapter_exposesPostThroughPlaybackIsapi() {
        runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val served = executor.submit {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toInt()
                    }
                }
                val body = CharArray(contentLength)
                var total = 0
                while (total < contentLength) {
                    val read = reader.read(body, total, contentLength - total)
                    if (read < 0) break
                    total += read
                }
                socket.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()
                )
                socket.getOutputStream().flush()
            }
        }
        try {
            val client = IsapiClient("http://127.0.0.1:${server.localPort}", "u", "p")
            assertEquals(200, DirectPlaybackIsapi(client).post("/search", "<xml/>").code)
            served.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
        }
        }
    }
}
