package id.cctv.monitor.cloud.stream

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import id.cctv.monitor.cloud.CloudStreamSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pemutar live cloud Hik-Connect: membuka relay P2P, mendemux RTP/MPEG-PS,
 * mendekripsi video perangkat (bila perlu), lalu menampilkannya lewat
 * MediaCodec ke [SurfaceView].
 *
 * Pemutar ini mandiri (punya thread IO sendiri) supaya tidak mengganggu
 * LibVLC. Panggil [start]/[stop]/[release] dari thread utama.
 */
class CloudVideoPlayer(
    private val surfaceView: SurfaceView,
    private val sourceProvider: suspend (Boolean) -> CloudStreamSource?,
    private val listener: Listener,
    /**
     * Dipanggil setelah kode verifikasi perangkat berhasil dipulihkan dari
     * stream, supaya pemanggil dapat menyimpannya per serial. Parameter:
     * serial perangkat dan kode (tanpa spasi).
     */
    private val onVerificationCode: (String, String) -> Unit = { _, _ -> }
) : CloudPlayer {

    interface Listener {
        /** [state] berubah; [message] diisi untuk ERROR yang bisa ditampilkan. */
        fun onState(state: State, message: String?)
        fun onVideoSize(width: Int, height: Int)
    }

    enum class State { IDLE, CONNECTING, PLAYING, ERROR, STOPPED }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var keepAlive: Job? = null
    private var session: CloudStreamSession? = null

    @Volatile
    private var stopping = false

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (!stopping) closeTransport()
        }
    }

    override fun start(mainStream: Boolean) {
        stopInternal(notify = false)
        stopping = false
        surfaceView.holder.addCallback(holderCallback)
        emit(State.CONNECTING, null)
        job = scope.launch { streamLoop(mainStream) }
    }

    override fun stop() = stopInternal(notify = true)

    override fun release() {
        stopInternal(notify = false)
        scope.cancel()
    }

    private fun stopInternal(notify: Boolean) {
        stopping = true
        keepAlive?.cancel()
        keepAlive = null
        job?.cancel()
        job = null
        surfaceView.holder.removeCallback(holderCallback)
        closeTransport()
        if (notify) emit(State.STOPPED, null)
    }

    private fun closeTransport() {
        val active = session
        session = null
        try {
            active?.close()
        } catch (_: Exception) {
        }
    }

    private suspend fun streamLoop(mainStream: Boolean) = withContext(Dispatchers.IO) {
        var decoder: CloudVideoDecoder? = null
        try {
            val source = sourceProvider(mainStream)
                ?: throw IllegalStateException(
                    "Stream cloud tidak tersedia. Login ulang Hik-Connect atau bind perangkat."
                )
            val keys = source.encryptionKeys()
            Log.d(
                TAG,
                "stream ch=${source.channel} tipe=${source.streamType} " +
                    "enkripsi=${source.encrypted} kunci=${keys.size}"
            )
            if (source.encrypted && keys.isEmpty()) {
                // Jangan hentikan: sebagian perangkat tetap mengirim stream
                // jernih walau ditandai terenkripsi di cloud.
                Log.w(TAG, "perangkat ditandai terenkripsi tanpa kunci; coba stream apa adanya")
            }
            val surface = awaitSurface()
                ?: throw IllegalStateException("Permukaan video belum siap.")
            val active = YsprotoClient.open(source)
            session = active
            keepAlive = scope.launch {
                while (isActive) {
                    delay(KEEPALIVE_MS)
                    try {
                        active.sendKeepAlive()
                    } catch (e: Exception) {
                        Log.w(TAG, "keepalive berhenti ch=${source.channel}: ${e.message}")
                        return@launch
                    }
                }
            }

            lateinit var demuxer: StreamDemuxer
            demuxer = StreamDemuxer(
                active.streamHead,
                keys,
                onEncryptedParam = { nal, hevc ->
                    // Kode perangkat hanya bisa dipulihkan dari VPS H.265.
                    if (hevc) {
                        HikKeyRecovery.recoverAsync(source.serial, nal) { code ->
                            demuxer.addKey(HikKeyRecovery.keyOf(code), verified = true)
                            onVerificationCode(source.serial, code)
                            Log.d(TAG, "kunci verifikasi dipulihkan ch=${source.channel}")
                        }
                    }
                }
            )
            var reportedEncryption = false
            val dec = CloudVideoDecoder(surface, object : CloudVideoDecoder.Listener {
                override fun onConfigured(width: Int, height: Int) {
                    Log.d(TAG, "video ${width}x$height ch=${source.channel}")
                    applyAspect(width, height)
                    listener.onVideoSize(width, height)
                }

                override fun onFrameRendered() = Unit

                override fun onError(message: String) {
                    emit(State.ERROR, message)
                }
            })
            decoder = dec
            emit(State.PLAYING, null)

            while (isActive && !stopping) {
                val frame = active.readFrame()
                val payload = active.mediaPayload(frame) ?: continue
                if (payload.isEmpty()) continue
                val units = demuxer.feed(payload)
                if (units.isEmpty()) continue
                if (!reportedEncryption && demuxer.encryptType != 0) {
                    reportedEncryption = true
                    Log.d(TAG, "enkripsi tipe=${demuxer.encryptType} kunci=${keys.size}")
                }
                for (unit in units) {
                    // Unit terenkripsi ditahan sampai kunci terverifikasi;
                    // decoder tidak boleh menerima ciphertext.
                    if (demuxer.awaitingKey) continue
                    // Parameter set bisa sudah lewat sebelum kunci tersedia;
                    // kirim ulang cache lengkap saat decoder belum siap.
                    val prime = if (dec.configured) null else demuxer.parameterSets(unit.codec)
                    dec.feed(unit, prime)
                }
                // Decoder gagal/macet: berhenti membaca relay daripada
                // mengulang error yang sama untuk setiap unit berikutnya.
                if (dec.unusable) break
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (e: Exception) {
            if (!stopping) {
                Log.w(
                    TAG,
                    "Stream cloud berakhir: ${e::class.java.simpleName}: ${e.message}",
                    e
                )
                emit(State.ERROR, e.message ?: "Stream cloud terputus.")
            }
        } finally {
            keepAlive?.cancel()
            keepAlive = null
            decoder?.release()
            closeTransport()
        }
    }

    /** Tunggu surface valid; SurfaceView bisa belum siap saat start. */
    private suspend fun awaitSurface(): Surface? {
        val holder = surfaceView.holder
        if (holder.surface?.isValid == true) return holder.surface
        return withTimeoutOrNull(SURFACE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        h.removeCallback(this)
                        if (cont.isActive) cont.resume(h.surface)
                    }

                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) = Unit

                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        h.removeCallback(this)
                        if (cont.isActive) cont.resume(null)
                    }
                }
                holder.addCallback(callback)
                cont.invokeOnCancellation { main.post { holder.removeCallback(callback) } }
            }
        }
    }

    /** Skala gambar agar pas (letterbox) di dalam wadah tile/layar penuh. */
    private fun applyAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        main.post {
            val parent = surfaceView.parent as? ViewGroup ?: return@post
            val parentWidth = parent.width
            val parentHeight = parent.height
            if (parentWidth <= 0 || parentHeight <= 0) return@post
            val scale = minOf(
                parentWidth.toFloat() / width,
                parentHeight.toFloat() / height
            )
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            val params = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return@post
            if (params.width != targetWidth || params.height != targetHeight ||
                params.gravity != Gravity.CENTER
            ) {
                params.width = targetWidth
                params.height = targetHeight
                params.gravity = Gravity.CENTER
                surfaceView.layoutParams = params
            }
        }
    }

    private fun emit(state: State, message: String?) {
        main.post {
            if (state == State.STOPPED && !stopping) return@post
            listener.onState(state, message)
        }
    }

    /** Tampilkan surface cloud dan sembunyikan isi VLC (dipanggil PlayerManager). */
    override fun showSurface(visible: Boolean) {
        main.post {
            surfaceView.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val TAG = "CloudVideoPlayer"
        const val KEEPALIVE_MS = 3_000L
        const val SURFACE_TIMEOUT_MS = 10_000L
    }
}
