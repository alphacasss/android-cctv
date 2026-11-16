package id.cctv.monitor

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomnavigation.BottomNavigationView
import id.cctv.monitor.control.ControlRepository
import id.cctv.monitor.control.RestoreReport
import id.cctv.monitor.control.BulkResult
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

    private lateinit var pagerCameras: ViewPager2
    private lateinit var tvPageIndicator: TextView
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerProgress: ProgressBar
    private lateinit var btnRestoreAll: MaterialButton
    private lateinit var btnTurnOffAll: MaterialButton
    private lateinit var btnTurnOnAll: MaterialButton
    private lateinit var btnTurnOffAllRtsp: MaterialButton

    private var playerManager: PlayerManager? = null
    private val tiles = LinkedHashMap<Int, Tile>()
    private var camerasById: Map<Int, Camera> = emptyMap()
    private val playerStates = HashMap<Int, PlayerManager.State>()
    private var lastConfigRev = -1
    private var restoring = false
    private var remoteSettings = RemoteSettings()
    private var ipChangedRegistered = false

    private companion object {
        const val CAMERAS_PER_PAGE = 6
    }

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

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.nav_history -> true
                else -> false
            }
        }

        pagerCameras = findViewById(R.id.pagerCameras)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        banner = findViewById(R.id.bannerRestore)
        bannerText = findViewById(R.id.tvRestoreText)
        bannerProgress = findViewById(R.id.progressRestore)
        btnRestoreAll = findViewById(R.id.btnRestoreAll)
        btnRestoreAll.setOnClickListener { confirmRestoreAll() }

        btnTurnOffAll = findViewById(R.id.btnTurnOffAll)
        btnTurnOffAll.setOnClickListener { confirmTurnOffAll() }
        btnTurnOnAll = findViewById(R.id.btnTurnOnAll)
        btnTurnOnAll.setOnClickListener { confirmTurnOnAll() }
        btnTurnOffAllRtsp = findViewById(R.id.btnTurnOffAllRtsp)
        btnTurnOffAllRtsp.setOnClickListener { confirmTurnOffAllRtsp() }

        pagerCameras.offscreenPageLimit = 1
        pagerCameras.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
            }
        })

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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.nav_history -> {
                true
            }
            else -> false
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            applyConfigIfNeeded()
            startAllCameras()
            PublicIp.refreshAsync(prefs)
            ipChangedRegistered = true
            PublicIp.onIpChanged = { PublicIp.refreshAsync(prefs); renderCameras() }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "onStart error", e)
        }
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

        val manager = PlayerManager(this, prefs)
        manager.listener = this
        playerManager = manager

        val cameras = prefs.cameras().sortedBy { it.id }
        camerasById = cameras.associateBy { it.id }

        buildCameraPages(cameras, manager)
        updateBanner()
    }

    private fun buildCameraPages(cameras: List<Camera>, manager: PlayerManager) {
        val pages = if (cameras.isEmpty()) listOf(emptyList()) else cameras.chunked(CAMERAS_PER_PAGE)
        val pageViews = pages.map { pageCams -> createPageView(pageCams, manager) }

        pagerCameras.adapter = object : RecyclerView.Adapter<PageHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
                // Not used; pages are pre-created to keep VLC surfaces attached.
                val view = layoutInflater.inflate(R.layout.page_camera_grid, parent, false)
                return PageHolder(view)
            }

            override fun getItemCount(): Int = pageViews.size

            override fun onBindViewHolder(holder: PageHolder, position: Int) {
                val page = pageViews[position]
                val container = holder.itemView as? ViewGroup ?: return
                if (page.parent !== container) {
                    (page.parent as? ViewGroup)?.removeView(page)
                    container.removeAllViews()
                    container.addView(page)
                }
            }
        }
        pagerCameras.setCurrentItem(0, false)
        updatePageIndicator(0)
        pagerCameras.post { adjustPagerHeightToFit() }
    }

    private fun createPageView(pageCameras: List<Camera>, manager: PlayerManager): View {
        val page = layoutInflater.inflate(R.layout.page_camera_grid, pagerCameras, false) as GridLayout
        val rows = if (pageCameras.isEmpty()) 1 else kotlin.math.ceil(pageCameras.size / 3.0).toInt().coerceAtLeast(1)
        page.columnCount = 3
        page.rowCount = rows
        pageCameras.forEachIndexed { index, camera ->
            val tileView = layoutInflater.inflate(R.layout.item_camera, page, false)
            val row = index / 3
            val col = index % 3
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(col, 1, 1f)
                rowSpec = GridLayout.spec(row, 1, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            page.addView(tileView, params)

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
        return page
    }

    private fun updatePageIndicator(position: Int) {
        val count = pagerCameras.adapter?.itemCount ?: 0
        if (count <= 1) {
            tvPageIndicator.visibility = View.GONE
        } else {
            tvPageIndicator.visibility = View.VISIBLE
            tvPageIndicator.text = "Halaman ${position + 1} / $count  •  Geser untuk kamera lain"
        }
    }

    private fun adjustPagerHeightToFit() {
        val w = pagerCameras.width
        if (w <= 0) return
        val total = camerasById.size
        val rows = if (total <= 0) 1 else kotlin.math.min(2, kotlin.math.ceil(total.coerceAtMost(CAMERAS_PER_PAGE) / 3.0).toInt()).coerceAtLeast(1)
        // For mixed pages (e.g. 6 + 3 cameras), keep height for up to 2 rows so page 1 fits;
        // page 2 will have fewer rows aligned at top with consistent pager height.
        val maxRows = if (total > CAMERAS_PER_PAGE) 2 else rows
        val hPad = pagerCameras.paddingLeft + pagerCameras.paddingRight
        val colWidth = ((w - hPad) / 3f)
        val tileWidth = (colWidth - dp(8)).coerceAtLeast(dp(60).toFloat())
        val tileHeight = tileWidth * 9f / 16f
        val desired = (tileHeight * maxRows + dp(8) * maxRows + dp(2)).toInt().coerceAtLeast(dp(100))
        val lp = pagerCameras.layoutParams
        if (lp.height != desired) {
            lp.height = desired
            pagerCameras.layoutParams = lp
        }
    }

    private class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

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


    // ---------------- aksi bulk: matikan / nyalakan semua kamera ----------------

    private fun confirmTurnOffAll() {
        val enabled = camerasById.values.count { it.enabled }
        MaterialAlertDialogBuilder(this)
            .setTitle("Matikan semua kamera?")
            .setMessage(
                "Semua $enabled kamera yang aktif akan dihentikan sekaligus. " +
                    "Kamera yang dimatikan akan berhenti mengirim video ke grid."
            )
            .setPositiveButton("Matikan semua") { _, _ -> startTurnOffAll() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmTurnOnAll() {
        val enabled = camerasById.values.count { it.enabled }
        val blocked = camerasById.values.count { camera ->
            camera.enabled && prefs.flags(camera.id).blocked
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Nyalakan kembali semua kamera?")
            .setMessage(
                "Semua $enabled kamera akan dimulai ulang. " +
                    "$blocked kamera saat ini dalam status mati."
            )
            .setPositiveButton("Nyalakan semua") { _, _ -> startTurnOnAll() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun startTurnOffAll() {
        if (restoring) return
        restoring = true
        btnTurnOffAll.isEnabled = false
        btnTurnOnAll.isEnabled = false
        btnTurnOffAllRtsp.isEnabled = false
        bannerProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val report = try {
                repository.setAllCamerasOff(camerasById.values.toList())
            } catch (e: Exception) {
                BulkResult(listOf(e.message ?: "Gagal mematikan."))
            }
            restoring = false
            btnTurnOffAll.isEnabled = true
            btnTurnOnAll.isEnabled = true
            btnTurnOffAllRtsp.isEnabled = true
            bannerProgress.visibility = View.GONE

            for (camera in camerasById.values) {
                val flags = prefs.flags(camera.id)
                if (!camera.enabled || flags.blocked) {
                    playerManager?.stop(camera.id)
                    playerStates[camera.id] = PlayerManager.State.STOPPED
                }
                render(camera, flags)
            }
            updateBanner()

            val body = if (report.ok) report.summary else report.errors.joinToString("\n") { "• $it" }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(if (report.ok) "Semua kamera dimatikan" else "Pemutaran sebagian berhasil dihentikan")
                .setMessage(body)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun startTurnOnAll() {
        if (restoring) return
        restoring = true
        btnTurnOffAll.isEnabled = false
        btnTurnOnAll.isEnabled = false
        btnTurnOffAllRtsp.isEnabled = false
        bannerProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val report = try {
                repository.setAllCamerasOn(camerasById.values.toList())
            } catch (e: Exception) {
                BulkResult(listOf(e.message ?: "Gagal menyalakan."))
            }
            restoring = false
            btnTurnOffAll.isEnabled = true
            btnTurnOnAll.isEnabled = true
            btnTurnOffAllRtsp.isEnabled = true
            bannerProgress.visibility = View.GONE

            for (camera in camerasById.values) {
                val flags = prefs.flags(camera.id)
                render(camera, flags)
                onCameraChanged(camera.id, false)
            }
            updateBanner()

            val body = if (report.ok) report.summary else report.errors.joinToString("\n") { "• $it" }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(if (report.ok) "Semua kamera dinyalakan" else "Pemutaran sebagian berhasil dimulai")
                .setMessage(body)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun confirmTurnOffAllRtsp() {
        val targets = camerasById.values.count { it.enabled && !it.isNvr }
        MaterialAlertDialogBuilder(this)
            .setTitle("Matikan semua RTSP?")
            .setMessage(
                "Layanan RTSP akan dimatikan pada $targets kamera aktif (non-NVR). " +
                    "Kamera NVR tidak diubah untuk menghindari gangguan pada semua kanal."
            )
            .setPositiveButton("Matikan RTSP") { _, _ -> startTurnOffAllRtsp() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun startTurnOffAllRtsp() {
        if (restoring) return
        restoring = true
        btnTurnOffAll.isEnabled = false
        btnTurnOnAll.isEnabled = false
        btnTurnOffAllRtsp.isEnabled = false
        bannerProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val report = try {
                repository.setAllRtspOff(camerasById.values.toList())
            } catch (e: Exception) {
                BulkResult(listOf(e.message ?: "Gagal mematikan RTSP."))
            }
            restoring = false
            btnTurnOffAll.isEnabled = true
            btnTurnOnAll.isEnabled = true
            btnTurnOffAllRtsp.isEnabled = true
            bannerProgress.visibility = View.GONE

            for (camera in camerasById.values) {
                val flags = prefs.flags(camera.id)
                render(camera, flags)
                if (flags.blocked) {
                    playerManager?.stop(camera.id)
                    playerStates[camera.id] = PlayerManager.State.STOPPED
                }
            }
            updateBanner()

            val body = if (report.ok) {
                "RTSP semua kamera (non-NVR) berhasil dimatikan."
            } else {
                report.errors.joinToString("\n") { "• $it" }
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(if (report.ok) "RTSP dimatikan" else "Sebagian RTSP gagal dimatikan")
                .setMessage(body)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
