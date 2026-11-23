package id.cctv.monitor.cloud.stream

import android.util.Log
import id.cctv.monitor.cloud.CloudStreamSource
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Satu frame relay `ysproto` (header 5 byte + payload). */
internal class StreamFrame(
    val channel: Int,
    val code: Int,
    val body: ByteArray
)

/**
 * Klien protokol relay cloud Hik-Connect (`ysproto://`).
 *
 * Alurnya:
 *  1. Buka koneksi TCP ke VTM dan kirim permintaan stream (code `0x13B`).
 *  2. VTM menjawab `result=5302` + URL VTDU + stream key.
 *  3. Buka koneksi kedua ke VTDU dengan stream key; VTDU menjawab `ssn` dan
 *     `streamHead` (deskripsi kontainer/kodek).
 *  4. Panggil [CloudStreamSession.readFrame] berulang; kirim keepalive
 *     `0x132` tiap 3 detik lewat koneksi VTM (kontrol), bukan VTDU. VTDU
 *     menutup koneksi bila menerima pesan yang bukan media.
 *
 * Bila relay menyediakan kunci publik, seluruh pesan dibungkus amplop ECDH
 * ([EcdhEnvelope]); channel media `0x0B`/`0x0C` juga terenkripsi.
 */
internal object YsprotoClient {

    private const val TAG = "YsprotoClient"

    private const val MSG_STREAM_INFO_REQ = 0x13B
    private const val RESULT_REDIRECT = 5302

    private const val CLIENT_VERSION = "v3.10.1.20260710"

