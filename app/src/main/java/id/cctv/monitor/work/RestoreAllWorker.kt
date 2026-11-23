package id.cctv.monitor.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import id.cctv.monitor.CctvApp
import id.cctv.monitor.control.ControlRepository

class RestoreAllWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CctvApp
        val prefs = app.prefs
        val store = app.store
        if (!prefs.autoRestore) return Result.success()
        if (!store.hasAny() && !prefs.anyFlagSet()) return Result.success()

        // Gunakan singleton cloud aplikasi supaya restore privacy mask tetap
        // punya rute passthrough walau worker berjalan tanpa Activity.
        val repository = ControlRepository(prefs, store, app.cloudRepo, app.cloudAuth)
        val report = repository.restoreAll(prefs.cameras())
        prefs.lastRestoreSummary = report.summary
        prefs.lastRestoreAt = System.currentTimeMillis()
        return Result.success()
    }
}
