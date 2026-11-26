package id.cctv.monitor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.deleteRecursively
import kotlin.io.path.createTempDirectory

class IsapiClientDownloadTest {

    @Test fun downloadToFile_streamsBodyIntoFile() {
        val dir = createTempDirectory("isapi-download").toFile()
        val target = java.io.File(dir, "clip.mp4")
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val body = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val executor = Executors.newSingleThreadExecutor()
        val served = executor.submit {
            server.accept().use { socket ->
                readRequest(socket)
                respond(socket, 200, body)
            }
        }
        try {
            val client = IsapiClient("http://127.0.0.1:${server.localPort}", "u", "p")
            val code = client.downloadToFile("/ISAPI/ContentMgmt/download/clip-42", target)
            served.get(10, TimeUnit.SECONDS)

            assertEquals(200, code)
            assertTrue(target.exists())
            assertEquals(body.size.toLong(), target.length())
            assertTrue(target.readBytes().contentEquals(body))
        } finally {
            server.close()
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }

    @Test fun postToFile_streamsPostResponseIntoFile() {
        val dir = createTempDirectory("isapi-download").toFile()
        val target = java.io.File(dir, "clip.mp4")
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val body = ByteArray(1024 * 1024) { (it % 253).toByte() }
        val executor = Executors.newSingleThreadExecutor()
        val served = executor.submit {
            server.accept().use { socket ->
                readRequest(socket)
                respond(socket, 200, body)
            }
        }
        try {
            val client = IsapiClient("http://127.0.0.1:${server.localPort}", "u", "p")
            val code = client.postToFile("/ISAPI/ContentMgmt/download", "<xml/>", target)
            served.get(10, TimeUnit.SECONDS)

            assertEquals(200, code)
            assertTrue(target.exists())
            assertEquals(body.size.toLong(), target.length())
            assertTrue(target.readBytes().contentEquals(body))
        } finally {
            server.close()
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }

    @Test fun downloadToFile_truncatedBodyLeavesNoPartialFile() {
        val dir = createTempDirectory("isapi-download").toFile()
        val target = java.io.File(dir, "clip.mp4")
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val served = executor.submit {
            server.accept().use { socket ->
                readRequest(socket)
                respondWithDeclaredLength(socket, 200, 4096, ByteArray(128) { 7 })
            }
        }
        try {
            val client = IsapiClient("http://127.0.0.1:${server.localPort}", "u", "p")
            val error = runCatching {
                client.downloadToFile("/ISAPI/ContentMgmt/download/clip-42", target)
            }.exceptionOrNull()
            served.get(5, TimeUnit.SECONDS)

            assertTrue(error is IsapiException)
            assertFalse(target.exists())
        } finally {
            server.close()
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }

    @Test fun downloadToFile_httpFailureLeavesNoPartialFile() {
        val dir = createTempDirectory("isapi-download").toFile()
        val target = java.io.File(dir, "clip.mp4")
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val served = executor.submit {
            server.accept().use { socket ->
                readRequest(socket)
                respond(socket, 500, "rejected".toByteArray())
            }
        }
        try {
            val client = IsapiClient("http://127.0.0.1:${server.localPort}", "u", "p")
            val error = runCatching {
                client.downloadToFile("/ISAPI/ContentMgmt/download/clip-42", target)
            }.exceptionOrNull()
            served.get(5, TimeUnit.SECONDS)

            assertTrue(error is IsapiException)
            assertFalse(target.exists())
        } finally {
            server.close()
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }

    private fun readRequest(socket: Socket) {
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
    }

    private fun respondWithDeclaredLength(
        socket: Socket,
        code: Int,
        declaredLength: Int,
        body: ByteArray
    ) {
        val status = if (code == 200) "200 OK" else "500 Internal Server Error"
        socket.getOutputStream().write(
            "HTTP/1.1 $status\r\nContent-Length: $declaredLength\r\nConnection: close\r\n\r\n".toByteArray() + body
        )
        socket.getOutputStream().flush()
    }

    private fun respond(socket: Socket, code: Int, body: ByteArray) {
        val status = if (code == 200) "200 OK" else "500 Internal Server Error"
        socket.getOutputStream().write(
            "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body
        )
        socket.getOutputStream().flush()
    }
}
