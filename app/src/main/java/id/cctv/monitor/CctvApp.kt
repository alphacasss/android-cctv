package id.cctv.monitor

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.BackupStore
import id.cctv.monitor.net.PublicIp
import id.cctv.monitor.work.RestoreScheduler

class CctvApp : Application() {

    lateinit var prefs: AppPrefs
        private set
    lateinit var store: BackupStore
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        store = BackupStore(this)

        // Segarkan alamat publik agar mode luar jaringan siap saat dibutuhkan.
        PublicIp.refreshAsync(prefs)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Aplikasi kembali terlihat: batalkan pemulihan otomatis.
                RestoreScheduler.cancel(this@CctvApp)
            }

            override fun onStop(owner: LifecycleOwner) {
                // Aplikasi ditinggalkan: jadwalkan pemulihan bila ada yang perlu dibersihkan.
                if (prefs.autoRestore && (prefs.anyFlagSet() || store.hasAny())) {
                    RestoreScheduler.schedule(this@CctvApp, prefs.restoreDelaySeconds)
                }
            }
        })
    }
}
