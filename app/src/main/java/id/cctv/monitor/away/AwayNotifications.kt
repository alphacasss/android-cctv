package id.cctv.monitor.away

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import id.cctv.monitor.MainActivity
import id.cctv.monitor.R

/** Kanal notifikasi Mode Away + notifikasi deteksi gerakan. */
object AwayNotifications {

    const val CHANNEL_ONGOING = "cctv_away"
    const val CHANNEL_ALERT = "cctv_alert"
    const val ONGOING_ID = 4101

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                "Mode Away",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status pemantauan gerakan saat meninggalkan rumah"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "Deteksi gerakan",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pemberitahuan saat kamera mendeteksi gerakan"
                enableVibration(true)
            }
        )
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun ongoing(context: Context, cameraCount: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("Mode Away aktif")
            .setContentText("Memantau gerakan pada $cameraCount kamera")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
            .build()

    fun motion(
        context: Context,
        cameraName: String,
        bitmap: Bitmap?,
        notificationId: Int,
        label: String? = null
    ): Notification {
        val title = if (label != null) {
            "$label terdeteksi: $cameraName"
        } else {
            "Gerakan terdeteksi: $cameraName"
        }
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_camera)
                .setContentTitle(title)
            .setContentText("Ketuk untuk membuka CCTV Monitor")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context))
        if (bitmap != null) {
            builder.setLargeIcon(bitmap)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .setSummaryText(cameraName)
            )
        }
        return builder.build()
    }
}
