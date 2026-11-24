package id.cctv.monitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomnavigation.BottomNavigationView
import id.cctv.monitor.away.AwayService
import id.cctv.monitor.cloud.CloudCameraImporter
import id.cctv.monitor.cloud.StreamEncryptionManager
import id.cctv.monitor.control.ControlRepository
import id.cctv.monitor.control.RestoreReport
import id.cctv.monitor.control.BulkResult
import id.cctv.monitor.data.ActivitySummary
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.BackupStore
import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.CameraFlags
import id.cctv.monitor.data.DayNight
import id.cctv.monitor.data.RemoteSettings
import id.cctv.monitor.motion.MotionMonitor
import id.cctv.monitor.motion.ObjectFilter
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.NetworkType
import id.cctv.monitor.net.PublicIp
import id.cctv.monitor.player.PlayerManager
import id.cctv.monitor.ui.CameraControlSheet
import id.cctv.monitor.ui.CloudBindingSheet
import id.cctv.monitor.update.UpdateManager
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), PlayerManager.Listener {

    private lateinit var prefs: AppPrefs
    private lateinit var store: BackupStore
    private lateinit var repository: ControlRepository

    private lateinit var pagerCameras: ViewPager2
    private lateinit var tvPageIndicator: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var tvTotalCameras: TextView
    private lateinit var tvActivityInfo: TextView
    private lateinit var tvActivityLog: TextView
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerProgress: ProgressBar
    private lateinit var btnRestoreAll: MaterialButton
    private lateinit var btnTurnOffAll: MaterialButton
    private lateinit var btnTurnOnAll: MaterialButton
    private lateinit var btnTurnOffAllRtsp: MaterialButton
    private lateinit var btnAway: MaterialButton
    private lateinit var tvAwayStatus: TextView

    private var playerManager: PlayerManager? = null
    private val tiles = LinkedHashMap<Int, Tile>()
    private var camerasById: Map<Int, Camera> = emptyMap()
    private val playerStates = HashMap<Int, PlayerManager.State>()
    private val playerMessages = HashMap<Int, String?>()
    private var lastConfigRev = -1
    private var restoring = false
    private var remoteSettings = RemoteSettings()
    private var ipChangedRegistered = false

    private companion object {
        const val CAMERAS_PER_PAGE = 4
        const val GRID_COLUMNS = 2
        const val REQ_NOTIF = 9001
    }

    private data class Tile(
        val root: View,
        val video: VLCVideoLayout,
        val cloudSurface: android.view.SurfaceView,
        val name: TextView,
        val status: TextView,
        val overlay: TextView,
        val menu: ImageButton,
        val sensorChip: TextView,
        val motionBadge: TextView
    )

    private var cameraPages: List<List<Camera>> = emptyList()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pageStartToken = Any()
    private var startGeneration = 0

    private lateinit var motionMonitor: MotionMonitor
    private val motionActive = HashSet<Int>()
    private val motionLabels = HashMap<Int, String>()
    private val motionSnapshotOk = HashMap<Int, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CctvApp
        prefs = app.prefs
        store = app.store
        repository = ControlRepository(prefs, store, app.cloudRepo, app.cloudAuth)
        UpdateManager.maybeCheckOnLaunch(this)

        motionMonitor = MotionMonitor(
            prefs,
            lifecycleScope,
            object : MotionMonitor.Listener {
                override fun onMotion(cameraId: Int, active: Boolean, label: String?) {
                    if (active) {
                        motionActive.add(cameraId)
                        if (label != null) motionLabels[cameraId] = label else motionLabels.remove(cameraId)
                    } else {
                        motionActive.remove(cameraId)
                        motionLabels.remove(cameraId)
                    }
                    camerasById[cameraId]?.let { render(it, prefs.flags(cameraId)) }
                }

                override fun onSnapshotStatus(cameraId: Int, ok: Boolean) {
                    motionSnapshotOk[cameraId] = ok
                    camerasById[cameraId]?.let { render(it, prefs.flags(cameraId)) }
                }
            },
            intervalMs = 4_000L,
            // Deteksi gerakan sisi aplikasi berjalan di semua kamera yang terlihat,
            // tidak bergantung pada sensor bawaan kamera.
            onlyMotionEnabled = false,
            // Hanya manusia/hewan/kendaraan yang dianggap gerakan.
            objectFilter = if (app.prefs.objectFilter) ObjectFilter(this) else null
        )

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
                R.id.nav_sdk -> {
                    startActivity(Intent(this, OfficialSdkActivity::class.java))
                    true
                }
                R.id.nav_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                else -> false
            }
        }

        pagerCameras = findViewById(R.id.pagerCameras)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle)
        tvTotalCameras = findViewById(R.id.tvTotalCameras)
        tvActivityInfo = findViewById(R.id.tvActivityInfo)
        tvActivityLog = findViewById(R.id.tvActivityLog)
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

        btnAway = findViewById(R.id.btnAway)
        tvAwayStatus = findViewById(R.id.tvAwayStatus)
        btnAway.setOnClickListener { toggleAway() }
        updateAwayUi()

        pagerCameras.offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
        pagerCameras.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
                // Ringankan RAM/CPU: putar hanya kamera di halaman yang terlihat.
                startVisiblePage(position)
                motionMonitor.updateCameras(cameraPages.getOrNull(position).orEmpty())
            }
        })

        supportFragmentManager.setFragmentResultListener(
            CameraControlSheet.REQUEST_KEY,
            this
        ) { _, bundle ->
            val id = bundle.getInt(CameraControlSheet.KEY_CAMERA_ID, -1)
            val refresh = bundle.getBoolean(CameraControlSheet.KEY_REFRESH, false)
            val activityOnly = bundle.getBoolean(CameraControlSheet.KEY_ACTIVITY_ONLY, false)
            if (activityOnly) {
                updateBanner()
                renderActivityLog()
                return@setFragmentResultListener
            }
            if (id > 0) onCameraChanged(id, refresh)
            updateBanner()
            renderActivityLog()
        }

        // Hasil impor otomatis kanal cloud: muat ulang grid tanpa menunggu
        // pengguna membuka sheet BIND.
        supportFragmentManager.setFragmentResultListener(
            CloudBindingSheet.REQUEST_KEY,
            this
        ) { _, bundle ->
            if (!bundle.getBoolean(CloudBindingSheet.KEY_REFRESH, false)) return@setFragmentResultListener
            applyConfigIfNeeded()
            startAllCameras()
        }
        renderActivityLog()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.nav_history -> {
                startActivity(Intent(this, HistoryActivity::class.java))
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
            refreshLanCodeIfNeeded()
            lifecycleScope.launch { StreamEncryptionManager.disableIfOnLan(prefs) }
            syncCloudIfNeeded()
            motionMonitor.updateCameras(cameraPages.getOrNull(pagerCameras.currentItem).orEmpty())
            motionMonitor.start()
            updateAwayUi()
            if (prefs.awayMode) AwayService.start(this)
            maybeSuggestAway()
            PublicIp.refreshAsync(prefs)
            ipChangedRegistered = true
            PublicIp.onIpChanged = { PublicIp.refreshAsync(prefs); renderCameras() }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "onStart error", e)
        }
    }

    override fun onStop() {
        PublicIp.onIpChanged = null
        motionMonitor.stop()
        playerManager?.stopAll()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        findViewById<BottomNavigationView>(R.id.bottomNav)?.selectedItemId = R.id.nav_monitor
        updateBanner()
        renderActivityLog()
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
        tvStatusSubtitle.text = "${cameras.size} kamera Hikvision di jaringan rumah"
        tvTotalCameras.text = "${cameras.size} kamera terhubung"
        updateBanner()
        renderActivityLog()
    }

    /**
     * Impor otomatis semua kanal cloud **sekali per proses** supaya setelah
     * login tidak ada lagi BIND per kamera. Bila gagal (mis. offline), percobaan
     * diulang saat layar dibuka lagi.
     */
    private fun syncCloudIfNeeded() {
        val app = application as CctvApp
        if (app.cloudImportAttempted) return
        val session = app.cloudAuth.session.value ?: return
        app.cloudImportAttempted = true
        lifecycleScope.launch {
            val result = runCatching {
                val devices = app.cloudRepo.syncDevices(session)
                val prepared = app.cloudRepo.prepareForImport(
                    session,
                    devices,
                    prefs.defaultUser,
                    prefs.defaultPass
                )
                CloudCameraImporter(prefs).import(prepared).changed
            }
            if (result.isFailure) {
                app.cloudImportAttempted = false
                return@launch
            }
            if (result.getOrDefault(false)) {
                applyConfigIfNeeded()
                startAllCameras()
            }
        }
    }

    /**
     * Segarkan kode verifikasi langsung dari NVR di LAN, sekali per proses.
     *
     * Saat ponsel berada di rumah, ISAPI perangkat adalah sumber kode paling
     * akurat: nilai yang tersimpan dari sinkronisasi cloud atau tebakan lama
     * yang salah ikut diperbaiki sebelum stream cloud dibuka. Hanya host yang
     * benar-benar satu subnet dengan ponsel yang dicoba.
     */
    private fun refreshLanCodeIfNeeded() {
        val app = application as CctvApp
        if (app.lanCodeAttempted) return
        val user = prefs.defaultUser
        val pass = prefs.defaultPass
        if (user.isBlank() || pass.isBlank()) return

        // Host NVR lokal → gabungan serial perangkat & kanal.
        val targets = LinkedHashMap<String, LinkedHashSet<String>>()
        for (camera in prefs.cameras()) {
            val device = camera.cloudDevice ?: continue
            if (!device.valid) continue
            val host = device.localIp.ifBlank { camera.ip }.trim()
            if (host.isBlank() || !EndpointResolver.isLocalSubnet(host)) continue
            val serials = targets.getOrPut(host) { LinkedHashSet() }
            serials.add(device.serial)
            serials.add(device.channelSerial)
            serials.add(device.cloudDeviceId.substringBefore('#'))
            serials.remove("")
        }
        if (targets.isEmpty()) return
        app.lanCodeAttempted = true
        lifecycleScope.launch {
            for ((host, serials) in targets) {
                val code = app.cloudRepo.refreshVerificationCodeFromLan(
                    host,
                    user,
                    pass,
                    serials.toList()
                )
                if (code.isBlank()) continue
                // Kode baru sudah tersimpan per serial. Mulai ulang stream agar
                // dekripsi memakai kunci yang benar, bukan kode lama di memori.
                playerManager?.stopAll()
                startAllCameras()
                break
            }
        }
    }

    private fun buildCameraPages(cameras: List<Camera>, manager: PlayerManager) {
        val pages = if (cameras.isEmpty()) listOf(emptyList()) else cameras.chunked(CAMERAS_PER_PAGE)
        cameraPages = pages
        // Impor cloud atau perubahan konfigurasi bisa selesai saat pengguna
        // sudah berpindah halaman; jangan paksa kembali ke halaman pertama.
        val active = pagerCameras.currentItem.coerceIn(0, pages.lastIndex)
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
        pagerCameras.setCurrentItem(active, false)
        updatePageIndicator(active)
        // Rebuild bisa terjadi saat layout belum selesai (mis. setelah aksi bulk
        // mengubah flag). Tunggu width tersedia supaya grid tidak collapse.
        pagerCameras.doOnLayout { adjustPagerHeightToFit() }
    }

    private fun createPageView(pageCameras: List<Camera>, manager: PlayerManager): View {
        val page = layoutInflater.inflate(R.layout.page_camera_grid, pagerCameras, false) as GridLayout
        val rows = if (pageCameras.isEmpty()) 1 else
            kotlin.math.ceil(pageCameras.size / GRID_COLUMNS.toDouble()).toInt().coerceAtLeast(1)
        page.columnCount = GRID_COLUMNS
        page.rowCount = rows
        pageCameras.forEachIndexed { index, camera ->
            val tileView = layoutInflater.inflate(R.layout.item_camera, page, false)
            val row = index / GRID_COLUMNS
            val col = index % GRID_COLUMNS
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
                cloudSurface = tileView.findViewById(R.id.cloudSurface),
                name = tileView.findViewById(R.id.tvCameraName),
                status = tileView.findViewById(R.id.tvStatus),
                overlay = tileView.findViewById(R.id.tvOverlay),
                menu = tileView.findViewById(R.id.btnMenu),
                sensorChip = tileView.findViewById(R.id.tvSensorChip),
                motionBadge = tileView.findViewById(R.id.tvMotionBadge)
            )
            tiles[camera.id] = tile

            tile.name.text = camera.displayName
            tile.root.alpha = if (camera.enabled) 1f else 0.55f
            tile.menu.setOnClickListener { showControls(camera.id) }
            tile.root.setOnClickListener { openFullscreen(camera.id) }
        }
        return page
    }

    /**
     * Pasang player hanya untuk kamera di halaman yang terlihat. Kamera di halaman
     * lain dilepas (surface + decoder dibebaskan) agar RAM/GPU tidak membengkak.
     */
    private fun attachVisiblePage(position: Int) {
        val manager = playerManager ?: return
        val visibleIds = cameraPages.getOrNull(position)?.map { it.id }?.toSet() ?: emptySet()
        for (camera in camerasById.values) {
            val tile = tiles[camera.id] ?: continue
            if (camera.id in visibleIds) {
                if (!manager.hasSlot(camera.id)) {
                    manager.attach(camera, tile.video, tile.cloudSurface)
                }
            } else if (manager.hasSlot(camera.id)) {
                manager.detach(camera.id)
                playerStates[camera.id] = PlayerManager.State.IDLE
                render(camera, prefs.flags(camera.id))
            }
        }
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
        if (w <= 0) {
            pagerCameras.post { adjustPagerHeightToFit() }
            return
        }
        val total = camerasById.size
        val rows = if (total <= 0) 1 else
            kotlin.math.ceil(total.coerceAtMost(CAMERAS_PER_PAGE) / GRID_COLUMNS.toDouble()).toInt().coerceAtLeast(1)
        // Semua halaman memakai tinggi yang sama agar pager tidak berubah saat geser.
        val maxRows = GRID_COLUMNS
        val hPad = pagerCameras.paddingLeft + pagerCameras.paddingRight
        val colWidth = ((w - hPad) / GRID_COLUMNS.toFloat())
        val tileWidth = (colWidth - dp(8)).coerceAtLeast(dp(120).toFloat())
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
        // Untuk hemat RAM/CPU, mulai hanya kamera pada halaman yang terlihat.
        startVisiblePage(pagerCameras.currentItem)
    }

    private fun startVisiblePage(position: Int) {
        val manager = playerManager ?: return
        // Pasang surface hanya untuk halaman ini sebelum mulai memutar.
        attachVisiblePage(position)

        // Batalkan penjadwalan halaman sebelumnya.
        startGeneration++
        val generation = startGeneration
        uiHandler.removeCallbacksAndMessages(pageStartToken)

        val visible = cameraPages.getOrNull(position).orEmpty()
        val visibleIds = visible.map { it.id }.toSet()

        // Hentikan kamera di halaman lain supaya tidak ada decoder yang jalan diam-diam.
        for (camera in camerasById.values) {
            if (camera.id !in visibleIds) {
                manager.stop(camera.id)
                motionActive.remove(camera.id)
                playerStates[camera.id] = PlayerManager.State.IDLE
                render(camera, prefs.flags(camera.id))
            }
        }

        // Mulai bertahap (staggered): 1 kamera langsung, sisanya diberi jeda agar
        // UI tidak tersendat saat banyak decoder dimulai bersamaan.
        visible.forEachIndexed { index, camera ->
            val flags = prefs.flags(camera.id)
            val canPlay = camera.enabled && !flags.blocked && hasCredentials(camera)
            if (!canPlay) {
                manager.stop(camera.id)
                playerStates[camera.id] = PlayerManager.State.STOPPED
                render(camera, flags)
                return@forEachIndexed
            }
            playerStates[camera.id] = PlayerManager.State.LOADING
            render(camera, flags)

            val start = Runnable {
                if (startGeneration == generation) {
                    val c = camerasById[camera.id] ?: return@Runnable
                    val f = prefs.flags(c.id)
                    if (c.enabled && !f.blocked && hasCredentials(c)) manager.play(c, false)
                }
            }
            if (index == 0) {
                start.run()
            } else {
                uiHandler.postAtTime(
                    start,
                    pageStartToken,
                    SystemClock.uptimeMillis() + index * 300L
                )
            }
        }
    }

    private fun onCameraChanged(cameraId: Int, forceRefresh: Boolean) {
        // Impor cloud / perubahan daftar kamera mengganti seluruh grid.
        if (prefs.configRev != lastConfigRev) {
            applyConfigIfNeeded()
            startAllCameras()
            return
        }
        val camera = camerasById[cameraId] ?: return
        // Sensor gerak bisa baru diubah dari sheet: segarkan target pemantauan.
        motionMonitor.updateCameras(cameraPages.getOrNull(pagerCameras.currentItem).orEmpty())
        val flags = prefs.flags(cameraId)
        val manager = playerManager ?: return
        val visibleIds = cameraPages.getOrNull(pagerCameras.currentItem)?.map { it.id }?.toSet() ?: emptySet()
        val isVisible = cameraId in visibleIds
        if (isVisible && !manager.hasSlot(cameraId)) {
            tiles[cameraId]?.let { manager.attach(camera, it.video, it.cloudSurface) }
        }

        when {
            !isVisible -> {
                manager.stop(cameraId)
                playerStates[cameraId] = PlayerManager.State.IDLE
                render(camera, flags)
            }
            !camera.enabled || flags.blocked || !hasCredentials(camera) -> {
                manager.stop(cameraId)
                playerStates[cameraId] = PlayerManager.State.STOPPED
                render(camera, flags)
            }
            forceRefresh -> manager.play(camera, false)
            !manager.isActive(cameraId) -> manager.play(camera, false)
            else -> render(camera, flags)
        }
        renderActivityLog()
    }

    private fun hasCredentials(camera: Camera): Boolean =
        prefs.effectiveUser(camera).isNotBlank() ||
            camera.cloudDevice?.valid == true

    private fun showControls(cameraId: Int) {
        CameraControlSheet.newInstance(cameraId)
            .show(supportFragmentManager, "camera-control-$cameraId")
    }

    private fun openFullscreen(cameraId: Int) {
        val intent = Intent(this, FullscreenPlayerActivity::class.java)
        intent.putExtra(FullscreenPlayerActivity.EXTRA_CAMERA_ID, cameraId)
        startActivity(intent)
    }

    // ---------------- Mode Away ----------------

    private fun toggleAway() {
        if (prefs.awayMode) {
            AwayService.stop(this)
            prefs.awayMode = false
            updateAwayUi()
            Toast.makeText(this, "Mode Away dimatikan.", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIF
            )
            return
        }
        enableAway()
    }

    private fun enableAway() {
        prefs.awayMode = true
        AwayService.start(this)
        updateAwayUi()
        Toast.makeText(
            this,
            "Mode Away aktif. Gerakan akan dikirim sebagai notifikasi.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateAwayUi() {
        if (!::btnAway.isInitialized) return
        val on = prefs.awayMode
        val active = camerasById.values.count { it.enabled }
        btnAway.text = if (on) "MATIKAN MODE AWAY" else "AKTIFKAN MODE AWAY"
        btnAway.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (on) R.color.danger else R.color.accent)
        )
        tvAwayStatus.text = if (on) {
            "Aktif — memantau gerakan $active kamera. Foto gerakan tersimpan di galeri " +
                "dan dikirim sebagai notifikasi."
        } else {
            "Nonaktif. Aktifkan saat meninggalkan rumah: aplikasi memantau gerakan kamera " +
                "dan mengirim notifikasi berisi foto."
        }
    }

    /** Tawarkan Mode Away sekali setiap 12 jam bila terdeteksi di luar jaringan rumah. */
    private fun maybeSuggestAway() {
        if (prefs.awayMode || camerasById.isEmpty()) return
        val cameras = camerasById.values.filter { it.enabled }
        if (cameras.isEmpty() || !hasCredentials(cameras.first())) return
        val now = System.currentTimeMillis()
        if (now - prefs.awayPromptAt < 12 * 60 * 60 * 1000L) return
        val offLan = cameras.count { EndpointResolver.resolve(it, remoteSettings).offLan }
        if (offLan == 0) return
        prefs.awayPromptAt = now
        MaterialAlertDialogBuilder(this)
            .setTitle("Aktifkan Mode Away?")
            .setMessage(
                "Anda terdeteksi di luar jaringan rumah. Aktifkan Mode Away agar aplikasi " +
                    "memantau gerakan kamera dan mengirim notifikasi berisi foto."
            )
            .setPositiveButton("Aktifkan") { _, _ -> toggleAway() }
            .setNegativeButton("Nanti", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                enableAway()
            } else {
                Toast.makeText(
                    this,
                    "Izin notifikasi diperlukan agar Mode Away bisa memberi tahu gerakan.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------- status tile ----------------

    override fun onPlayerState(cameraId: Int, state: PlayerManager.State) {
        val camera = camerasById[cameraId] ?: return
        playerStates[cameraId] = state
        if (state == PlayerManager.State.PLAYING) playerMessages.remove(cameraId)
        render(camera, prefs.flags(cameraId))
    }

    override fun onPlayerMessage(cameraId: Int, message: String?) {
        android.util.Log.d("MainActivity", "pesan cam=$cameraId: $message")
        playerMessages[cameraId] = message
        camerasById[cameraId]?.let { render(it, prefs.flags(cameraId)) }
    }

    private fun render(camera: Camera, flags: CameraFlags) {
        val tile = tiles[camera.id] ?: return
        val state = playerStates[camera.id] ?: PlayerManager.State.IDLE
        val endpoint = EndpointResolver.resolve(camera, remoteSettings)

        val chips = ArrayList<String>()
        chips.add("SUB ${camera.streamId(false)}")
        if (camera.isNvr) chips.add("NVR")
        if (camera.cloudDevice?.valid == true) chips.add("CLOUD")
        if (endpoint.remote) chips.add(if (endpoint.automatic) "LUAR AUTO" else "LUAR")
        if (prefs.blockRtspOnCellular && NetworkType.isCellular(this)) chips.add("DATA")
        if (flags.black) chips.add("HITAM")
        if (flags.streamOff) chips.add("STREAM OFF")
        if (flags.rtspOff) chips.add("RTSP OFF")
        if (flags.nvrOff) chips.add("NVR OFF")
        if (flags.nightBoost) chips.add("NIGHT+")
        if (!camera.enabled) chips.add("NONAKTIF")
        val dayNight = camera.dayNight
        if (dayNight != DayNight.AUTO) {
            chips.add(when (dayNight) {
                DayNight.AUTO -> "OTOMATIS"
                DayNight.NIGHT -> "MODE MALAM"
                DayNight.DAY -> "NORMAL"
            })
        }
        tile.status.text = chips.joinToString(" • ")
        tile.status.visibility = View.VISIBLE

        // Indikator sensor gerak & badge GERAK di atas gambar kamera.
        tile.sensorChip.visibility = if (camera.enabled) View.VISIBLE else View.GONE
        val snapshotOk = motionSnapshotOk[camera.id]
        when {
            snapshotOk == false -> {
                tile.sensorChip.text = "SNAP GAGAL"
                tile.sensorChip.setTextColor(ContextCompat.getColor(this, R.color.danger))
            }
            camera.movementDetect -> {
                tile.sensorChip.text = "SENSOR ON"
                tile.sensorChip.setTextColor(ContextCompat.getColor(this, R.color.accent))
            }
            else -> {
                tile.sensorChip.text = "DETEKSI ON"
                tile.sensorChip.setTextColor(ContextCompat.getColor(this, R.color.accent))
            }
        }
        val motion = motionActive.contains(camera.id)
        tile.motionBadge.visibility = if (motion) View.VISIBLE else View.GONE
        if (motion) {
            tile.motionBadge.text = motionLabels[camera.id] ?: "GERAK"
            tile.motionBadge.animate().cancel()
            tile.motionBadge.alpha = 1f
            tile.motionBadge.animate().alpha(0.35f).setDuration(450).withEndAction {
                if (motionActive.contains(camera.id)) {
                    tile.motionBadge.animate().alpha(1f).setDuration(450).start()
                }
            }.start()
        }

        val overlay = when {
            !camera.enabled -> "NONAKTIF"
            !hasCredentials(camera) -> "Isi kredensial di Setelan"
            !endpoint.remote && endpoint.offLan && camera.cloudDevice?.valid != true ->
                if (prefs.remoteAuto) {
                    "Luar jaringan — IP publik rumah belum terdeteksi"
                } else {
                    "Luar jaringan — aktifkan akses luar di Setelan"
                }
            flags.streamOff || flags.rtspOff || flags.nvrOff -> "DIMATIKAN"
            camera.dayNight == DayNight.NIGHT -> "MODE MALAM"
            camera.dayNight == DayNight.DAY -> "NORMAL"
            flags.black -> "HITAM"
            state == PlayerManager.State.LOADING -> "Memuat…"
            state == PlayerManager.State.ERROR ->
                playerMessages[camera.id]?.takeIf { it.isNotBlank() }
                    ?: "Gagal — mencoba lagi"
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

        val nightLayer = tile.root.findViewById<View>(R.id.nightLayer)
        nightLayer.visibility = if (camera.dayNight == DayNight.NIGHT) View.VISIBLE else View.GONE
    }

    /** Render semua kamera di grid. */
    private fun renderCameras() {
        for (camera in camerasById.values) {
            render(camera, prefs.flags(camera.id))
        }
    }

    // ---------------- banner & pemulihan ----------------

    /** Ringkasan status lokal dan empat aksi terbaru pada card "Aktifitas & Status". */
    private fun renderActivityLog() {
        if (!::tvActivityInfo.isInitialized || !::tvActivityLog.isInitialized) return
        val cameras = camerasById.values.toList()
        val total = cameras.count { it.enabled }
        val off = cameras.count { camera ->
            camera.enabled && prefs.flags(camera.id).let { flags ->
                flags.blocked || flags.rtspOff || flags.nvrOff || flags.streamOff
            }
        }
        tvActivityInfo.text = ActivitySummary.format(total, off, store.count())

        val entries = prefs.activityLog().take(4)
        tvActivityLog.text = if (entries.isEmpty()) {
            "Belum ada aktivitas tercatat."
        } else {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            entries.joinToString("\n") { entry ->
                "${timeFormat.format(Date(entry.at))}  ${entry.title} — ${entry.detail}"
            }
        }
    }

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
            renderActivityLog()
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
                "Semua $enabled kamera akan dihitamkan lewat Hik-Connect dan pemutaran " +
                    "di HP ini dihentikan. Firmware NVR tidak mendukung matikan kanal, " +
                    "jadi privacy mask dipakai supaya HP lain/NVR juga melihat layar hitam."
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
                "Semua $enabled kamera akan dimulai ulang dan privacy mask dipulihkan. " +
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
            val targets = camerasById.values.toList()
            val local = try {
                repository.setAllCamerasOff(targets)
            } catch (e: Exception) {
                BulkResult(listOf(e.message ?: "Gagal mematikan."))
            }
            val cloud = try {
                repository.setAllBlack(targets, on = true)
            } catch (e: Exception) {
                BulkResult(listOf(e.message ?: "Gagal menghitamkan lewat cloud."))
            }
            val report = BulkResult(local.errors + cloud.errors)
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
            renderActivityLog()

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
            val targets = camerasById.values.toList()
            val local = try {
                repository.setAllCamerasOn(targets)
            } catch (e: Exception) {
                BulkResult(listOf(e.message ?: "Gagal menyalakan."))
            }
            val cloud = try {
                repository.setAllBlack(targets, on = false)
            } catch (e: Exception) {
                BulkResult(listOf(e.message ?: "Gagal memulihkan privacy mask lewat cloud."))
            }
            val report = BulkResult(local.errors + cloud.errors)
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
            renderActivityLog()

            val body = if (report.ok) report.summary else report.errors.joinToString("\n") { "• $it" }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(if (report.ok) "Semua kamera dinyalakan" else "Pemutaran sebagian berhasil dimulai")
                .setMessage(body)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun confirmTurnOffAllRtsp() {
        val targets = camerasById.values.count { it.enabled }
        MaterialAlertDialogBuilder(this)
            .setTitle("Matikan semua RTSP?")
            .setMessage(
                "Stream akan diputus pada $targets kamera aktif. Kanal NVR diputus satu per satu " +
                    "lewat sumber IP kamera, lalu bisa dipulihkan lewat NYALAKAN SEMUA."
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
            renderActivityLog()

            val body = if (report.ok) {
                "Stream/RTSP semua kamera berhasil dimatikan."
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
