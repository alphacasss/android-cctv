package id.cctv.monitor.motion

import android.os.Handler
import android.os.Looper
import android.util.Log
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.Camera
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.SnapshotClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Deteksi gerakan ringan berbasis perbandingan snapshot.
 *
 * Hanya berjalan saat aplikasi di depan dan hanya untuk kamera di halaman yang
 * terlihat, sehingga tidak membebani RAM/CPU/baterai. Snapshot diambil dari
 * substream (160px) sekitar 4 detik sekali.
 */
class MotionMonitor(
    private val prefs: AppPrefs,
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val intervalMs: Long = INTERVAL_MS,
    private val width: Int = 160,
    private val onlyMotionEnabled: Boolean = true,
    /** Filter objek (manusia/hewan/kendaraan). Null = deteksi piksel biasa. */
    private val objectFilter: ObjectFilter? = null,
    private val onMotionSnapshot: ((cameraId: Int, jpeg: ByteArray, label: String?) -> Unit)? = null
) {

    interface Listener {
        /**
         * [active] true saat gerakan berarti terdeteksi; false saat reda.
         * [label] ORANG / HEWAN / KENDARAAN bila filter objek aktif.
         */
        fun onMotion(cameraId: Int, active: Boolean, label: String? = null)

        /** Status pengambilan snapshot: false = kamera menolak/gagal. */
        fun onSnapshotStatus(cameraId: Int, ok: Boolean) {}
    }

    private companion object {
        const val TAG = "MotionMonitor"
        const val INTERVAL_MS = 3_000L
        const val HOLD_MS = 8_000L
        const val FAIL_BACKOFF_MS = 30_000L
        const val LOG_INTERVAL_MS = 10_000L
        const val CLASSIFY_WIDTH = 640
        const val CLASSIFY_INTERVAL_MS = 12_000L
    }

    private var job: Job? = null
    private var targets: List<Camera> = emptyList()
    private val previous = HashMap<Int, ByteArray>()
    private val active = HashMap<Int, Boolean>()
    private val labels = HashMap<Int, String?>()
    private val lastEvent = HashMap<Int, Long>()
    private val retryAfter = HashMap<Int, Long>()
    private val snapshotOk = HashMap<Int, Boolean>()
    private val lastLog = HashMap<Int, Long>()
    private val lastClassify = HashMap<Int, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var paused = false

    fun updateCameras(cameras: List<Camera>) {
        targets = cameras.filter {
            it.enabled &&
                (!onlyMotionEnabled || it.movementDetect) &&
                !prefs.flags(it.id).blocked &&
                prefs.effectiveUser(it).isNotBlank()
        }
        if (Log.isLoggable(TAG, Log.INFO)) {
            val ids = targets.map { it.id }.toSet()
            val skipped = cameras.filter { it.id !in ids }.joinToString {
                "${it.displayName}(aktif=${it.enabled}, sensor=${it.movementDetect}, " +
                    "blokir=${prefs.flags(it.id).blocked}, user=${prefs.effectiveUser(it).isNotBlank()})"
            }
            Log.i(TAG, "pantau ${targets.size} kamera: ${targets.joinToString { it.displayName }}; dilewati: $skipped")
        }
        // Buang riwayat kamera yang tidak lagi dipantau.
        val ids = targets.map { it.id }.toSet()
        previous.keys.retainAll(ids)
        active.keys.retainAll(ids)
        lastEvent.keys.retainAll(ids)
        retryAfter.keys.retainAll(ids)
        snapshotOk.keys.retainAll(ids)
        lastLog.keys.retainAll(ids)
        lastClassify.keys.retainAll(ids)
        labels.keys.retainAll(ids)
    }

    fun start() {
        if (job != null) return
        paused = false
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (paused) {
                    delay(500)
                    continue
                }
                for (camera in targets) {
                    if (!isActive || paused) break
                    scan(camera)
                    delay(120)
                }
                expire()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        paused = true
        job?.cancel()
        job = null
        previous.clear()
        for (id in active.keys.toList()) {
            if (active[id] == true) {
                active[id] = false
                notifyMain(id, false, null)
            }
        }
        active.clear()
        labels.clear()
        lastEvent.clear()
        retryAfter.clear()
        snapshotOk.clear()
        lastLog.clear()
        lastClassify.clear()
    }

    private suspend fun scan(camera: Camera) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < (retryAfter[camera.id] ?: 0L)) return
        // Di luar rumah alamat IP kamera tidak bisa dijangkap lewat ISAPI.
        // Jangan membuang request dan jangan menampilkan "SNAP GAGAL":
        // live cloud sudah berjalan lewat relay.
        if (!EndpointResolver.isLocalSubnet(camera.ip)) {
            reportSnapshot(camera.id, true)
            previous.remove(camera.id)
            return
        }
        val bytes = SnapshotClient.fetch(camera, prefs, main = false, width = width)
        if (bytes == null) {
            reportSnapshot(camera.id, false)
            // Kamera mati/tidak mendukung snapshot: jangan spam jaringan.
            retryAfter[camera.id] = now + FAIL_BACKOFF_MS
            previous.remove(camera.id)
            if (lastLog[camera.id]?.let { now - it < LOG_INTERVAL_MS } != true) {
                lastLog[camera.id] = now
                Log.w(TAG, "snapshot gagal: ${camera.displayName}")
            }
            return
        }
        reportSnapshot(camera.id, true)

        val frame = MotionMath.grayscale(bytes) ?: return
        val prev = previous[camera.id]
        previous[camera.id] = frame
        if (prev == null) return
        val stats = MotionMath.diff(prev, frame) ?: return

        val now2 = android.os.SystemClock.elapsedRealtime()
        val shouldLog = now2 - (lastLog[camera.id] ?: 0L) >= LOG_INTERVAL_MS
        if (stats.moving) {
            // Pixel diff hanya pemicu. Bila filter objek tersedia, verifikasi
            // bahwa ada manusia/hewan/kendaraan sebelum dianggap gerakan.
            val isActive = active[camera.id] == true
            val classifyDue = objectFilter != null &&
                (!isActive || now2 - (lastClassify[camera.id] ?: 0L) >= CLASSIFY_INTERVAL_MS)
            var accepted = true
            var label: String? = labels[camera.id]
            var detail = ""
            var shot = bytes
            if (classifyDue) {
                lastClassify[camera.id] = now2
                val verdict = verifyObject(camera)
                accepted = verdict.accepted
                label = verdict.label
                detail = verdict.detail
                if (verdict.jpeg != null) shot = verdict.jpeg
                if (accepted) labels[camera.id] = label
            }
            if (!accepted) {
                if (shouldLog) {
                    lastLog[camera.id] = now2
                    Log.i(
                        TAG,
                        "%s: gerak diabaikan (bukan objek target) mean=%.2f blok=%d/%d; %s".format(
                            camera.displayName,
                            stats.mean,
                            stats.activeBlocks,
                            stats.totalBlocks,
                            detail
                        )
                    )
                }
                return
            }
            if (shouldLog) {
                lastLog[camera.id] = now2
                Log.i(
                    TAG,
                    "%s: GERAK%s mean=%.2f blok=%d/%d max=%.1f piksel=%d/%d; %s".format(
                        camera.displayName,
                        if (label != null) " ($label)" else "",
                        stats.mean,
                        stats.activeBlocks,
                        stats.totalBlocks,
                        stats.maxBlockMean,
                        stats.changedPixels,
                        stats.totalPixels,
                        detail
                    )
                )
            }
            if (markActive(camera.id, label)) {
                onMotionSnapshot?.invoke(camera.id, shot, label)
            }
        } else if (shouldLog && Log.isLoggable(TAG, Log.DEBUG)) {
            lastLog[camera.id] = now2
            Log.d(
                TAG,
                "%s: diam mean=%.2f blok=%d/%d max=%.1f".format(
                    camera.displayName,
                    stats.mean,
                    stats.activeBlocks,
                    stats.totalBlocks,
                    stats.maxBlockMean
                )
            )
        }
    }

    private fun reportSnapshot(cameraId: Int, ok: Boolean) {
        if (snapshotOk[cameraId] == ok) return
        snapshotOk[cameraId] = ok
        mainHandler.post { listener.onSnapshotStatus(cameraId, ok) }
    }

    /**
     * Ambil snapshot lebih besar saat diff terpicu lalu jalankan detektor objek.
     */
    private data class Verdict(
        val accepted: Boolean,
        val label: String?,
        val detail: String,
        /** Snapshot resolusi lebih tinggi untuk simpan/notifikasi, bila ada. */
        val jpeg: ByteArray?
    )

    private suspend fun verifyObject(camera: Camera): Verdict {
        val filter = objectFilter
            ?: return Verdict(true, null, "filter objek nonaktif", null)
        val result = SnapshotClient.fetchResult(
            camera,
            prefs,
            main = false,
            width = CLASSIFY_WIDTH
        )
        val jpeg = result.bytes ?: SnapshotClient.fetch(camera, prefs, main = true)
        if (jpeg == null) {
            // Jangan hilangkan gerakan nyata hanya karena verifikasi gagal.
            return Verdict(true, null, "verifikasi gagal: ${result.error}", null)
        }
        val detection = filter.detect(jpeg)
        if (!detection.available) {
            return Verdict(true, null, "model tidak tersedia (${detection.raw})", jpeg)
        }
        return Verdict(detection.isTarget, detection.label, detection.raw, jpeg)
    }

    private fun markActive(cameraId: Int, label: String?): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val wasActive = active[cameraId] == true
        lastEvent[cameraId] = now
        if (!wasActive) {
            active[cameraId] = true
            labels[cameraId] = label
            notifyMain(cameraId, true, label)
        }
        return !wasActive
    }

    private fun expire() {
        val now = android.os.SystemClock.elapsedRealtime()
        for ((id, value) in active.entries.toList()) {
            if (value && now - (lastEvent[id] ?: 0L) > HOLD_MS) {
                active[id] = false
                labels.remove(id)
                lastClassify.remove(id)
                notifyMain(id, false, null)
            }
        }
    }

    private fun notifyMain(cameraId: Int, isActive: Boolean, label: String?) {
        mainHandler.post { listener.onMotion(cameraId, isActive, label) }
    }
}
