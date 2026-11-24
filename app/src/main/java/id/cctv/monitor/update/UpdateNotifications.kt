package id.cctv.monitor.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import id.cctv.monitor.R

/** Notifikasi progres unduh dan notifikasi "siap dipasang". */
object UpdateNotifications {

    const val CHANNEL_ID = "cctv_update"
    const val PROGRESS_ID = 9100
    const val READY_ID = 9101
    const val EXTRA_PATH = "path"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Pembaruan aplikasi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progres unduh dan pemberitahuan APK siap dipasang"
                setShowBadge(false)
            }
        )
    }

    fun progress(context: Context, percent: Int): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("Mengunduh pembaruan")
            .setContentText(if (percent < 0) "Menyiapkan unduhan" else "$percent%")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)

    fun ready(context: Context, file: java.io.File, version: String): NotificationCompat.Builder {
        val intent = Intent().apply {
            setClassName(context.packageName, "id.cctv.monitor.update.UpdateInstallActivity")
            putExtra(EXTRA_PATH, file.absolutePath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            READY_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("Pembaruan siap dipasang")
            .setContentText("Versi $version telah diunduh. Ketuk untuk memasang.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    fun notifyProgress(context: Context, percent: Int) {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        manager.notify(PROGRESS_ID, progress(context, percent).build())
    }

    fun notifyReady(context: Context, file: java.io.File, version: String) {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        manager.notify(READY_ID, ready(context, file, version).build())
    }
}
