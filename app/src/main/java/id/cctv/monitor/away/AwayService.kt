package id.cctv.monitor.away

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import id.cctv.monitor.CctvApp
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.MediaSaver
import id.cctv.monitor.motion.MotionMonitor
import id.cctv.monitor.motion.ObjectFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap

/**
 * Layanan latar Mode Away: memantau snapshot semua kamera, menyimpan foto saat
 * ada gerakan, dan mengirim notifikasi. Berjalan sebagai foreground service
 * agar tidak dimatikan sistem saat aplikasi ditutup.
 */
class AwayService : Service(), MotionMonitor.Listener {

    companion object {
        private const val TAG = "AwayService"
        private const val ACTION_START = "id.cctv.monitor.action.AWAY_START"
        private const val ACTION_STOP = "id.cctv.monitor.action.AWAY_STOP"
        private const val ALERT_THROTTLE_MS = 45_000L

        fun start(context: Context) {
            val intent = Intent(context, AwayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val app = context.applicationContext as? CctvApp
            app?.prefs?.awayMode = false
            context.stopService(Intent(context, AwayService::class.java))
        }
    }

    private lateinit var prefs: AppPrefs
    private lateinit var monitor: MotionMonitor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastAlert = ConcurrentHashMap<Int, Long>()

    override fun onCreate() {
        super.onCreate()
        val app = application as CctvApp
        prefs = app.prefs
        AwayNotifications.ensureChannels(this)

        val cameras = prefs.cameras().filter { it.enabled }
        val notification = AwayNotifications.ongoing(this, cameras.size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AwayNotifications.ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(AwayNotifications.ONGOING_ID, notification)
        }

        monitor = MotionMonitor(
            prefs = prefs,
            scope = scope,
            listener = this,
            intervalMs = 6_000L,
            width = 160,
            onlyMotionEnabled = false,
            objectFilter = if (prefs.objectFilter) ObjectFilter(this) else null,
            onMotionSnapshot = { cameraId, jpeg, label -> onSnapshot(cameraId, jpeg, label) }
        )
        monitor.updateCameras(cameras)
        monitor.start()
        Log.i(TAG, "Mode Away aktif untuk ${cameras.size} kamera")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.awayMode = true
        // Segarkan daftar kamera (mungkin berubah dari Setelan).
        monitor.updateCameras(prefs.cameras().filter { it.enabled })
        return START_STICKY
    }

    override fun onMotion(cameraId: Int, active: Boolean, label: String?) {
        // Badge di UI ditangani MainActivity; service hanya butuh snapshot.
    }

    private fun onSnapshot(cameraId: Int, jpeg: ByteArray, label: String?) {
        val now = System.currentTimeMillis()
        val previous = lastAlert[cameraId] ?: 0L
        if (now - previous < ALERT_THROTTLE_MS) return
        lastAlert[cameraId] = now

        val camera = prefs.cameras().firstOrNull { it.id == cameraId } ?: return
        val file = try {
            MediaSaver.saveSnapshot(this, camera.displayName, jpeg)
        } catch (t: Throwable) {
            Log.w(TAG, "simpan snapshot gagal: ${t.message}")
            null
        }

        val bitmap: Bitmap? = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (t: Throwable) {
            null
        }
        val notification = AwayNotifications.motion(
            this,
            camera.displayName,
            bitmap,
            4200 + camera.id,
            label
        )
        try {
            NotificationManagerCompat.from(this).notify(4200 + camera.id, notification)
        } catch (e: SecurityException) {
            // Izin notifikasi belum diberikan; foto tetap tersimpan.
            Log.w(TAG, "notifikasi ditolak: ${e.message}")
        }
        if (file != null) Log.i(TAG, "snapshot gerakan disimpan: ${file.name}")
    }

    override fun onDestroy() {
        monitor.stop()
        scope.cancel()
        Log.i(TAG, "Mode Away berhenti")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
