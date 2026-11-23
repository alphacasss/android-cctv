package id.cctv.monitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import id.cctv.monitor.CctvApp
import id.cctv.monitor.R
import id.cctv.monitor.cloud.CloudCameraImporter
import id.cctv.monitor.cloud.CloudDevice
import id.cctv.monitor.cloud.CloudSession
import id.cctv.monitor.cloud.HikVisionCloudRepository
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.Camera
import kotlinx.coroutines.launch

/**
 * Dialog bawah untuk login Hik-Connect, sinkronisasi perangkat, dan mengikat
 * satu kamera ke device/kanal cloud.
 *
 * Cloud dipakai untuk metadata (nama, kanal, status, IP lokal) dan snapshot
 * jarak jauh. Stream live memakai `streamUrl` bila tersedia; bila tidak,
 * aplikasi tetap memutar lewat LAN/DDNS/VPN.
 */
class CloudBindingSheet : BottomSheetDialogFragment() {

    companion object {
        const val REQUEST_KEY = "cloud_binding_result"
        const val KEY_CAMERA_ID = "camera_id"
        const val KEY_REFRESH = "refresh"
    }

    private var cameraId = -1
    private lateinit var app: CctvApp
    private lateinit var prefs: AppPrefs
    private lateinit var repository: HikVisionCloudRepository

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etStreamUrl: EditText
    private lateinit var etVerification: EditText
    private lateinit var tvVerificationNote: TextView
    private lateinit var tvAccount: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvAttached: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnSync: MaterialButton
    private lateinit var btnAttach: MaterialButton
    private lateinit var btnUnbind: MaterialButton
    private lateinit var listDevices: ListView
    private lateinit var tvNoDevices: TextView
    private lateinit var progress: ProgressBar

    private var session: CloudSession? = null
    private var devices: List<CloudDevice> = emptyList()
    private var selectedIndex = -1
    private var adapter: DeviceAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_cloud_binding, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CctvApp
        this.app = app
        prefs = app.prefs
        repository = app.cloudRepo

        cameraId = requireArguments().getInt(CameraControlSheet.KEY_CAMERA_ID, -1)

        etEmail = view.findViewById(R.id.etEmail)
        etPassword = view.findViewById(R.id.etPassword)
        etStreamUrl = view.findViewById(R.id.etStreamUrl)
        etVerification = view.findViewById(R.id.etVerification)
        tvVerificationNote = view.findViewById(R.id.tvVerificationNote)
        tvAccount = view.findViewById(R.id.tvCloudAccount)
        tvStatus = view.findViewById(R.id.tvCloudStatus)
        tvAttached = view.findViewById(R.id.tvCloudAttached)
        btnLogin = view.findViewById(R.id.btnCloudLogin)
        btnSync = view.findViewById(R.id.btnCloudSync)
        btnAttach = view.findViewById(R.id.btnCloudAttach)
        btnUnbind = view.findViewById(R.id.btnCloudUnbind)
        listDevices = view.findViewById(R.id.rvCloudDevices)
        tvNoDevices = view.findViewById(R.id.tvCloudNoDevices)
        progress = view.findViewById(R.id.progressCloud)

        etEmail.setText(app.cloudAuth.email)
        btnLogin.setOnClickListener { doLogin() }
        btnSync.setOnClickListener { doSync() }
        btnAttach.setOnClickListener { doAttach() }
        btnUnbind.setOnClickListener { doUnbind() }

