package id.cctv.monitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import id.cctv.monitor.control.ControlRepository
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.BackupStore
import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.RemoteSettings
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.IsapiClient
import id.cctv.monitor.net.PublicIp
import id.cctv.monitor.net.isapiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var store: BackupStore
    private lateinit var repository: ControlRepository

    private lateinit var etUser: TextInputEditText
    private lateinit var etPass: TextInputEditText
    private lateinit var etRestoreDelay: TextInputEditText
    private lateinit var switchAutoRestore: MaterialSwitch
    private lateinit var containerCameras: LinearLayout
    private lateinit var tvRestoreInfo: TextView
    private lateinit var tvEncryption: TextView
    private lateinit var tvStreamEncryptionInfo: TextView
    private lateinit var btnDisableStreamEncryption: MaterialButton
    private lateinit var btnRestoreAllNow: MaterialButton
    private lateinit var etRemoteHostGlobal: TextInputEditText
    private lateinit var etRemoteHttpBase: TextInputEditText
    private lateinit var etRemoteRtspBase: TextInputEditText
    private lateinit var tilRemoteHostGlobal: TextInputLayout
    private lateinit var tilRemoteHttpBase: TextInputLayout
    private lateinit var tilRemoteRtspBase: TextInputLayout
    private lateinit var tvRemoteInfo: TextView
    private lateinit var tvRemotePreview: TextView
    private lateinit var btnRefreshIp: MaterialButton
    private lateinit var switchRemoteAuto: MaterialSwitch
    private lateinit var switchOfficialSdk: MaterialSwitch
    private lateinit var switchBlockRtspCellular: MaterialSwitch
    private lateinit var tvRemoteIp: TextView
    private lateinit var tvRemoteIpUpdated: TextView
    private lateinit var tvRemotePortInfo: TextView
    private lateinit var tvRemotePortTable: TextView
    private lateinit var switchRemoteVpn: MaterialSwitch
    private lateinit var tvRemoteVpnInfo: TextView
    private lateinit var tvRemoteNotice: TextView
    private var refreshingIp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CctvApp
        prefs = app.prefs
        store = app.store
        repository = ControlRepository(prefs, store, app.cloudRepo, app.cloudAuth)

        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarSettings)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etRestoreDelay = findViewById(R.id.etRestoreDelay)
        switchAutoRestore = findViewById(R.id.switchAutoRestore)
        containerCameras = findViewById(R.id.containerCameras)
        tvRestoreInfo = findViewById(R.id.tvRestoreInfo)
        tvEncryption = findViewById(R.id.tvEncryption)
        tvStreamEncryptionInfo = findViewById(R.id.tvStreamEncryptionInfo)
        btnDisableStreamEncryption = findViewById(R.id.btnDisableStreamEncryption)
        btnRestoreAllNow = findViewById(R.id.btnRestoreAllNow)
        etRemoteHostGlobal = findViewById(R.id.etRemoteHostGlobal)
        etRemoteHttpBase = findViewById(R.id.etRemoteHttpBase)
        etRemoteRtspBase = findViewById(R.id.etRemoteRtspBase)
        tvRemoteInfo = findViewById(R.id.tvRemoteInfo)
        tvRemotePreview = findViewById(R.id.tvRemotePreview)
        btnRefreshIp = findViewById(R.id.btnRefreshIp)
        tilRemoteHostGlobal = findViewById(R.id.tilRemoteHostGlobal)
        tilRemoteHttpBase = findViewById(R.id.tilRemoteHttpBase)
        tilRemoteRtspBase = findViewById(R.id.tilRemoteRtspBase)
        tvRemoteIp = findViewById(R.id.tvRemoteIp)
        tvRemoteIpUpdated = findViewById(R.id.tvRemoteIpUpdated)
        tvRemotePortInfo = findViewById(R.id.tvRemotePortInfo)
        tvRemotePortTable = findViewById(R.id.tvRemotePortTable)
        switchRemoteVpn = findViewById(R.id.switchRemoteVpn)
        tvRemoteVpnInfo = findViewById(R.id.tvRemoteVpnInfo)
        tvRemoteNotice = findViewById(R.id.tvRemoteNotice)
        switchRemoteAuto = findViewById(R.id.switchRemoteAuto)
        switchOfficialSdk = findViewById(R.id.switchOfficialSdk)
        switchBlockRtspCellular = findViewById(R.id.switchBlockRtspCellular)

        findViewById<MaterialButton>(R.id.btnSaveCred).setOnClickListener { saveCredentials() }
        findViewById<MaterialButton>(R.id.btnSaveRestore).setOnClickListener { saveRestoreSettings() }
        findViewById<MaterialButton>(R.id.btnSaveRemote).setOnClickListener {
            if (saveRemoteSettings(showToast = true)) {
                renderCameras()
            }
        }
        findViewById<MaterialButton>(R.id.btnAddCamera).setOnClickListener { addCamera() }
        btnRefreshIp.setOnClickListener { refreshPublicIp(force = true) }
        btnDisableStreamEncryption.setOnClickListener { disableStreamEncryption() }
        btnRestoreAllNow.setOnClickListener { confirmRestoreAll() }

        switchAutoRestore.setOnCheckedChangeListener { _, checked ->
            etRestoreDelay.isEnabled = checked
        }

        switchRemoteVpn.setOnCheckedChangeListener { _, checked ->
            prefs.vpnMode = checked
            updateRemoteVpnInfo()
            renderCameras()
        }

        switchOfficialSdk.setOnCheckedChangeListener { _, checked ->
            prefs.useOfficialSdk = checked
            Toast.makeText(
                this,
                "Jalur stream cloud akan dipakai saat pemutaran berikutnya.",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchBlockRtspCellular.setOnCheckedChangeListener { _, checked ->
            prefs.blockRtspOnCellular = checked
            Toast.makeText(
                this,
                if (checked) {
                    "RTSP akan dihentikan saat paket data seluler aktif."
                } else {
                    "RTSP boleh dipakai kembali saat paket data seluler aktif."
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        etUser.setText(prefs.defaultUser)
        etPass.setText(prefs.defaultPass)
        switchAutoRestore.isChecked = prefs.autoRestore
        etRestoreDelay.isEnabled = prefs.autoRestore
        etRestoreDelay.setText(prefs.restoreDelaySeconds.toString())
        switchRemoteAuto.isChecked = prefs.remoteAuto
        switchOfficialSdk.isChecked = prefs.useOfficialSdk
        switchBlockRtspCellular.isChecked = prefs.blockRtspOnCellular
        etRemoteHostGlobal.setText(prefs.remoteHost)
        etRemoteHttpBase.setText(prefs.remoteHttpBase.toString())
        etRemoteRtspBase.setText(prefs.remoteRtspBase.toString())
        switchRemoteVpn.isChecked = prefs.vpnMode
        updateRemoteInfo()
        updateRemoteVpnInfo()
        renderPortTable()
        renderCameras()
        updateRestoreInfo()
        updateEncryptionInfo()
        PublicIp.onIpChanged = { PublicIp.refreshAsync(prefs) }
        PublicIp.refreshAsync(prefs) {
            updateRemoteInfo()
            renderCameras()
            renderPortTable()
        }
    }

    override fun onPause() {
        saveRemoteSettings(showToast = false)
        applyRestoreSettings(silent = true)
        super.onPause()
    }

    private fun saveCredentials() {
        prefs.defaultUser = etUser.text?.toString()?.trim().orEmpty()
        prefs.defaultPass = etPass.text?.toString().orEmpty()
        prefs.bumpConfig()
        Toast.makeText(this, "Kredensial default disimpan.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Matikan "stream encryption" di NVR lewat ISAPI lokal. Setelah ini cloud
     * mengirim video jernih, sehingga dekode green/corrupt tidak terjadi lagi.
     * Kredensial kamera tetap dibaca dari Setelan, tidak ditulis ke source.
     */
    private fun disableStreamEncryption() {
        val nvr = prefs.cameras().firstOrNull { it.isNvr }
            ?: prefs.cameras().firstOrNull()
        if (nvr == null) {
            Toast.makeText(this, "Belum ada kamera/NVR yang dikonfigurasi.", Toast.LENGTH_LONG).show()
            return
        }
        val user = nvr.user.ifBlank { prefs.defaultUser }
        val pass = nvr.pass.ifBlank { prefs.defaultPass }
        if (user.isBlank()) {
            Toast.makeText(this, "Isi username kamera/NVR dulu.", Toast.LENGTH_LONG).show()
            return
        }
        btnDisableStreamEncryption.isEnabled = false
        tvStreamEncryptionInfo.text = "Menanyakan konfigurasi enkripsi ke ${nvr.ip}…"
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val client = IsapiClient("http://${nvr.ip}", user, pass)
                    val path = "/ISAPI/System/Network/EZVIZ"
                    val current = client.get(path)
                    if (current.code !in 200..299) return@runCatching isapiError(current)
                    if (!current.body.contains("streamEncrypteEnabled", ignoreCase = true)) {
                        return@runCatching "Perangkat tidak menyediakan opsi enkripsi stream."
                    }
                    val enabled = Regex(
                        "<streamEncrypteEnabled>\\s*true\\s*</streamEncrypteEnabled>",
                        RegexOption.IGNORE_CASE
                    ).containsMatchIn(current.body)
                    if (!enabled) return@runCatching "Enkripsi stream sudah nonaktif."
                    val updated = current.body.replace(
                        Regex(
                            "<streamEncrypteEnabled>\\s*true\\s*</streamEncrypteEnabled>",
                            RegexOption.IGNORE_CASE
                        ),
                        "<streamEncrypteEnabled>false</streamEncrypteEnabled>"
                    )
                    val put = client.put(path, updated)
                    if (put.code in 200..299) {
                        "Enkripsi stream dimatikan. Live cloud sekarang akan jernih."
                    } else {
                        isapiError(put)
                    }
                }.getOrElse { "Gagal: ${it.message ?: it::class.java.simpleName}" }
            }
            if (!isFinishing && !isDestroyed) {
                btnDisableStreamEncryption.isEnabled = true
                tvStreamEncryptionInfo.text = message
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                prefs.bumpConfig()
            }
        }
    }

    private fun saveRestoreSettings() {
        if (applyRestoreSettings(silent = false)) {
            Toast.makeText(this, "Pengaturan pemulihan disimpan.", Toast.LENGTH_SHORT).show()
        }
    }

    /** @return true bila delay valid. */
    private fun applyRestoreSettings(silent: Boolean): Boolean {
        prefs.autoRestore = switchAutoRestore.isChecked
        val seconds = etRestoreDelay.text?.toString()?.trim()?.toIntOrNull()
        return if (seconds == null || seconds !in 10..3600) {
            if (!silent) {
                etRestoreDelay.error = "Isi 10–3600 detik."
            } else {
                prefs.restoreDelaySeconds = prefs.restoreDelaySeconds
            }
            false
        } else {
            prefs.restoreDelaySeconds = seconds
            etRestoreDelay.error = null
            true
        }
    }

    // ---------------- akses luar jaringan otomatis ----------------

    /** @return true bila nilai valid dan tersimpan. */
    private fun saveRemoteSettings(showToast: Boolean): Boolean {
        val host = EndpointResolver.normalizeHost(etRemoteHostGlobal.text?.toString().orEmpty())
        val httpBase = etRemoteHttpBase.text?.toString()?.trim()?.toIntOrNull()
        val rtspBase = etRemoteRtspBase.text?.toString()?.trim()?.toIntOrNull()
        val enabled = switchRemoteAuto.isChecked

        tilRemoteHostGlobal.error = null
        tilRemoteHttpBase.error = null
        tilRemoteRtspBase.error = null

        if (host.isNotEmpty() && !EndpointResolver.isValidHost(host)) {
            if (showToast) {
                tilRemoteHostGlobal.error = "Nama host/DDNS tidak valid (tanpa http:// dan tanpa port)."
            }
            return false
        }
        if (httpBase == null || httpBase !in RemoteSettings.MIN_BASE..RemoteSettings.MAX_BASE) {
            if (showToast) {
                tilRemoteHttpBase.error =
                    "Isi ${RemoteSettings.MIN_BASE}–${RemoteSettings.MAX_BASE}."
            }
            return false
        }
        if (rtspBase == null || rtspBase !in RemoteSettings.MIN_BASE..RemoteSettings.MAX_BASE) {
            if (showToast) {
                tilRemoteRtspBase.error =
                    "Isi ${RemoteSettings.MIN_BASE}–${RemoteSettings.MAX_BASE}."
            }
            return false
        }

        val changed = prefs.remoteAuto != enabled ||
            prefs.remoteHost != host ||
            prefs.remoteHttpBase != httpBase ||
            prefs.remoteRtspBase != rtspBase

        prefs.remoteAuto = enabled
        prefs.remoteHost = host
        prefs.remoteHttpBase = httpBase
        prefs.remoteRtspBase = rtspBase
        if (changed) prefs.bumpConfig()

        updateRemoteInfo()
        if (showToast) {
            Toast.makeText(this, "Setelan akses luar disimpan.", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun updateRemoteInfo() {
        val settings = prefs.remoteSettings()
        val atHome = PublicIp.isAtHome(prefs)
        val sb = StringBuilder()
        sb.append("IP publik rumah: ")
        if (prefs.publicIp.isBlank()) {
            sb.append(if (atHome) "mendeteksi…" else "belum terdeteksi")
        } else {
            sb.append(prefs.publicIp)
            if (prefs.publicIpAt > 0) {
                val format = SimpleDateFormat("dd MMM HH:mm", Locale.forLanguageTag("id-ID"))
                sb.append(" (diperbarui ").append(format.format(Date(prefs.publicIpAt))).append(")")
            }
        }
        if (prefs.publicIp.isBlank()) {
            sb.append(
                if (atHome) {
                    "\nTunggu deteksi selesai atau tekan tombol di bawah."
                } else {
                    "\nSambungkan ke Wi-Fi rumah sekali agar alamat terdeteksi otomatis, atau isi DDNS."
                }
            )
        }
        sb.append("\nAlamat untuk luar: ")
        sb.append(settings.effectiveHost.ifBlank { "belum ada — isi DDNS" })
        sb.append("\nStatus: ")
        sb.append(if (settings.enabled) "otomatis aktif" else "otomatis nonaktif")

        tvRemoteInfo.text = sb.toString()
        updateRemotePreview(settings)
    }

    private fun updateRemotePreview(settings: RemoteSettings) {
        val cameras = prefs.cameras().sortedBy { it.id }
        val sb = StringBuilder()
        sb.append("Port luar (router → kamera):\n")
        for (camera in cameras) {
            val host = EndpointResolver.normalizeHost(camera.remoteHost)
                .ifBlank { settings.effectiveHost }
            if (host.isBlank()) {
                sb.append(camera.displayName).append(" → belum ada alamat\n")
                continue
            }
            val httpPort = camera.remoteHttpPort.takeIf { it in 1..65535 }
                ?: settings.httpPort(camera.id)
            val rtspPort = camera.remoteRtspPort.takeIf { it in 1..65535 }
                ?: settings.rtspPort(camera.id)
            sb.append(camera.displayName)
                .append(" → ").append(host)
                .append("  HTTP ").append(httpPort)
                .append(" / RTSP ").append(rtspPort)
                .append("  ⇒ ").append(camera.ip)
                .append('\n')
        }
        sb.append("\nRouter harus meneruskan port di atas ke kamera masing-masing.")
        tvRemotePreview.text = sb.toString()
    }

    /** Render tabel port forwarding yang harus diteruskan di router. */
    private fun renderPortTable() {
        val cameras = prefs.cameras().sortedBy { it.id }
        val sb = StringBuilder()
        sb.append("Tabel port forwarding (TCP) - terapkan di router ke IP LAN kamera masing-masing:\n")
        for (camera in cameras) {
            val httpPort = camera.remoteHttpPort.takeIf { it in 1..65535 } ?: prefs.remoteHttpBase + camera.id
            val rtspPort = camera.remoteRtspPort.takeIf { it in 1..65535 } ?: prefs.remoteRtspBase + camera.id
            sb.append(camera.displayName)
                .append(" -> ").append(camera.ip)
                .append("  HTTP ").append(httpPort)
                .append(" / RTSP ").append(rtspPort)
                .append("\n")
        }
        sb.append("\nPola: HTTP 8081-8089 -> 80, RTSP 8551-8559 -> 554 (jika menggunakan default).\n")
        sb.append("Hanya TCP yang dibutuhkan; RTSP di sini tidak memakai UDP.")
        tvRemotePortTable.text = sb.toString()
    }

    private fun refreshPublicIp(force: Boolean) {
        if (refreshingIp) return
        if (!PublicIp.isAtHome(prefs)) {
            Toast.makeText(
                this,
                "Sambungkan ke Wi-Fi rumah dulu. Di jaringan lain yang terdeteksi adalah IP operator, bukan IP rumah.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        refreshingIp = true
        btnRefreshIp.isEnabled = false
        btnRefreshIp.text = "Mendeteksi IP publik…"

        var finished = false
        val finish: (String?) -> Unit = { ip ->
            if (!finished) {
                finished = true
                refreshingIp = false
                if (!isFinishing && !isDestroyed) {
                    btnRefreshIp.isEnabled = true
                    btnRefreshIp.text = "Perbarui IP publik sekarang"
                    updateRemoteInfo()
                    renderCameras()
                    if (force) {
                        val message = if (ip != null) {
                            "IP publik rumah: $ip"
                        } else {
                            "Gagal mendeteksi IP publik. Periksa internet atau isi DDNS."
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        PublicIp.refreshAsync(prefs, force) { finish(it) }
        // Bila deteksi lain sedang berjalan, tetap pulihkan tombol.
        lifecycleScope.launch {
            delay(8_000)
            finish(prefs.publicIp.takeIf { it.isNotBlank() })
        }
    }

    private fun updateEncryptionInfo() {
        if (prefs.encrypted) {
            tvEncryption.text =
                "Kredensial disimpan di EncryptedSharedPreferences (kunci di Android Keystore)."
            tvEncryption.setTextColor(getColor(R.color.muted))
        } else {
            tvEncryption.text =
                "PERINGATAN: Android Keystore tidak tersedia, kredensial disimpan tanpa enkripsi."
            tvEncryption.setTextColor(getColor(R.color.danger))
        }
    }

    private fun updateRemoteVpnInfo() {
        if (switchRemoteVpn.isChecked) {
            tvRemoteVpnInfo.text = "VPN aktif — kamera tetap bisa diakses dari mana saja lewat jaringan VPN, tidak perlu port forwarding."
            tvRemoteNotice.visibility = View.GONE
        } else {
            tvRemoteVpnInfo.text = "Mode port forwarding — pastikan router meneruskan port HTTP/RTSP ke kamera."
            tvRemoteNotice.visibility = View.VISIBLE
            tvRemoteNotice.text = "Catatan: port forward membuka kamera ke internet. Saat ini router meneruskan HTTP/RTSP ke kamera masing-masing."
        }
    }

    private fun updateRestoreInfo() {
        val backups = store.count()
        val lastAt = prefs.lastRestoreAt
        val sb = StringBuilder()
        sb.append("Backup XML tersimpan: ").append(backups).append(" file.\n")
        sb.append("Pemulihan otomatis: ")
        if (prefs.autoRestore) {
            sb.append("aktif, ").append(prefs.restoreDelaySeconds)
                .append(" detik setelah aplikasi ditinggalkan.")
        } else {
            sb.append("nonaktif. Gunakan tombol Pulihkan semua bila perlu.")
        }
        if (lastAt > 0) {
            val format = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.forLanguageTag("id-ID"))
            sb.append("\nTerakhir: ").append(format.format(Date(lastAt)))
            if (prefs.lastRestoreSummary.isNotBlank()) {
                sb.append("\n").append(prefs.lastRestoreSummary.take(400))
            }
        }
        tvRestoreInfo.text = sb.toString()
    }

    // ---------------- daftar kamera ----------------

    private fun renderCameras() {
        containerCameras.removeAllViews()
        val cameras = prefs.cameras().sortedBy { it.id }
        for (camera in cameras) {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_camera_settings, containerCameras, false)
            val flags = prefs.flags(camera.id)

            row.findViewById<TextView>(R.id.tvCamName).text = camera.displayName
            val endpoint = EndpointResolver.resolve(camera, prefs.remoteSettings())
            row.findViewById<TextView>(R.id.tvCamDetail).text = buildString {
                append(camera.ip)
                append(" • kanal ").append(camera.channel)
                if (camera.isNvr) append(" • NVR")
                if (!camera.enabled) append(" • nonaktif")
                if (camera.user.isNotBlank()) append(" • user khusus")
                when {
                    endpoint.remote -> append(
                        if (endpoint.automatic) " • rute LUAR otomatis" else " • rute LUAR"
                    )
                    endpoint.offLan -> append(" • rute LOKAL (luar belum siap)")
                    else -> append(" • rute LOKAL")
                }
            }
            val badge = row.findViewById<TextView>(R.id.tvCamBadges)
            when {
                !camera.enabled -> {
                    badge.text = "NONAKTIF"
                    badge.setTextColor(getColor(R.color.muted))
                }
                flags.any -> {
                    badge.text = "PERLU PULIH"
                    badge.setTextColor(getColor(R.color.warning))
                }
                else -> {
                    badge.text = "SIAP"
                    badge.setTextColor(getColor(R.color.accent))
                }
            }
            row.setOnClickListener { editCamera(camera) }
            containerCameras.addView(row)
        }
    }

    private fun addCamera() {
        val existing = prefs.cameras()
        val nextId = (existing.maxOfOrNull { it.id } ?: 0) + 1
        val camera = Camera(
            id = nextId,
            name = "CAM-%02d".format(nextId),
            ip = "",
            channel = 1,
            isNvr = false,
            enabled = true,
            user = "",
            pass = ""
        )
        editCamera(camera, isNew = true)
    }

    private fun editCamera(camera: Camera, isNew: Boolean = false) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_camera, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etCamName)
        val etIp = view.findViewById<TextInputEditText>(R.id.etCamIp)
        val etChannel = view.findViewById<TextInputEditText>(R.id.etCamChannel)
        val etCamUser = view.findViewById<TextInputEditText>(R.id.etCamUser)
        val etCamPass = view.findViewById<TextInputEditText>(R.id.etCamPass)
        val switchForceRemote = view.findViewById<MaterialSwitch>(R.id.switchCamForceRemote)
        val switchNvr = view.findViewById<MaterialSwitch>(R.id.switchCamNvr)
        val switchEnabled = view.findViewById<MaterialSwitch>(R.id.switchCamEnabled)
        val tilRemoteHost = view.findViewById<TextInputLayout>(R.id.tilCamRemoteHost)
        val tilRemoteHttp = view.findViewById<TextInputLayout>(R.id.tilCamRemoteHttp)
        val tilRemoteRtsp = view.findViewById<TextInputLayout>(R.id.tilCamRemoteRtsp)
        val etRemoteHost = view.findViewById<TextInputEditText>(R.id.etCamRemoteHost)
        val etRemoteHttp = view.findViewById<TextInputEditText>(R.id.etCamRemoteHttp)
        val etRemoteRtsp = view.findViewById<TextInputEditText>(R.id.etCamRemoteRtsp)
        val progress = view.findViewById<ProgressBar>(R.id.progressTest)
        val tvTest = view.findViewById<TextView>(R.id.tvTestResult)

        etName.setText(camera.name)
        etIp.setText(camera.ip)
        etChannel.setText(camera.channel.toString())
        etCamUser.setText(camera.user)
        etCamPass.setText(camera.pass)
        etRemoteHost.setText(camera.remoteHost)
        etRemoteHttp.setText(camera.remoteHttpPort.takeIf { it in 1..65535 }?.toString().orEmpty())
        etRemoteRtsp.setText(camera.remoteRtspPort.takeIf { it in 1..65535 }?.toString().orEmpty())
        switchNvr.isChecked = camera.isNvr
        switchEnabled.isChecked = camera.enabled
        switchForceRemote.isChecked = camera.forceRemote

        val title = if (isNew) "Tambah kamera" else "${camera.displayName} — ubah"
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(if (isNew) "Tambah" else "Simpan", null)
            .setNegativeButton("Batal", null)
            .setNeutralButton("Tes ISAPI", null)
            .create()

        dialog.setOnShowListener {
            val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            val readFields: (Boolean) -> Camera? = read@{ validate ->
                val name = etName.text?.toString()?.trim().orEmpty()
                val ip = etIp.text?.toString()?.trim().orEmpty()
                val channel = etChannel.text?.toString()?.trim()?.toIntOrNull()
                val forceRemote = switchForceRemote.isChecked
                val remoteHost = EndpointResolver.normalizeHost(
                    etRemoteHost.text?.toString().orEmpty()
                )
                val remoteHttpPort = parsePort(etRemoteHttp.text?.toString(), 0)
                val remoteRtspPort = parsePort(etRemoteRtsp.text?.toString(), 0)
                etIp.error = null
                etChannel.error = null
                tilRemoteHost.error = null
                tilRemoteHttp.error = null
                tilRemoteRtsp.error = null
                if (!validIp(ip)) {
                    if (validate) etIp.error = "Alamat IPv4 tidak valid."
                    return@read null
                }
                if (channel == null || channel !in 1..64) {
                    if (validate) etChannel.error = "Kanal 1–64."
                    return@read null
                }
                if (remoteHost.isNotEmpty() && !EndpointResolver.isValidHost(remoteHost)) {
                    if (validate) tilRemoteHost.error = "Alamat/IP/DDNS tidak valid."
                    return@read null
                }
                if (remoteHttpPort == null) {
                    if (validate) tilRemoteHttp.error = "Port 0–65535."
                    return@read null
                }
                if (remoteRtspPort == null) {
                    if (validate) tilRemoteRtsp.error = "Port 0–65535."
                    return@read null
                }

                camera.copy(
                    name = name.ifBlank { "CAM-%02d".format(camera.id) },
                    ip = ip,
                    channel = channel,
                    isNvr = switchNvr.isChecked,
                    enabled = switchEnabled.isChecked,
                    user = etCamUser.text?.toString()?.trim().orEmpty(),
                    pass = etCamPass.text?.toString().orEmpty(),
                    remoteHost = remoteHost,
                    remoteHttpPort = remoteHttpPort,
                    remoteRtspPort = remoteRtspPort,
                    forceRemote = forceRemote
                )
            }

            positive.setOnClickListener {
                val updated = readFields(true) ?: return@setOnClickListener
                val list = prefs.cameras().toMutableList()
                val idx = list.indexOfFirst { it.id == camera.id }
                if (idx >= 0) list[idx] = updated else list.add(updated)
                prefs.saveCameras(list)
                renderCameras()
                renderPortTable()
                updateRestoreInfo()
                Toast.makeText(
                    this,
                    if (isNew) "Kamera ditambahkan." else "Kamera disimpan.",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }

            neutral.setOnClickListener {
                val probeCamera = readFields(true) ?: return@setOnClickListener
                neutral.isEnabled = false
                positive.isEnabled = false
                progress.visibility = View.VISIBLE
                val endpoint = EndpointResolver.resolve(probeCamera, prefs.remoteSettings())
                tvTest.text = "Menghubungi ${endpoint.host}:${endpoint.httpPort} (${endpoint.label})…"
                lifecycleScope.launch {
                    val result = repository.probe(probeCamera)
                    if (isFinishing || isDestroyed) return@launch
                    progress.visibility = View.GONE
                    neutral.isEnabled = true
                    positive.isEnabled = true
                    tvTest.text = result
                }
            }
        }
        dialog.show()
    }

    private fun confirmRestoreAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Pulihkan semua kamera?")
            .setMessage(
                "Aplikasi akan mengembalikan privacy mask, stream, layanan RTSP, dan kanal NVR " +
                    "sesuai backup. Proses bisa memakan beberapa detik."
            )
            .setPositiveButton("Pulihkan") { _, _ -> restoreAllNow() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun restoreAllNow() {
        btnRestoreAllNow.isEnabled = false
        btnRestoreAllNow.text = "Memulihkan…"
        lifecycleScope.launch {
            val report = try {
                repository.restoreAll(prefs.cameras())
            } catch (e: Exception) {
                id.cctv.monitor.control.RestoreReport(listOf(e.message ?: "Gagal."))
            }
            prefs.lastRestoreSummary = report.summary
            prefs.lastRestoreAt = System.currentTimeMillis()
            btnRestoreAllNow.isEnabled = true
            btnRestoreAllNow.text = "Pulihkan semua sekarang"
            renderCameras()
            updateRestoreInfo()
            val body = if (report.ok) report.summary else report.errors.joinToString("\n") { "• $it" }
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(if (report.ok) "Pemulihan selesai" else "Pemulihan sebagian")
                .setMessage(body)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** Port kosong berarti otomatis (0); nilai di luar 0–65535 mengembalikan null. */
    private fun parsePort(text: String?, default: Int): Int? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return default
        return value.toIntOrNull()?.takeIf { it in 0..65535 }
    }

    private fun validIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val number = part.toIntOrNull()
            part.isNotEmpty() && number != null && number in 0..255
        }
    }
}
