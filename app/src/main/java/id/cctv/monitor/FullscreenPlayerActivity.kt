package id.cctv.monitor

import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.CameraFlags
import id.cctv.monitor.motion.MotionMonitor
import id.cctv.monitor.motion.ObjectFilter
import id.cctv.monitor.net.NetworkType
import id.cctv.monitor.player.PlayerManager
import id.cctv.monitor.ui.CameraControlSheet
import org.videolan.libvlc.util.VLCVideoLayout

class FullscreenPlayerActivity : AppCompatActivity(), PlayerManager.Listener {

    companion object {
        const val EXTRA_CAMERA_ID = "camera_id"
    }

    private lateinit var prefs: AppPrefs
    private var camera: Camera? = null
    private var playerManager: PlayerManager? = null
    private var currentState = PlayerManager.State.IDLE

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var cloudSurface: SurfaceView
    private lateinit var overlay: TextView
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var motionBadge: TextView
    private var motionMonitor: MotionMonitor? = null
    private var motionOn = false
    private var motionLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CctvApp
        prefs = app.prefs

        val cameraId = intent.getIntExtra(EXTRA_CAMERA_ID, -1)
        camera = prefs.cameras().firstOrNull { it.id == cameraId }
        if (camera == null) {
            finish()
            return
        }

        setContentView(R.layout.activity_fullscreen)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        videoLayout = findViewById(R.id.videoFullscreen)
        cloudSurface = findViewById(R.id.cloudSurfaceFullscreen)
        overlay = findViewById(R.id.tvFullscreenOverlay)
        status = findViewById(R.id.tvFullscreenStatus)
        title = findViewById(R.id.tvFullscreenTitle)
        topBar = findViewById(R.id.topBarFullscreen)
        motionBadge = findViewById(R.id.tvFullscreenMotion)
        val root = findViewById<View>(R.id.rootFullscreen)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(dp(8), bars.top + dp(8), dp(8), dp(8))
            status.setPadding(dp(8), dp(8), dp(8), bars.bottom + dp(8))
            insets
        }

        val cam = camera ?: return
        title.text = "${cam.displayName} • ${cam.ip}"
        findViewById<ImageButton>(R.id.btnFullscreenClose).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnFullscreenControls).setOnClickListener {
            CameraControlSheet.newInstance(cam.id)
                .show(supportFragmentManager, "camera-control-fullscreen")
        }

        val manager = PlayerManager(this, prefs)
        manager.listener = this
        playerManager = manager
        manager.attach(cam, videoLayout, cloudSurface)

        supportFragmentManager.setFragmentResultListener(
            CameraControlSheet.REQUEST_KEY,
            this
        ) { _, bundle ->
            val id = bundle.getInt(CameraControlSheet.KEY_CAMERA_ID, -1)
            val refresh = bundle.getBoolean(CameraControlSheet.KEY_REFRESH, false)
            if (id == cam.id) applyFlags(refresh)
        }

        motionMonitor = MotionMonitor(
            prefs,
            lifecycleScope,
            object : MotionMonitor.Listener {
                override fun onMotion(cameraId: Int, active: Boolean, label: String?) {
                    if (cameraId == cam.id) showMotion(active, label)
                }
            },
            intervalMs = 4_000L,
            onlyMotionEnabled = false,
            objectFilter = if (prefs.objectFilter) ObjectFilter(this) else null
        ).apply { updateCameras(listOf(cam)) }
    }

    override fun onStart() {
        super.onStart()
        applyFlags(forcePlay = false)
        motionMonitor?.start()
    }

    override fun onStop() {
        motionMonitor?.stop()
        showMotion(false)
        playerManager?.stop(camera?.id ?: -1)
        super.onStop()
    }

    override fun onDestroy() {
        playerManager?.release()
        playerManager = null
        super.onDestroy()
    }

    override fun onPlayerState(cameraId: Int, state: PlayerManager.State) {
        if (cameraId != camera?.id) return
        currentState = state
        updateOverlay()
    }

    override fun onPlayerMessage(cameraId: Int, message: String?) {
        if (cameraId != camera?.id) return
        if (!message.isNullOrBlank()) {
            overlay.text = message
            overlay.visibility = View.VISIBLE
        }
    }

    private fun applyFlags(forcePlay: Boolean) {
        val cam = camera ?: return
        val manager = playerManager ?: return
        val flags = prefs.flags(cam.id)
        status.text = buildString {
            append("MAIN ${cam.streamId(true)}")
            if (cam.isNvr) append(" • NVR")
            if (flags.black) append(" • HITAM")
            if (flags.streamOff) append(" • STREAM OFF")
            if (flags.rtspOff) append(" • RTSP OFF")
            if (flags.nvrOff) append(" • NVR OFF")
            if (prefs.blockRtspOnCellular &&
                NetworkType.isCellular(this@FullscreenPlayerActivity)
            ) append(" • DATA")
            if (!cam.enabled) append(" • NONAKTIF")
        }
        when {
            !cam.enabled || flags.blocked -> {
                manager.stop(cam.id)
                currentState = PlayerManager.State.STOPPED
                overlay.text = "DIMATIKAN"
                overlay.visibility = View.VISIBLE
            }
            prefs.effectiveUser(cam).isBlank() && cam.cloudDevice?.valid != true -> {
                manager.stop(cam.id)
                currentState = PlayerManager.State.STOPPED
                overlay.text = "Isi kredensial di Setelan"
                overlay.visibility = View.VISIBLE
            }
            forcePlay || !manager.isActive(cam.id) -> {
                manager.play(cam, mainStream = true)
                updateOverlay()
            }
            else -> updateOverlay()
        }
    }

    private fun updateOverlay() {
        val cam = camera ?: return
        val flags: CameraFlags = prefs.flags(cam.id)
        val text = when {
            flags.black -> "HITAM"
            currentState == PlayerManager.State.LOADING -> "Memuat…"
            currentState == PlayerManager.State.ERROR -> "Gagal — mencoba lagi"
            currentState == PlayerManager.State.STOPPED -> "Berhenti"
            currentState == PlayerManager.State.IDLE -> "Menunggu"
            else -> null
        }
        if (text == null) {
            overlay.visibility = View.GONE
        } else {
            overlay.text = text
            overlay.visibility = View.VISIBLE
        }
    }

    private fun showMotion(active: Boolean, label: String? = null) {
        motionOn = active
        motionLabel = if (active) label else null
        motionBadge.animate().cancel()
        if (!active) {
            motionBadge.visibility = View.GONE
            return
        }
        motionBadge.text = if (label != null) "$label TERDETEKSI" else "GERAKAN TERDETEKSI"
        motionBadge.visibility = View.VISIBLE
        motionBadge.alpha = 1f
        pulse()
    }

    /** Badge GERAK berdenyut selama gerakan masih terdeteksi. */
    private fun pulse() {
        if (!motionOn) return
        motionBadge.animate()
            .alpha(0.35f)
            .setDuration(450)
            .withEndAction {
                if (!motionOn) return@withEndAction
                motionBadge.animate()
                    .alpha(1f)
                    .setDuration(450)
                    .withEndAction { pulse() }
                    .start()
            }
            .start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
