package id.cctv.monitor.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import id.cctv.monitor.control.ControlRepository
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.BackupStore

class RestoreAllWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPrefs(applicationContext)
        val store = BackupStore(applicationContext)
        if (!prefs.autoRestore) return Result.success()
        if (!store.hasAny() && !prefs.anyFlagSet()) return Result.success()

        val repository = ControlRepository(prefs, store)
        val report = repository.restoreAll(prefs.cameras())
        prefs.lastRestoreSummary = report.summary
        prefs.lastRestoreAt = System.currentTimeMillis()
        return Result.success()
    }
}
