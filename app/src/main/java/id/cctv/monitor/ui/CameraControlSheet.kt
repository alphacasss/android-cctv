package id.cctv.monitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import id.cctv.monitor.control.ControlRepository
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.CameraFlags
import kotlinx.coroutines.launch

class CameraControlSheet : BottomSheetDialogFragment() {

    companion object {
        const val REQUEST_KEY = "camera_control_result"
        const val KEY_CAMERA_ID = "camera_id"
        const val KEY_REFRESH = "refresh"

        fun newInstance(cameraId: Int): CameraControlSheet =
            CameraControlSheet().apply {
                arguments = bundleOf(KEY_CAMERA_ID to cameraId)
            }
    }

    private var cameraId = -1
    private lateinit var prefs: AppPrefs
    private lateinit var repository: ControlRepository
    private var camera: Camera? = null
    private var busy = false

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvFlags: TextView
    private lateinit var tvRtspNote: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnBlack: MaterialButton
    private lateinit var btnStream: MaterialButton
    private lateinit var btnRtsp: MaterialButton
    private lateinit var btnNvr: MaterialButton
    private lateinit var btnRefresh: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_camera_control, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CctvApp
        prefs = app.prefs
        repository = ControlRepository(prefs, app.store)
        cameraId = requireArguments().getInt(KEY_CAMERA_ID, -1)
        camera = prefs.cameras().firstOrNull { it.id == cameraId }

        tvTitle = view.findViewById(R.id.tvSheetTitle)
        tvSubtitle = view.findViewById(R.id.tvSheetSubtitle)
        tvStatus = view.findViewById(R.id.tvSheetStatus)
        tvFlags = view.findViewById(R.id.tvSheetFlags)
        tvRtspNote = view.findViewById(R.id.tvRtspNote)
        progress = view.findViewById(R.id.progressSheet)
        btnBlack = view.findViewById(R.id.btnBlack)
        btnStream = view.findViewById(R.id.btnStream)
        btnRtsp = view.findViewById(R.id.btnRtsp)
        btnNvr = view.findViewById(R.id.btnNvr)
        btnRefresh = view.findViewById(R.id.btnRefresh)

        camera?.let { cam ->
            tvTitle.text = "${cam.displayName} • ${cam.ip}"
            tvSubtitle.text = buildString {
                append("Kanal ${cam.channel} • sub ${cam.streamId(false)} / main ${cam.streamId(true)}")
                if (cam.isNvr) append(" • NVR")
                if (!cam.enabled) append(" • NONAKTIF")
            }
            btnStream.visibility = if (cam.isNvr) View.GONE else View.VISIBLE
            btnNvr.visibility = if (cam.isNvr) View.VISIBLE else View.GONE
            btnRtsp.visibility = if (cam.isNvr) View.GONE else View.VISIBLE
            tvRtspNote.visibility = if (cam.isNvr) View.VISIBLE else View.GONE
        }

