package id.cctv.monitor.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Pemulihan otomatis dijadwalkan saat aplikasi masuk background. WorkManager
 * tetap menjalankannya walau proses aplikasi sudah dimatikan.
 */
object RestoreScheduler {
    private const val WORK_NAME = "cctv-auto-restore"

    fun schedule(context: Context, delaySeconds: Int) {
        val request = OneTimeWorkRequestBuilder<RestoreAllWorker>()
            .setInitialDelay(delaySeconds.toLong().coerceIn(10, 3600), TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}
