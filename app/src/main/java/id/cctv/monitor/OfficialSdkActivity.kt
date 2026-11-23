package id.cctv.monitor

import android.os.Bundle
import android.view.SurfaceView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import id.cctv.monitor.cloud.stream.CloudVideoPlayer
import id.cctv.monitor.cloud.stream.OfficialSdkPlayer
import id.cctv.monitor.cloud.stream.OfficialSdkRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Menu uji SDK resmi Hik-Connect.
 *
 * Memakai [OfficialSdkPlayer] yang sama dengan Monitor, sehingga hanya ada satu
 * DexClassLoader/native library di proses. Membuka activity ini tidak boleh
 * mengganggu stream yang sedang berjalan.
 */
class OfficialSdkActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var surface: SurfaceView
    private var player: OfficialSdkPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sdk_cloud)
        status = findViewById(R.id.sdkStatus)
        surface = findViewById(R.id.sdkSurface)

        val app = application as CctvApp
        val all = app.prefs.cameras()
        val camera = all.firstOrNull {
            it.cloudDevice?.valid == true && it.cloudDevice?.streamUrl.isNullOrBlank()
        }
        if (camera == null) {
            status.text = "Tidak ada kamera cloud yang terikat."
            return
        }
        val device = camera.cloudDevice ?: return
        status.text = "Menyiapkan SDK resmi…"

        val sdkPlayer = OfficialSdkPlayer(
            surfaceView = surface,
            sourceProvider = { mainStream ->
                val key = listOf(
                    "${device.serial}|${device.channel}|$mainStream",
                    "${device.serial}|${device.channel}|false"
                ).firstOrNull { app.cloudSourceCache.containsKey(it) }
                app.cloudSourceCache[key] ?: app.cloudRepo.streamSource(
                    app.cloudAuth.session.value,
                    device,
                    mainStream
                )
            },
            listener = object : CloudVideoPlayer.Listener {
                override fun onState(state: CloudVideoPlayer.State, message: String?) {
                    status.text = when (state) {
                        CloudVideoPlayer.State.CONNECTING -> "Menghubungkan SDK resmi…"
                        CloudVideoPlayer.State.PLAYING ->
                            "SDK streaming berjalan • ch=${device.channel}"
                        CloudVideoPlayer.State.ERROR -> "SDK error: ${message.orEmpty()}"
                        CloudVideoPlayer.State.STOPPED -> "SDK dihentikan."
                        CloudVideoPlayer.State.IDLE -> "SDK siap."
                    }
                }

                override fun onVideoSize(width: Int, height: Int) = Unit
            },
            channelSerialProvider = { device.channelSerial }
        )
        player = sdkPlayer
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) {
                OfficialSdkRuntime.initialize(applicationContext)
            }
            if (!ready) {
                status.text = "SDK gagal: ${OfficialSdkRuntime.lastError().orEmpty()}"
                return@launch
            }
            sdkPlayer.start(mainStream = true)
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
