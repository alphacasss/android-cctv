package id.cctv.monitor.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File

/** Mengunduh APK di latar belakang dan menyiapkan notifikasi pemasangan. */
class UpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)
        val version = inputData.getString(KEY_VERSION)
        if (url.isNullOrBlank() || version.isNullOrBlank()) return Result.failure()

        val sha256 = inputData.getString(KEY_SHA256)?.takeIf { it.isNotBlank() }
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        UpdateNotifications.ensureChannel(applicationContext)
        val progressNotification = UpdateNotifications.progress(applicationContext, -1).build()
        val foreground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                UpdateNotifications.PROGRESS_ID,
                progressNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(UpdateNotifications.PROGRESS_ID, progressNotification)
        }
        setForegroundAsync(foreground)

        val dir = File(applicationContext.getExternalFilesDir("Updates"), ".")
        val dest = File(dir, "cctv-monitor-$version.apk")
        try {
            UpdateRepository.download(url, dest) { percent ->
                UpdateNotifications.notifyProgress(applicationContext, percent)
            }
            if (sha256 != null && !UpdateRepository.sha256(dest).equals(sha256, ignoreCase = true)) {
                dest.delete()
                return Result.failure()
            }
            UpdateNotifications.notifyReady(applicationContext, dest, version)
            return Result.success(
                Data.Builder()
                    .putString(KEY_PATH, dest.absolutePath)
                    .putBoolean(KEY_MANUAL, manual)
                    .build()
            )
        } catch (_: Exception) {
            if (dest.exists()) dest.delete()
            return Result.retry()
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_VERSION = "version"
        const val KEY_SHA256 = "sha256"
        const val KEY_MANUAL = "manual"
        const val KEY_PATH = "path"
    }
}
