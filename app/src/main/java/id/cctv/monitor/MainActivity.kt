package id.cctv.monitor

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import id.cctv.monitor.control.ControlRepository
import id.cctv.monitor.control.RestoreReport
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.BackupStore
import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.CameraFlags
import id.cctv.monitor.data.RemoteSettings
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.PublicIp
import id.cctv.monitor.player.PlayerManager
import id.cctv.monitor.ui.CameraControlSheet
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout

class MainActivity : AppCompatActivity(), PlayerManager.Listener {

    private lateinit var prefs: AppPrefs
    private lateinit var store: BackupStore
    private lateinit var repository: ControlRepository

    private lateinit var grid: GridLayout
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerProgress: ProgressBar
    private lateinit var btnRestoreAll: MaterialButton

    private var playerManager: PlayerManager? = null
    private val tiles = LinkedHashMap<Int, Tile>()
    private var camerasById: Map<Int, Camera> = emptyMap()
    private val playerStates = HashMap<Int, PlayerManager.State>()
    private var lastConfigRev = -1
    private var restoring = false
    private var remoteSettings = RemoteSettings()
    private var ipChangedRegistered = false

    private data class Tile(
        val root: View,
        val video: VLCVideoLayout,
        val name: TextView,
        val status: TextView,
        val overlay: TextView,
        val menu: ImageButton
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CctvApp
        prefs = app.prefs
        store = app.store
        repository = ControlRepository(prefs, store)

        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        grid = findViewById(R.id.gridCameras)
        banner = findViewById(R.id.bannerRestore)
        bannerText = findViewById(R.id.tvRestoreText)
        bannerProgress = findViewById(R.id.progressRestore)
        btnRestoreAll = findViewById(R.id.btnRestoreAll)
        btnRestoreAll.setOnClickListener { confirmRestoreAll() }

        supportFragmentManager.setFragmentResultListener(
            CameraControlSheet.REQUEST_KEY,
            this
        ) { _, bundle ->
            val id = bundle.getInt(CameraControlSheet.KEY_CAMERA_ID, -1)
            val refresh = bundle.getBoolean(CameraControlSheet.KEY_REFRESH, false)
            if (id > 0) onCameraChanged(id, refresh)
            updateBanner()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_restore_exit -> {
                confirmRestoreExit()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        applyConfigIfNeeded()
        startAllCameras()
        PublicIp.refreshAsync(prefs)
        ipChangedRegistered = true
        PublicIp.onIpChanged = { PublicIp.refreshAsync(prefs); renderCameras() }
    }

    override fun onStop() {
        PublicIp.onIpChanged = null
        playerManager?.stopAll()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateBanner()
    }

    override fun onDestroy() {
        playerManager?.release()
        playerManager = null
        super.onDestroy()
    }

    // ---------------- konfigurasi & tile ----------------

    private fun applyConfigIfNeeded() {
        if (prefs.configRev == lastConfigRev && playerManager != null) return
        lastConfigRev = prefs.configRev
        remoteSettings = prefs.remoteSettings()

        playerManager?.release()
        tiles.clear()
        playerStates.clear()
        grid.removeAllViews()

        val manager = PlayerManager(this, prefs)
        manager.listener = this
        playerManager = manager

        val cameras = prefs.cameras().sortedBy { it.id }
        camerasById = cameras.associateBy { it.id }

        for (camera in cameras) {
            val tileView = layoutInflater.inflate(R.layout.item_camera, grid, false)
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(tileView, params)

            val tile = Tile(
                root = tileView,
                video = tileView.findViewById(R.id.videoLayout),
                name = tileView.findViewById(R.id.tvCameraName),
                status = tileView.findViewById(R.id.tvStatus),
                overlay = tileView.findViewById(R.id.tvOverlay),
                menu = tileView.findViewById(R.id.btnMenu)
            )
            tiles[camera.id] = tile

            tile.name.text = camera.displayName
            tile.root.alpha = if (camera.enabled) 1f else 0.55f
            tile.menu.setOnClickListener { showControls(camera.id) }
            tile.root.setOnClickListener { openFullscreen(camera.id) }

            manager.attach(camera, tile.video)
        }
        updateBanner()
    }

    private fun startAllCameras() {
        val manager = playerManager ?: return
        for (camera in camerasById.values.sortedBy { it.id }) {
            val flags = prefs.flags(camera.id)
            if (!camera.enabled) {
                manager.stop(camera.id)
                playerStates[camera.id] = PlayerManager.State.STOPPED
                render(camera, flags)
                continue
            }
            if (flags.blocked) {
                manager.stop(camera.id)
                playerStates[camera.id] = PlayerManager.State.STOPPED
                render(camera, flags)
                continue
            }
            if (!hasCredentials(camera)) {
                manager.stop(camera.id)
                playerStates[camera.id] = PlayerManager.State.STOPPED
                render(camera, flags)
                continue
            }
            playerStates[camera.id] = PlayerManager.State.LOADING
            render(camera, flags)
            manager.play(camera, false)
        }
    }

    private fun onCameraChanged(cameraId: Int, forceRefresh: Boolean) {
        val camera = camerasById[cameraId] ?: return
        val flags = prefs.flags(cameraId)
        val manager = playerManager ?: return

        when {
            !camera.enabled || flags.blocked -> {
                manager.stop(cameraId)
                playerStates[cameraId] = PlayerManager.State.STOPPED
                render(camera, flags)
            }
            !hasCredentials(camera) -> {
                manager.stop(cameraId)
                playerStates[cameraId] = PlayerManager.State.STOPPED
                render(camera, flags)
            }
            forceRefresh -> manager.play(camera, false)
            !manager.isActive(cameraId) -> manager.play(camera, false)
            else -> render(camera, flags)
        }
    }

    private fun hasCredentials(camera: Camera): Boolean =
        prefs.effectiveUser(camera).isNotBlank()

    private fun showControls(cameraId: Int) {
        CameraControlSheet.newInstance(cameraId)
            .show(supportFragmentManager, "camera-control-$cameraId")
    }

    private fun openFullscreen(cameraId: Int) {
        val intent = Intent(this, FullscreenPlayerActivity::class.java)
        intent.putExtra(FullscreenPlayerActivity.EXTRA_CAMERA_ID, cameraId)
        startActivity(intent)
    }

    // ---------------- status tile ----------------

    override fun onPlayerState(cameraId: Int, state: PlayerManager.State) {
        val camera = camerasById[cameraId] ?: return
        playerStates[cameraId] = state
        render(camera, prefs.flags(cameraId))
    }

    private fun render(camera: Camera, flags: CameraFlags) {
        val tile = tiles[camera.id] ?: return
        val state = playerStates[camera.id] ?: PlayerManager.State.IDLE
        val endpoint = EndpointResolver.resolve(camera, remoteSettings)

        val chips = ArrayList<String>()
        chips.add("SUB ${camera.streamId(false)}")
        if (camera.isNvr) chips.add("NVR")
        if (endpoint.remote) chips.add(if (endpoint.automatic) "LUAR AUTO" else "LUAR")
        if (flags.black) chips.add("HITAM")
        if (flags.streamOff) chips.add("STREAM OFF")
        if (flags.rtspOff) chips.add("RTSP OFF")
        if (flags.nvrOff) chips.add("NVR OFF")
        if (!camera.enabled) chips.add("NONAKTIF")
        tile.status.text = chips.joinToString(" • ")

        val overlay = when {
            !camera.enabled -> "NONAKTIF"
            !hasCredentials(camera) -> "Isi kredensial di Setelan"
            !endpoint.remote && endpoint.offLan ->
                if (prefs.remoteAuto) {
                    "Luar jaringan — IP publik rumah belum terdeteksi"
                } else {
                    "Luar jaringan — aktifkan akses luar di Setelan"
                }
            flags.streamOff || flags.rtspOff || flags.nvrOff -> "DIMATIKAN"
            flags.black -> "HITAM"
            state == PlayerManager.State.LOADING -> "Memuat…"
            state == PlayerManager.State.ERROR -> "Gagal — mencoba lagi"
            state == PlayerManager.State.STOPPED -> "Berhenti"
            state == PlayerManager.State.IDLE -> "Menunggu"
            else -> null
        }
        if (overlay == null) {
            tile.overlay.visibility = View.GONE
        } else {
            tile.overlay.text = overlay
            tile.overlay.visibility = View.VISIBLE
        }
    }

    /** Render semua kamera di grid. */
    private fun renderCameras() {
        for (camera in camerasById.values) {
            render(camera, prefs.flags(camera.id))
        }
    }

    // ---------------- banner & pemulihan ----------------

    private fun updateBanner() {
        val backups = store.count()
        val dirty = prefs.anyFlagSet() || backups > 0
        banner.visibility = if (dirty) View.VISIBLE else View.GONE
        if (dirty) {
            bannerText.text = if (backups > 0) {
                "Status kamera belum dipulihkan ($backups backup XML tersimpan). " +
                    "Pulihkan agar privacy mask, stream, RTSP, dan kanal NVR kembali normal."
            } else {
                "Status kamera belum dipulihkan. Pulihkan agar privacy mask, stream, RTSP, " +
                    "dan kanal NVR kembali normal."
            }
        }
    }

    private fun confirmRestoreAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Pulihkan semua kamera?")
            .setMessage(
                "Aplikasi akan membuka privacy mask, menyalakan stream, mengaktifkan RTSP, dan " +
                    "mengaktifkan kembali kanal NVR sesuai backup. Proses bisa memakan beberapa detik."
            )
            .setPositiveButton("Pulihkan") { _, _ -> startRestoreAll(showDialog = true) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmRestoreExit() {
        if (!prefs.anyFlagSet() && !store.hasAny()) {
            finishAffinity()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Pulihkan lalu keluar?")
            .setMessage("Status kamera akan dikembalikan normal sebelum aplikasi ditutup.")
            .setPositiveButton("Pulihkan & keluar") { _, _ ->
                startRestoreAll(showDialog = false) { finishAffinity() }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun startRestoreAll(showDialog: Boolean, onDone: (() -> Unit)? = null) {
        if (restoring) return
        restoring = true
        btnRestoreAll.isEnabled = false
        bannerProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val report = try {
                repository.restoreAll(camerasById.values.toList())
            } catch (e: Exception) {
                RestoreReport(listOf(e.message ?: "Gagal memulihkan."))
            }
            prefs.lastRestoreSummary = report.summary
            prefs.lastRestoreAt = System.currentTimeMillis()
            restoring = false
            btnRestoreAll.isEnabled = true
            bannerProgress.visibility = View.GONE
            updateBanner()
            startAllCameras()

            if (showDialog) {
                val body = if (report.ok) {
                    report.summary
                } else {
                    report.errors.joinToString("\n") { "• $it" }
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(if (report.ok) "Pemulihan selesai" else "Pemulihan sebagian")
                    .setMessage(body)
                    .setPositiveButton("OK", null)
                    .show()
            }
            onDone?.invoke()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
