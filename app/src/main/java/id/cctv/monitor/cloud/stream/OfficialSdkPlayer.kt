package id.cctv.monitor.cloud.stream

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import id.cctv.monitor.CctvApp
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
 * Pemutar cloud memakai SDK resmi Hik-Connect. Beda dengan [CloudVideoPlayer],
 * decoding dan rendering dilakukan native SDK, jadi data callback tidak perlu
 * dilewatkan ke MediaCodec.
 */
class OfficialSdkPlayer(
    private val surfaceView: SurfaceView,
    private val sourceProvider: suspend (Boolean) -> CloudStreamSource?,
    private val listener: CloudVideoPlayer.Listener,
    private val channelSerialProvider: () -> String = { "" }
) : CloudPlayer {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var handle = 0L

    /** Berubah setiap start/stop; dipakai menolak handle yang terbuka terlambat. */
    private var generation = 0

    @Volatile
    private var stopping = false

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (!stopping) stop()
        }
    }

    override fun start(mainStream: Boolean) {
        stopInternal(notify = false)
        val token = generation
        stopping = false
        surfaceView.holder.addCallback(holderCallback)
        emit(token, CloudVideoPlayer.State.CONNECTING, null)
        job = scope.launch { streamLoop(mainStream, token) }
    }

    override fun stop() = stopInternal(notify = true)

    override fun release() {
        stopInternal(notify = false)
        scope.cancel()
    }

    override fun showSurface(visible: Boolean) {
        main.post {
            surfaceView.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun stopInternal(notify: Boolean) {
        generation++
        stopping = true
        job?.cancel()
        job = null
        surfaceView.holder.removeCallback(holderCallback)
        val active = handle
        handle = 0L
        if (active != 0L) OfficialSdkRuntime.closeStream(active)
        if (notify) emit(generation, CloudVideoPlayer.State.STOPPED, null)
    }

    private suspend fun streamLoop(mainStream: Boolean, token: Int) = withContext(Dispatchers.IO) {
        try {
            emit(token, CloudVideoPlayer.State.CONNECTING, null)
            val source = sourceProvider(mainStream)
                ?: throw IllegalStateException(
                    "Stream cloud tidak tersedia. Login ulang Hik-Connect atau bind perangkat."
                )
            val surface = awaitSurface()
                ?: throw IllegalStateException("Permukaan video belum siap.")
            if (token != generation || !isActive) return@withContext
            val app = surfaceView.context.applicationContext as? CctvApp
            val session = app?.cloudAuth?.session?.value
            val opened = OfficialSdkRuntime.openStream(
                context = surfaceView.context.applicationContext,
                source = source,
                verificationCode = source.verificationCode,
                channelSerial = channelSerialProvider(),
                clientSession = session?.accessToken.orEmpty(),
                userId = session?.userId.orEmpty(),
                surface = surface,
                onData = { _, _ -> Unit },
                onEvent = { name, code, extra ->
                    if (name == "onErrorListener") {
                        emit(token, CloudVideoPlayer.State.ERROR, "SDK resmi error $code/$extra")
                    }
                }
            )
            // stop() bisa terjadi saat native masih membuka handle. Tutup handle
            // yang terlambat agar stream tidak hidup di tile yang sudah dilepas.
            if (token != generation || !isActive) {
                OfficialSdkRuntime.closeStream(opened)
                return@withContext
            }
            handle = opened
            if (handle == 0L) throw IllegalStateException("SDK handle kosong")
            emit(token, CloudVideoPlayer.State.PLAYING, null)

            // Jaga coroutine tetap hidup sampai stop()/surfaceDestroyed.
            while (isActive) delay(1_000L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "SDK stream gagal", e)
            emit(token, CloudVideoPlayer.State.ERROR, e.message ?: "SDK stream gagal")
        }
    }

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

                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit

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

    private fun emit(token: Int, state: CloudVideoPlayer.State, message: String?) {
        main.post {
            if (token != generation) return@post
            listener.onState(state, message)
        }
    }

    private companion object {
        const val TAG = "OfficialSdkPlayer"
        const val SURFACE_TIMEOUT_MS = 10_000L
    }
}
