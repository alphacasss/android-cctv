package id.cctv.monitor

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import id.cctv.monitor.cloud.CloudAuthManager
import id.cctv.monitor.cloud.CloudStreamSource
import id.cctv.monitor.cloud.HikVisionCloudRepository
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.BackupStore
import id.cctv.monitor.net.PublicIp
import id.cctv.monitor.work.RestoreScheduler

class CctvApp : Application() {

    lateinit var prefs: AppPrefs
        private set
    lateinit var store: BackupStore
        private set
    lateinit var cloudAuth: CloudAuthManager
    lateinit var cloudRepo: HikVisionCloudRepository

    /** Parameter relay cloud terakhir per kanal, dipakai menu SDK resmi. */
    val cloudSourceCache = java.util.concurrent.ConcurrentHashMap<String, CloudStreamSource>()

    /**
     * True bila impor otomatis kanal cloud sudah dicoba pada proses ini.
     * Mencegah sinkronisasi jaringan berulang setiap layar dibuka kembali.
     */
    @Volatile
    var cloudImportAttempted: Boolean = false

    /**
     * True bila penyegaran kode verifikasi dari NVR di LAN sudah dicoba pada
     * proses ini. Autentikasi ISAPI yang gagal berulang dapat mengunci akun,
     * jadi percobaan dibatasi satu kali per proses.
     */
    @Volatile
    var lanCodeAttempted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        store = BackupStore(this)
        cloudAuth = CloudAuthManager(this)
        cloudRepo = HikVisionCloudRepository(this, cloudAuth, prefs)

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
