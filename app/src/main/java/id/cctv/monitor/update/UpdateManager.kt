package id.cctv.monitor.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import id.cctv.monitor.BuildConfig
import id.cctv.monitor.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Orkestrasi cek rilis, jadwal, dan antrean unduh. */
object UpdateManager {

    const val WORK_NAME = "cctv_update"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    sealed class CheckResult {
        object UpToDate : CheckResult()
        data class Available(val release: ReleaseInfo) : CheckResult()
        object Failed : CheckResult()
    }

    /** Murni: dipakai UI/test tanpa jaringan. */
    fun decide(release: ReleaseInfo?, current: String): CheckResult = when {
        release == null -> CheckResult.Failed
        ReleaseParser.isNewer(release.version, current) -> CheckResult.Available(release)
        else -> CheckResult.UpToDate
    }

    suspend fun check(context: Context, manual: Boolean): CheckResult =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val release = UpdateRepository.latest()
            AppPrefs(app).lastUpdateCheckAt = System.currentTimeMillis()
            val result = decide(release, BuildConfig.VERSION_NAME)
            if (!manual && result is CheckResult.Available) {
                enqueue(app, result.release, manual = false)
            }
            result
        }

    /** Antrekan unduh. Otomatis hanya lewat jaringan tidak terukur (Wi-Fi). */
    fun enqueue(context: Context, release: ReleaseInfo, manual: Boolean) {
        val constraints = Constraints.Builder()
            .apply { if (!manual) setRequiredNetworkType(NetworkType.UNMETERED) }
            .build()
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(constraints)
            .setInputData(
                androidx.work.Data.Builder()
                    .putString(UpdateWorker.KEY_URL, release.apkUrl)
                    .putString(UpdateWorker.KEY_VERSION, release.version)
                    .putString(UpdateWorker.KEY_SHA256, release.sha256)
                    .putBoolean(UpdateWorker.KEY_MANUAL, manual)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Dipanggil sekali saat aplikasi dibuka. Tidak memblokir UI. */
    fun maybeCheckOnLaunch(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val prefs = AppPrefs(app)
            if (!prefs.autoUpdate) return@launch
            if (System.currentTimeMillis() - prefs.lastUpdateCheckAt < CHECK_INTERVAL_MS) {
                return@launch
            }
            check(app, manual = false)
        }
    }
}