        listDevices.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            adapter?.selected = position
            adapter?.notifyDataSetChanged()
            val device = devices.getOrNull(position)
            updateVerificationUi(device)
            tvStatus.text = if (device == null) {
                "Pilih device."
            } else {
                "Terpilih: ${device.displayName} • ${device.serial}"
            }
        }

        showCurrentBinding()

        val existing = app.cloudAuth.session.value
        if (existing != null) {
            session = existing
            tvAccount.text = "Sesi tersimpan: ${existing.userId} • ${existing.apiDomain}"
            tvAccount.visibility = View.VISIBLE
            btnSync.visibility = View.VISIBLE
            tvStatus.text = "Menyinkronkan device…"
            lifecycleScope.launch {
                setBusy(true)
                loadDevices(existing)
                setBusy(false)
            }
        } else {
            tvStatus.text = "Belum login. Masukkan email & password Hik-Connect."
        }
        if (!app.cloudAuth.encrypted) {
            tvStatus.append(
                "\nPeringatan: penyimpanan terenkripsi tidak tersedia; sesi cloud disimpan tanpa enkripsi."
            )
        }
    }

    // ---------------- login & sync ----------------

    private fun doLogin() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        if (email.isBlank() || password.isBlank()) {
            tvStatus.text = "Email dan password wajib diisi."
            return
        }
        setBusy(true)
        tvStatus.text = "Login ke Hik-Connect…"
        lifecycleScope.launch {
            val result = runCatching { repository.login(email, password) }
            if (!isAdded) return@launch
            result.onSuccess { loggedIn ->
                session = loggedIn
                etPassword.setText("")
                tvAccount.text = "Login: ${loggedIn.userId} • ${loggedIn.apiDomain}"
                tvAccount.visibility = View.VISIBLE
                btnSync.visibility = View.VISIBLE
                loadDevices(loggedIn)
            }.onFailure { error ->
                tvStatus.text = "Login gagal: ${error.message ?: "tidak diketahui"}"
            }
            setBusy(false)
        }
    }

    private fun doSync() {
        val current = session ?: app.cloudAuth.session.value
        if (current == null) {
            tvStatus.text = "Login dulu sebelum sinkronisasi."
            return
        }
        session = current
        setBusy(true)
        lifecycleScope.launch {
            loadDevices(current)
            setBusy(false)
        }
    }

    private suspend fun loadDevices(current: CloudSession) {
        tvStatus.text = "Sinkronisasi device cloud…"
        val result = runCatching { repository.syncDevices(current) }
        if (!isAdded) return
        result.onSuccess { raw ->
            // Kode verifikasi diambil otomatis (cache → cloud → ISAPI LAN)
            // supaya pengguna tidak perlu mengetik kode per kanal.
            tvStatus.text = "Menyiapkan kode verifikasi kanal…"
            val list = runCatching {
                repository.prepareForImport(
                    current,
                    raw,
                    prefs.defaultUser,
                    prefs.defaultPass
                )
            }.getOrDefault(raw)
            if (!isAdded) return

            devices = list
            selectedIndex = -1
            tvNoDevices.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            listDevices.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            if (list.isEmpty()) {
                tvStatus.text = "Tidak ada device terikat pada akun ini."
                adapter?.notifyDataSetChanged()
                return@onSuccess
            }
            adapter = DeviceAdapter(list)
            listDevices.adapter = adapter

            // Impor semua kanal sekaligus; BIND per kamera hanya untuk
            // menyesuaikan satu tile bila diperlukan.
            val imported = runCatching { CloudCameraImporter(prefs).import(list) }.getOrNull()
            val missingCodes = list.count {
                it.encrypted && it.verificationCode.isBlank() && it.encryptionPwd.isBlank()
            }
            tvStatus.text = buildString {
                append(list.size).append(" device/kanal ditemukan")
                if (imported != null) {
                    append(" • ").append(imported.devices).append(" kanal siap dipakai")
                }
                append('.')
                if (missingCodes > 0) {
                    append(" ").append(missingCodes)
                    append(" kanal butuh kode verifikasi — pilih kanal lalu isi sekali.")
                }
            }
            if (imported?.changed == true) {
                // Layar utama memuat ulang grid dari konfigurasi hasil impor.
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(KEY_REFRESH to true)
                )
            }
        }.onFailure { error ->
            tvStatus.text = "Sinkronisasi gagal: ${error.message ?: "tidak diketahui"}"
        }
    }

    // ---------------- binding ----------------

    private fun doAttach() {
        val device = devices.getOrNull(selectedIndex)
        if (device == null) {
            Toast.makeText(requireContext(), "Pilih device dulu.", Toast.LENGTH_SHORT).show()
            return
        }
        val cam = currentCamera()
        if (cam == null) {
            tvStatus.text = "Kamera tidak ditemukan."
            return
        }
        if (cam.cloudDevice != null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Ganti binding cloud?")
                .setMessage(
                    "${cam.displayName} sudah terikat ke " +
                        "${cam.cloudDevice?.displayName ?: "device lain"}. Ganti dengan ${device.displayName}?"
                )
                .setPositiveButton("Ganti") { _, _ -> bind(cam, device) }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        bind(cam, device)
    }

    private fun bind(cam: Camera, device: CloudDevice) {
        val cameras = prefs.cameras()
        val index = cameras.indexOfFirst { it.id == cam.id }
        if (index < 0) return

        val streamUrl = etStreamUrl.text.toString().trim()
        if (streamUrl.isNotEmpty() && !isStreamUrl(streamUrl)) {
            tvStatus.text = "URL stream harus diawali rtsp://, http://, atau https://."
            return
        }
        // Kode dipakai apa adanya; huruf besar/kecil memengaruhi kunci AES.
        val enteredCode = etVerification.text.toString().trim()
        val verificationCode = enteredCode.ifBlank {
            device.verificationCode.ifBlank { prefs.cloudVerificationCode(device.serial) }
        }
        if (verificationCode.isNotBlank()) {
            prefs.saveCloudVerificationCode(device.serial, verificationCode)
        }

        var updated = cam.copy(
            cloudDevice = device.copy(
                internalId = cam.id,
                streamUrl = streamUrl,
                verificationCode = verificationCode
            ),
            // Kanal & tipe mengikuti device cloud yang dipilih. Tanpa ini,
            // kamera yang sebelumnya NVR tetap memakai kanal lama saat di-bind
            // ulang ke kamera tunggal (stream jadi salah kanal).
            channel = device.channel,
            isNvr = device.isNvr
        )
        if (updated.ip.isBlank() && device.localIp.isNotBlank()) updated.ip = device.localIp
        if (updated.name.isBlank()) updated.name = device.name
        // NVR: host diarahkan ke NVR karena kanal hanya bisa diakses lewat NVR.
        if (device.isNvr && device.localIp.isNotBlank()) updated.ip = device.localIp
        cameras[index] = updated
        prefs.saveCameras(cameras)

        Toast.makeText(
            requireContext(),
            if (device.encrypted && verificationCode.isBlank() && device.encryptionPwd.isBlank()) {
                "${cam.displayName} → ${device.displayName} • isi kode verifikasi agar snapshot cloud bisa dibuka"
            } else {
                "${cam.displayName} → ${device.displayName}"
            },
            Toast.LENGTH_LONG
        ).show()
        notifyAndDismiss(cam.id)
    }

    private fun doUnbind() {
        val cam = currentCamera() ?: return
        val bound = cam.cloudDevice ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Lepas binding cloud?")
            .setMessage(
                "${cam.displayName} akan dilepas dari ${bound.displayName}. " +
                    "Pemutaran kembali memakai alamat IP kamera."
            )
            .setPositiveButton("Lepas") { _, _ ->
                val cameras = prefs.cameras()
                val index = cameras.indexOfFirst { it.id == cam.id }
                if (index >= 0) {
                    cameras[index] = cameras[index].copy(cloudDevice = null)
                    prefs.saveCameras(cameras)
                }
                Toast.makeText(requireContext(), "Binding cloud dilepas.", Toast.LENGTH_SHORT)
                    .show()
                notifyAndDismiss(cam.id)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------------- helpers ----------------

    private fun currentCamera(): Camera? =
        prefs.cameras().firstOrNull { it.id == cameraId }

    private fun showCurrentBinding() {
        val cam = currentCamera()
        val bound = cam?.cloudDevice
        if (bound == null) {
            tvAttached.text = "Binding cloud: belum ada."
            btnUnbind.visibility = View.GONE
            updateVerificationUi(null)
            return
        }
        val state = if (bound.online) "online" else "offline"
        tvAttached.text = buildString {
            append("Binding cloud: ").append(bound.displayName).append('\n')
            append("Serial ").append(bound.serial)
            append(" • kanal ").append(bound.channel)
            append(" • ").append(state)
            if (bound.encrypted) append(" • terenkripsi")
            if (bound.localIp.isNotBlank()) append(" • LAN ").append(bound.localIp)
            if (bound.wanIp.isNotBlank()) append(" • WAN ").append(bound.wanIp)
            if (bound.streamUrl.isNotBlank()) append("\nStream: ").append(bound.streamUrl)
        }
        etStreamUrl.setText(bound.streamUrl)
        updateVerificationUi(bound)
        btnUnbind.visibility = View.VISIBLE
    }

    /** Tampilkan kolom kode hanya bila kode belum berhasil diambil otomatis. */
    private fun updateVerificationUi(device: CloudDevice?) {
        val code = when {
            device == null -> ""
            device.verificationCode.isNotBlank() -> device.verificationCode
            else -> prefs.cloudVerificationCode(device.serial)
        }
        val show = device?.encrypted == true && code.isBlank()
        etVerification.visibility = if (show) View.VISIBLE else View.GONE
        tvVerificationNote.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            // Nilai tersembunyi tetap dipakai BIND bila pengguna menekan BIND.
            etVerification.setText(code)
            return
        }
        // Pertahankan kode yang sudah tersimpan bila device sama.
        val current = device ?: return
        val bound = currentCamera()?.cloudDevice
        val sameDevice = bound != null && (
            (bound.serial.isNotBlank() && bound.serial == current.serial) ||
                bound.cloudDeviceId == current.cloudDeviceId
            )
        etVerification.setText(if (sameDevice) bound?.verificationCode.orEmpty() else "")
    }

    private fun isStreamUrl(value: String): Boolean {
        val lower = value.lowercase()
        return lower.startsWith("rtsp://") ||
            lower.startsWith("http://") ||
            lower.startsWith("https://")
    }

    private fun notifyAndDismiss(camera: Int) {
        if (!isAdded) return
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(KEY_CAMERA_ID to camera, KEY_REFRESH to true)
        )
        dismiss()
    }

    private fun setBusy(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !value
        btnSync.isEnabled = !value
        btnAttach.isEnabled = !value
        btnUnbind.isEnabled = !value
        etEmail.isEnabled = !value
        etPassword.isEnabled = !value
        etVerification.isEnabled = !value
    }

    /** Dua baris per device: nama + detail, dengan penanda pilihan. */
    private inner class DeviceAdapter(
        private val items: List<CloudDevice>
    ) : ArrayAdapter<CloudDevice>(
        // Field ID wajib diberikan: tanpa ini ArrayAdapter menganggap seluruh
        // item layout adalah TextView dan melempar IllegalStateException saat
        // super.getView() meng-cast TwoLineListItem.
        requireContext(),
        android.R.layout.simple_list_item_2,
        android.R.id.text1,
        items
    ) {

        var selected: Int = -1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val device = items[position]
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)
            val context = view.context
            text1.text = (if (position == selected) "✓ " else "") + device.displayName
            text1.setTextColor(context.getColor(R.color.text))
            text1.textSize = 14f
            val details = buildString {
                append(if (device.online) "Online" else "Offline")
                append(" • ").append(device.deviceType.ifBlank { "Hikvision" })
                append(" • SN ").append(device.serial)
                if (device.encrypted) append(" • terenkripsi")
                if (device.localIp.isNotBlank()) append(" • ").append(device.localIp)
                if (device.wanIp.isNotBlank()) append(" • WAN ").append(device.wanIp)
            }
            text2.text = details
            text2.setTextColor(
                context.getColor(if (device.online) R.color.muted else R.color.status_error)
            )
            text2.textSize = 12f
            return view
        }
    }
}