    /** Buka sesi stream; melempar exception bila relay menolak. */
    fun open(source: CloudStreamSource): CloudStreamSession {
        val peerKey = source.publicKey
        val useEcdh = peerKey != null
        var lastError: Exception? = null

        // Token bersifat sekali pakai: coba token pertama dengan ECDH, lalu
        // token berikutnya tanpa enkripsi bila relay menolak.
        val attempts = buildList {
            if (useEcdh) add(true)
            add(false)
        }
        for ((attempt, ecdh) in attempts.withIndex()) {
            val tokenIndex = minOf(attempt, source.tokens.lastIndex).coerceAtLeast(0)
            val url = buildStreamUrl(source, ecdh, tokenIndex)
            try {
                return connect(source, url, if (ecdh) peerKey else null, source.keyVersion)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Percobaan ${if (ecdh) "ECDH" else "plain"} gagal: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("Relay cloud tidak dapat dihubungi.")
    }

    private fun connect(
        source: CloudStreamSource,
        requestUrl: String,
        peerKey: ByteArray?,
        keyVersion: Int
    ): CloudStreamSession {
        val vtmEnvelope = peerKey?.let { EcdhEnvelope(it, keyVersion) }
        val vtm = YsprotoConn(connect(source.vtmHost, source.vtmPort), vtmEnvelope)
        var relay: YsprotoConn? = null
        try {
            vtm.send(streamInfoRequest(requestUrl, null), MSG_STREAM_INFO_REQ)
            val response = vtm.receiveFields()
            val result = Proto.result(response)
            if (result != 0 && result != RESULT_REDIRECT) {
                throw IllegalStateException("VTM menolak stream (result=$result).")
            }
            val redirect = Proto.text(response, 7)
            if (redirect.isBlank()) throw IllegalStateException("VTM tidak memberi URL relay.")
            val streamKey = Proto.bytes(response, 5)
            val (host, port) = hostPort(redirect)

            val media = YsprotoConn(
                connect(host, port),
                peerKey?.let { EcdhEnvelope(it, keyVersion) },
                // Handshake media membawa session key sendiri; pisahkan dari
                // amplop kontrol agar keepalive tetap memakai kunci kontrol.
                if (peerKey != null) EcdhEnvelope(null, 1) else null
            )
            relay = media
            media.send(streamInfoRequest(requestUrl, streamKey), MSG_STREAM_INFO_REQ)
            val relayResponse = media.receiveFields()
            val relayResult = Proto.result(relayResponse)
            if (relayResult != 0) {
                throw IllegalStateException("VTDU menolak stream (result=$relayResult).")
            }
            val ssn = Proto.bytes(relayResponse, 4) ?: ByteArray(0)
            val head = Proto.bytes(relayResponse, 3) ?: ByteArray(0)
            // VTM tetap terbuka sebagai kanal kontrol (keepalive); VTDU hanya
            // membawa media dan akan memutus koneksi bila dikirimi pesan lain.
            return CloudStreamSession(vtm, media, ssn, head)
        } catch (e: Exception) {
            relay?.close()
            vtm.close()
            throw e
        }
    }

    private fun buildStreamUrl(source: CloudStreamSource, ecdh: Boolean, tokenIndex: Int): String {
        val token = source.tokens.getOrNull(tokenIndex)
            ?: source.tokens.firstOrNull().orEmpty()
        val builder = StringBuilder("ysproto://")
            .append(source.vtmHost).append(':').append(source.vtmPort)
            .append("/live?dev=").append(source.serial)
            .append("&chn=").append(source.channel)
            .append("&stream=").append(source.streamType)
            .append("&cln=55&isp=0&auth=1&ssn=").append(token)
        val biz = source.bizQuery.trim()
        if (biz.isNotEmpty()) {
            builder.append('&').append(biz.removePrefix("&"))
        }
        builder.append("&vip=0&timestamp=").append(System.currentTimeMillis())
        if (ecdh) builder.append("&ecdh=1")
        return builder.toString()
    }

    private fun streamInfoRequest(url: String, streamKey: ByteArray?): ByteArray {
        var body = Proto.pbStr(1, url)
        if (streamKey != null && streamKey.isNotEmpty()) body += Proto.pbStr(2, streamKey)
        body += Proto.pbStr(3, CLIENT_VERSION)
        body += Proto.pbInt(4, 0)
        body += Proto.pbStr(6, CLIENT_VERSION)
        return body
    }

    private fun connect(host: String, port: Int): Socket = Socket().apply {
        connect(InetSocketAddress(host, port), 10_000)
        soTimeout = 20_000
        tcpNoDelay = true
    }

    private fun hostPort(url: String): Pair<String, Int> {
        val authority = url.substringAfter("://", url).substringBefore('/')
        val colon = authority.lastIndexOf(':')
        require(colon > 0) { "URL relay tanpa port: $url" }
        val host = authority.substring(0, colon)
        val port = authority.substring(colon + 1).toIntOrNull()
            ?: throw IllegalArgumentException("Port relay tidak valid: $url")
        return host to port
    }
}

/**
 * Koneksi relay dengan framing 5 byte dan amplop ECDH opsional.
 *
 * [envelope] hanya dipakai untuk pesan kontrol (handshake, keepalive, balasan
 * VTDU). Media memakai [mediaEnvelope] tersendiri: handshake media membawa
 * session key baru, jadi bila amplop kontrol dipakai bersama, session key
 * media akan menimpa kunci kontrol dan keepalive berikutnya ditolak relay.
 */
internal class YsprotoConn(
    private val socket: Socket,
    private val envelope: EcdhEnvelope?,
    private val mediaEnvelope: EcdhEnvelope? = null
) {

    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    fun send(body: ByteArray, code: Int) {
        val payload = envelope?.encrypt(body) ?: body
        output.writeByte(0x24)
        output.writeByte(if (envelope != null) CHANNEL_ECDH_MSG else CHANNEL_MSG)
        output.writeShort(payload.size)
        output.writeShort(0)
        output.writeShort(code)
        output.write(payload)
        output.flush()
    }

    fun receive(): StreamFrame {
        val magic = input.readUnsignedByte()
        if (magic != 0x24) throw IllegalStateException("Header relay tidak dikenal: $magic")
        val channel = input.readUnsignedByte()
        val length = input.readUnsignedShort()
        input.readUnsignedShort()
        val code = input.readUnsignedShort()
        val body = ByteArray(length)
        input.readFully(body)
        return StreamFrame(channel, code, body)
    }

    fun receiveFields(): ProtoFields {
        val frame = receive()
        val body = if (frame.channel == CHANNEL_ECDH_MSG && envelope != null) {
            envelope.decrypt(frame.body)
        } else {
            frame.body
        }
        return Proto.decode(body)
    }

    /**
     * Payload media yang sudah didekripsi, atau null bila frame bukan media.
     *
     * Media memakai session key ECDH dari handshake koneksi relay ini (bukan
     * kunci publik dan bukan sesi baru). Channel RTP `0x0C` menyimpan 12 byte
     * header RTP sebagai plaintext; marker bit dibersihkan seperti aplikasi
     * resmi.
     */
    fun mediaPayload(frame: StreamFrame): ByteArray? {
        if (frame.channel == CHANNEL_MEDIA) return frame.body
        if (frame.channel != CHANNEL_ECDH_MEDIA && frame.channel != CHANNEL_ECDH_RTP) return null
        val current = mediaEnvelope ?: return null
        val headerSize = if (frame.channel == CHANNEL_ECDH_RTP) 12 else 0
        if (frame.body.size <= headerSize) return null
        val plain = try {
            current.decrypt(frame.body.copyOfRange(headerSize, frame.body.size))
        } catch (_: Exception) {
            // Paket rusak/kunci belum siap: lewati, jangan hentikan stream.
            return ByteArray(0)
        }
        if (plain.size <= 1) return ByteArray(0)
        if (headerSize == 0) return plain
        val out = ByteArray(headerSize + plain.size)
        out[0] = (frame.body[0].toInt() and 0xDF).toByte()
        System.arraycopy(frame.body, 1, out, 1, headerSize - 1)
        System.arraycopy(plain, 0, out, headerSize, plain.size)
        return out
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val CHANNEL_MSG = 0x00
        const val CHANNEL_MEDIA = 0x01
        const val CHANNEL_ECDH_MSG = 0x0A
        const val CHANNEL_ECDH_MEDIA = 0x0B
        const val CHANNEL_ECDH_RTP = 0x0C
    }
}

/**
 * Sesi stream aktif. [readFrame] memblokir sampai ada paket; panggil dari
 * thread IO. Setelah selesai selalu panggil [close].
 *
 * [control] adalah koneksi VTM (dipakai keepalive), [media] koneksi VTDU yang
 * hanya membawa paket media.
 */
internal class CloudStreamSession(
    private val control: YsprotoConn,
    private val media: YsprotoConn,
    private val ssn: ByteArray,
    val streamHead: ByteArray
) {

    fun sendKeepAlive() {
        // Sesi yang dijaga adalah sesi VTDU (media). VTM menutup koneksinya
        // segera setelah memberi redirect, jadi keepalive tidak dikirim ke sana.
        media.send(Proto.pbStr(1, ssn), MSG_KEEPALIVE_REQ)
    }

    fun readFrame(): StreamFrame = media.receive()

    /** Payload media terdekripsi; lihat [YsprotoConn.mediaPayload]. */
    fun mediaPayload(frame: StreamFrame): ByteArray? = media.mediaPayload(frame)

    fun close() {
        control.close()
        media.close()
    }

    private companion object {
        const val MSG_KEEPALIVE_REQ = 0x132
    }
}
