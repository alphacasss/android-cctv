package id.cctv.monitor.net

import android.util.Log
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.Camera

/** Hasil pengambilan snapshot: byte JPEG atau alasan kegagalan. */
data class SnapshotResult(val bytes: ByteArray?, val error: String?) {
    val ok: Boolean get() = bytes != null
}

/**
 * Mengambil snapshot JPEG lewat ISAPI. Jauh lebih ringan daripada membuka
 * stream RTSP, jadi dipakai untuk deteksi gerakan dan tombol foto.
 */
object SnapshotClient {

    private const val TAG = "SnapshotClient"

    fun fetch(
        camera: Camera,
        prefs: AppPrefs,
        main: Boolean = false,
        width: Int = 0
    ): ByteArray? = fetchResult(camera, prefs, main, width).bytes

    fun fetchResult(
        camera: Camera,
        prefs: AppPrefs,
        main: Boolean = false,
        width: Int = 0
    ): SnapshotResult {
        val endpoint = EndpointResolver.resolve(camera, prefs.remoteSettings())
        // Snapshot kecil (untuk deteksi gerakan) memakai timeout pendek supaya
        // kamera offline tidak memblokir pemindaian kamera lain.
        val fast = width in 1..320
        val client = IsapiClient(
            endpoint.httpBase,
            prefs.effectiveUser(camera),
            prefs.effectivePass(camera),
            connectTimeoutMs = if (fast) 3_000 else 8_000,
            readTimeoutMs = if (fast) 4_000 else 12_000
        )
        val channel = camera.streamId(main)
        val query = if (width > 0) "?videoResolutionWidth=$width" else ""
        val paths = listOf(
            "/ISAPI/Streaming/channels/$channel/picture$query",
            "/ISAPI/Streaming/channels/$channel/picture",
            "/ISAPI/Streaming/channels/$channel/snapshot$query"
        )
        var lastError = "tidak ada respons"
        for (path in paths) {
            val (code, bytes) = try {
                client.getBinary(path)
            } catch (t: Throwable) {
                lastError = "koneksi gagal: ${t.message ?: t.javaClass.simpleName}"
                continue
            }
            if (code in 200..299 && bytes.size > 800 && isJpeg(bytes)) {
                return SnapshotResult(bytes, null)
            }
            lastError = when {
                code == 401 -> "HTTP 401 — user/password kamera salah"
                code == 403 -> "HTTP 403 — akun tidak punya izin ISAPI/snapshot"
                code == 404 -> "HTTP 404 — endpoint snapshot tidak ada (kanal $channel)"
                code !in 200..299 -> "HTTP $code (kanal $channel)"
                bytes.size <= 800 -> "respons terlalu kecil (${bytes.size} byte)"
                else -> "respons bukan JPEG (${bytes.size} byte)"
            }
        }
        Log.w(TAG, "snapshot gagal ${camera.displayName} (kanal $channel): $lastError")
        return SnapshotResult(null, lastError)
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte()
}