        btnBlack.setOnClickListener { toggleBlack() }
        btnStream.setOnClickListener { toggleStream() }
        btnRtsp.setOnClickListener { toggleRtsp() }
        btnNvr.setOnClickListener { toggleNvr() }
        btnRefresh.setOnClickListener {
            sendResult(refresh = true)
            Toast.makeText(requireContext(), "Memuat ulang tampilan…", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        refreshUi()
    }

    private fun refreshUi() {
        val cam = camera ?: return
        val flags = prefs.flags(cameraId)
        btnBlack.text = if (flags.black) {
            "NORMAL — nonaktifkan privacy mask"
        } else {
            "HITAM — aktifkan privacy mask"
        }
        btnStream.text = if (flags.streamOff) "AKTIFKAN STREAM" else "STOP STREAM (sub 102)"
        btnRtsp.text = if (flags.rtspOff) "RTSP ON — aktifkan layanan" else "RTSP OFF — paksa mati"
        btnNvr.text = if (flags.nvrOff) "KANAL AKTIF" else "KANAL STOP"
        tvFlags.text = describeFlags(flags)
        tvFlags.setTextColor(
            requireContext().getColor(
                if (flags.any) R.color.warning else R.color.muted
            )
        )
        tvSubtitle.text = buildString {
            append("Kanal ${cam.channel} • sub ${cam.streamId(false)} / main ${cam.streamId(true)}")
            if (cam.isNvr) append(" • NVR")
            if (!cam.enabled) append(" • NONAKTIF")
        }
    }

    private fun describeFlags(flags: CameraFlags): String = when {
        !flags.any -> "Status: normal"
        flags.rtspOff -> "Status: layanan RTSP dimatikan — semua stream berhenti."
        flags.streamOff -> "Status: sub-stream dimatikan."
        flags.nvrOff -> "Status: kanal NVR dihentikan."
        flags.black -> "Status: privacy mask aktif (HITAM)."
        else -> "Status: normal"
    }

    private fun toggleBlack() {
        val cam = camera ?: return
        val enable = !prefs.flags(cameraId).black
        runAction(if (enable) "Mengaktifkan privacy mask…" else "Membuka privacy mask…") {
            repository.setBlack(cam, enable)
            if (enable) "Kamera dihitamkan (privacy mask aktif)." else "Privacy mask dibuka; kamera normal."
        }
    }

    private fun toggleStream() {
        val cam = camera ?: return
        val turnOn = prefs.flags(cameraId).streamOff
        if (turnOn) {
            runAction("Mengaktifkan stream…") {
                repository.setStream(cam, true)
                "Stream 102 diaktifkan kembali."
            }
            return
        }
        confirm(
            "Hentikan sub-stream?",
            "Sub-stream ${cam.streamId(false)} akan dimatikan. Kamera berhenti mengirim video " +
                "untuk grid. Firmware tertentu menolak perintah ini; aplikasi akan rollback otomatis."
        ) {
            runAction("Menghentikan sub-stream…") {
                repository.setStream(cam, false)
                "Sub-stream 102 dimatikan."
            }
        }
    }

    private fun toggleRtsp() {
        val cam = camera ?: return
        val turnOn = prefs.flags(cameraId).rtspOff
        if (turnOn) {
            runAction("Mengaktifkan layanan RTSP…") {
                repository.setRtsp(cam, true)
                "Layanan RTSP aktif kembali."
            }
            return
        }
        confirm(
            "Matikan layanan RTSP?",
            "Semua stream kamera ini akan berhenti, termasuk rekaman NVR. HTTP/ISAPI tetap hidup, " +
                "jadi kamera masih bisa dipulihkan lewat tombol RTSP ON."
        ) {
            runAction("Mematikan layanan RTSP…") {
                repository.setRtsp(cam, false)
                "Layanan RTSP dimatikan. Gunakan RTSP ON untuk memulihkan."
            }
        }
    }

    private fun toggleNvr() {
        val cam = camera ?: return
        val turnOn = prefs.flags(cameraId).nvrOff
        if (turnOn) {
            runAction("Mengaktifkan kanal NVR…") {
                repository.setNvr(cam, true)
                "Kanal NVR aktif kembali."
            }
            return
        }
        confirm(
            "Hentikan kanal NVR?",
            "Kanal ${cam.channel} pada NVR akan dihentikan sehingga rekaman kanal ini berhenti. " +
                "Aktifkan lagi lewat tombol KANAL AKTIF."
        ) {
            runAction("Menghentikan kanal NVR…") {
                repository.setNvr(cam, false)
                "Kanal NVR dihentikan."
            }
        }
    }

    private fun confirm(title: String, message: String, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Lanjutkan") { _, _ -> onOk() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun runAction(label: String, action: suspend () -> String) {
        if (busy) return
        busy = true
        setBusy(true)
        tvStatus.text = label
        viewLifecycleOwner.lifecycleScope.launch {
            var message: String
            var success = false
            try {
                message = action()
                success = true
            } catch (e: Exception) {
                message = e.message ?: "Gagal."
            }
            busy = false
            if (!isAdded) return@launch
            setBusy(false)
            refreshUi()
            tvStatus.text = message
            if (success) sendResult(refresh = false)
        }
    }

    private fun setBusy(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
        btnBlack.isEnabled = !value
        btnStream.isEnabled = !value
        btnRtsp.isEnabled = !value
        btnNvr.isEnabled = !value
        btnRefresh.isEnabled = !value
    }

    private fun sendResult(refresh: Boolean) {
        if (!isAdded) return
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(KEY_CAMERA_ID to cameraId, KEY_REFRESH to refresh)
        )
    }
}
