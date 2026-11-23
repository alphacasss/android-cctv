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
import id.cctv.monitor.cloud.HikVisionCloudRepository
import id.cctv.monitor.control.ControlRepository
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.CameraFlags
import id.cctv.monitor.data.DayNight
import id.cctv.monitor.data.MediaSaver
import id.cctv.monitor.motion.MotionMath
import id.cctv.monitor.motion.ObjectFilter
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.SnapshotClient
import id.cctv.monitor.player.ClipRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraControlSheet : BottomSheetDialogFragment() {

    companion object {
        const val REQUEST_KEY = "camera_control_result"
        const val KEY_CAMERA_ID = "camera_id"
        const val KEY_REFRESH = "refresh"
        const val KEY_ACTIVITY_ONLY = "activity_only"

        fun newInstance(cameraId: Int): CameraControlSheet =
            CameraControlSheet().apply {
                arguments = bundleOf(KEY_CAMERA_ID to cameraId)
            }
    }

    private var cameraId = -1
    private lateinit var app: CctvApp
    private lateinit var prefs: AppPrefs
    private lateinit var repository: ControlRepository
    private lateinit var cloudRepo: HikVisionCloudRepository
    private var camera: Camera? = null
    private var busy = false
    private lateinit var btnBindCloud: MaterialButton

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
    private lateinit var btnDayNight: MaterialButton
    private lateinit var tvDayNightNote: TextView
    private lateinit var btnMovement: MaterialButton
    private lateinit var tvMovementNote: TextView
    private lateinit var btnNightBoost: MaterialButton
    private lateinit var btnSnapshot: MaterialButton
    private lateinit var btnRecord: MaterialButton
    private lateinit var tvNightNote: TextView
    private lateinit var btnLed: MaterialButton
    private lateinit var btnProbe: MaterialButton
    private lateinit var tvProbeResult: TextView
    private lateinit var btnMotionTest: MaterialButton
    private lateinit var tvMotionTestResult: TextView
    private var ledOn = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_camera_control, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CctvApp
        this.app = app
        prefs = app.prefs
        repository = ControlRepository(prefs, app.store, app.cloudRepo, app.cloudAuth)
        cloudRepo = app.cloudRepo
        cameraId = requireArguments().getInt(KEY_CAMERA_ID, -1)
        camera = prefs.cameras().firstOrNull { it.id == cameraId }

        parentFragmentManager.setFragmentResultListener(
            CloudBindingSheet.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getInt(CloudBindingSheet.KEY_CAMERA_ID, -1) == cameraId) {
                refreshUi()
                sendResult(refresh = true)
            }
        }

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
        btnBindCloud = view.findViewById(R.id.btnBindCloud)
        btnDayNight = view.findViewById(R.id.btnDayNight)
        tvDayNightNote = view.findViewById(R.id.tvDayNightNote)
        btnMovement = view.findViewById(R.id.btnMovement)
        tvMovementNote = view.findViewById(R.id.tvMovementNote)
        btnNightBoost = view.findViewById(R.id.btnNightBoost)
        btnSnapshot = view.findViewById(R.id.btnSnapshot)
        btnRecord = view.findViewById(R.id.btnRecord)
        tvNightNote = view.findViewById(R.id.tvNightNote)
        btnLed = view.findViewById(R.id.btnLed)
        btnProbe = view.findViewById(R.id.btnProbe)
        tvProbeResult = view.findViewById(R.id.tvProbeResult)
        btnMotionTest = view.findViewById(R.id.btnMotionTest)
        tvMotionTestResult = view.findViewById(R.id.tvMotionTestResult)
        btnMovement.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            val enable = !cam.movementDetect
            runAction(if (enable) "Mengaktifkan sensor gerak…" else "Menonaktifkan sensor gerak…") {
                repository.setMovementDetect(cam, enable)
                if (enable) "Sensor gerak diaktifkan." else "Sensor gerak dinonaktifkan."
            }
        }

        btnBindCloud.setOnClickListener {
            CloudBindingSheet().apply {
                arguments = bundleOf(CameraControlSheet.KEY_CAMERA_ID to cameraId)
            }.show(parentFragmentManager, "cloud_binding")
        }

        btnMotionTest.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            if (busy) return@setOnClickListener
            busy = true
            setBusy(true)
            tvMotionTestResult.visibility = View.GONE
            tvStatus.text = "Menguji sensor gerak — lambaikan tangan di depan kamera…"
            viewLifecycleOwner.lifecycleScope.launch {
                val report = withContext(Dispatchers.IO) {
                    val firstResult = SnapshotClient.fetchResult(cam, prefs, main = false, width = 160)
                    val first = firstResult.bytes
                    if (first == null) {
                        return@withContext "GAGAL: ${firstResult.error}\n\n" +
                            "Cek kredensial & izin ISAPI akun, atau coba CEK KEMAMPUAN KAMERA."
                    }
                    delay(1_500)
                    val secondResult = SnapshotClient.fetchResult(cam, prefs, main = false, width = 160)
                    val second = secondResult.bytes
                    if (second == null) {
                        return@withContext "GAGAL snapshot kedua: ${secondResult.error}"
                    }
                    val f1 = MotionMath.grayscale(first)
                    val f2 = MotionMath.grayscale(second)
                    if (f1 == null || f2 == null) {
                        return@withContext "GAGAL: JPEG tidak bisa dibaca " +
                            "(${first.size}/${second.size} byte)."
                    }
                    val stats = MotionMath.diff(f1, f2)
                        ?: return@withContext "GAGAL: ukuran frame tidak cocok."
                    val detection = if (prefs.objectFilter) {
                        val big = SnapshotClient.fetch(cam, prefs, main = false, width = 640)
                        if (big != null) {
                            ObjectFilter(requireContext().applicationContext).detect(big)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                    buildString {
                        append("Snapshot: ${first.size / 1024} KB & ${second.size / 1024} KB\n")
                        append("Rata-rata selisih: ${"%.2f".format(stats.mean)}\n")
                        append(
                            "Blok berubah: ${stats.activeBlocks}/${stats.totalBlocks} " +
                                "(minimal ${MotionMath.MIN_ACTIVE_BLOCKS})\n"
                        )
                        append("Blok paling berubah: ${"%.1f".format(stats.maxBlockMean)}\n")
                        append("Piksel berubah: ${stats.changedPixels}/${stats.totalPixels}\n")
                        append(
                            when {
                                detection == null && prefs.objectFilter ->
                                    "Objek: snapshot besar gagal diambil\n"
                                detection == null -> "Filter objek: nonaktif\n"
                                !detection.available ->
                                    "Objek: model tidak tersedia (${detection.raw})\n"
                                detection.isTarget ->
                                    "Objek: ${detection.label} " +
                                        "(${"%.2f".format(detection.score)})\n"
                                else -> "Objek: tidak ada manusia/hewan/kendaraan " +
                                    "(${detection.raw})\n"
                            }
                        )
                        append("\n")
                        if (!stats.moving) {
                            append(
                                "HASIL: belum ada gerakan. Ulangi sambil melambaikan " +
                                    "tangan di depan kamera."
                            )
                        } else if (!prefs.objectFilter) {
                            append("HASIL: GERAKAN TERDETEKSI (filter objek nonaktif)")
                        } else if (detection?.isTarget == true) {
                            append("HASIL: ${detection.label} TERDETEKSI")
                        } else {
                            append(
                                "HASIL: ada perubahan piksel, tapi bukan objek target — " +
                                    "sensor tidak akan berbunyi."
                            )
                        }
                    }
                }
                busy = false
                if (!isAdded) return@launch
                setBusy(false)
                tvStatus.text = "Tes sensor selesai."
                tvMotionTestResult.text = report
                tvMotionTestResult.visibility = View.VISIBLE
            }
        }
        btnDayNight.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            val irOn = cam.dayNight == DayNight.NIGHT
            val next = if (irOn) DayNight.DAY else DayNight.NIGHT
            runAction(if (next == DayNight.NIGHT) "Menyalakan mode IR/malam…" else "Mematikan mode IR/malam…") {
                val applied = repository.setDayNight(cam, next)
                when {
                    applied && next == DayNight.NIGHT -> "Mode IR/malam dinyalakan."
                    applied -> "Mode IR/malam dimatikan (normal)."
                    next == DayNight.NIGHT ->
                        "Mode malam diterapkan di aplikasi (overlay). Firmware NVR tidak " +
                            "mengizinkan perubahan IR lewat ISAPI."
                    else -> "Mode siang diterapkan di aplikasi. Firmware NVR tidak " +
                        "mengizinkan perubahan IR lewat ISAPI."
                }
            }
        }

        btnNightBoost.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            val flags = prefs.flags(cam.id)
            val enable = !flags.nightBoost
            prefs.setFlags(cam.id, flags.copy(nightBoost = enable))
            refreshUi()
            sendResult(refresh = true)
            tvStatus.text = if (enable) {
                "Night boost aktif — stream dimuat ulang dengan gambar lebih terang."
            } else {
                "Night boost dimatikan."
            }
        }

        btnSnapshot.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            runAction("Mengambil foto…") {
                val cloud = cam.cloudDevice
                val remote = cloud != null && !EndpointResolver.isLocalSubnet(cam.ip)
                if (remote) {
                    val bound = requireNotNull(cloud)
                    if (bound.encrypted && bound.verificationCode.isBlank()) {
                        throw IllegalStateException(
                            "Snapshot cloud perangkat ini terenkripsi. Buka BIND KE CLOUD, " +
                                "isi kode verifikasi perangkat (label NVR), lalu coba lagi."
                        )
                    }
                }
                val cloudBytes = if (remote) {
                    withContext(Dispatchers.IO) {
                        cloud?.let { cloudRepo.captureSnapshot(app.cloudAuth.session.value, it) }
                    }
                } else {
                    null
                }
                val local = if (cloudBytes == null) {
                    withContext(Dispatchers.IO) {
                        SnapshotClient.fetchResult(cam, prefs, main = true)
                    }
                } else {
                    null
                }
                val bytes = cloudBytes ?: local?.bytes
                    ?: throw IllegalStateException("Snapshot gagal: ${local?.error ?: "cloud tidak mengirim gambar"}")
                val file = withContext(Dispatchers.IO) {
                    MediaSaver.saveSnapshot(requireContext(), cam.displayName, bytes)
                } ?: throw IllegalStateException("Gagal menyimpan foto ke galeri.")
                if (cloudBytes != null) {
                    "Foto disimpan dari snapshot cloud (${file.name})."
                } else {
                    "Foto disimpan ke galeri (${file.name})."
                }
            }
        }

        btnRecord.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            if (ClipRecorder.isRecording(cam.id)) {
                tvStatus.text = "Menyimpan rekaman…"
                setBusy(true)
                ClipRecorder.stop { file, message ->
                    if (!isAdded) return@stop
                    setBusy(false)
                    if (file != null) {
                        MediaSaver.publishRecording(requireContext(), file)
                    }
                    tvStatus.text = message
                    refreshUi()
                }
            } else {
                tvStatus.text = "Mulai merekam main stream…"
                ClipRecorder.start(
                    requireContext(),
                    prefs,
                    cam,
                    MediaSaver.recordingsDir(requireContext())
                ) { file, message ->
                    if (!isAdded) return@start
                    if (file != null) MediaSaver.publishRecording(requireContext(), file)
                    tvStatus.text = message
                    refreshUi()
                }
                refreshUi()
            }
        }

        btnLed.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            val next = !ledOn
            runAction(if (next) "Menyalakan lampu putih…" else "Mematikan lampu putih…") {
                repository.setSupplementLight(cam, next)
                ledOn = next
                if (next) "Lampu putih dinyalakan." else "Lampu putih dimatikan."
            }
        }

        btnProbe.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            if (busy) return@setOnClickListener
            busy = true
            setBusy(true)
            tvStatus.text = "Memeriksa kemampuan kamera…"
            viewLifecycleOwner.lifecycleScope.launch {
                val lines = try {
                    repository.probeCapabilities(cam).joinToString("\n") { c ->
                        (if (c.supported) "✓ " else "✗ ") + c.name + " — " + c.detail
                    }
                } catch (e: Exception) {
                    e.message ?: "Gagal memeriksa kamera."
                }
                busy = false
                if (!isAdded) return@launch
                setBusy(false)
                tvStatus.text = "Pemeriksaan selesai."
                tvProbeResult.text = lines
                tvProbeResult.visibility = View.VISIBLE
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Kemampuan ${cam.displayName}")
                    .setMessage(lines)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        camera?.let { cam ->
            tvTitle.text = "${cam.displayName} • ${cam.ip}"
            tvSubtitle.text = buildString {
                append("Kanal ${cam.channel} • sub ${cam.streamId(false)} / main ${cam.streamId(true)}")
                if (cam.isNvr) append(" • NVR")
                if (!cam.enabled) append(" • NONAKTIF")
            }
            // Semua tombol tetap tersedia. Untuk kanal NVR, STOP STREAM dan
            // RTSP OFF diarahkan ke penghentian kanal (bukan seluruh NVR).
            btnStream.visibility = View.VISIBLE
            btnNvr.visibility = if (cam.isNvr) View.VISIBLE else View.GONE
            btnRtsp.visibility = View.VISIBLE
            tvRtspNote.visibility = if (cam.isNvr) View.VISIBLE else View.GONE
            if (cam.isNvr) {
                tvRtspNote.text =
                    "Kanal NVR: STOP STREAM mematikan sub-stream kanal ini, RTSP OFF dan " +
                        "KANAL STOP menghentikan kanal ini (main + sub). Tidak menghentikan kanal lain."
            }
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
        val cam = prefs.cameras().firstOrNull { it.id == cameraId } ?: camera ?: return
        camera = cam
        val flags = prefs.flags(cameraId)
        btnBlack.text = if (flags.black) {
            "NORMAL — nonaktifkan privacy mask"
        } else {
            "HITAM — aktifkan privacy mask"
        }
        btnStream.text = if (flags.streamOff) "AKTIFKAN STREAM" else "STOP STREAM (sub 102)"
        btnRtsp.text = when {
            cam.isNvr && flags.rtspOff -> "RTSP ON — aktifkan kanal"
            cam.isNvr -> "RTSP OFF — matikan kanal"
            flags.rtspOff -> "RTSP ON — aktifkan layanan"
            else -> "RTSP OFF — paksa mati"
        }
        btnNvr.text = if (flags.nvrOff) "KANAL AKTIF" else "KANAL STOP"
        btnNightBoost.text = if (flags.nightBoost) {
            "NIGHT BOOST: ON — ketuk untuk mati"
        } else {
            "NIGHT BOOST (tanpa IR)"
        }
        btnRecord.text = if (ClipRecorder.isRecording(cam.id)) "HENTIKAN" else "REKAM"
        btnBindCloud.text = cam.cloudDevice?.let { cloud ->
            "CLOUD: ${cloud.displayName} — ubah"
        } ?: "BIND KE CLOUD (Hik-Connect)"

        val irOn = cam.dayNight == DayNight.NIGHT
        btnDayNight.text = if (irOn) "MATIKAN MODE IR" else "NYALAKAN MODE IR"
        btnMovement.text = if (cam.movementDetect) "MATIKAN SENSOR GERAK" else "NYALAKAN SENSOR GERAK"
        tvMovementNote.visibility = View.VISIBLE
        tvDayNightNote.visibility = View.VISIBLE

        tvFlags.text = describeFlags(cam, flags)
        tvFlags.setTextColor(
            requireContext().getColor(
                if (flags.any || irOn || cam.movementDetect) R.color.warning else R.color.muted
            )
        )
        tvSubtitle.text = buildString {
            append("Kanal ${cam.channel} • sub ${cam.streamId(false)} / main ${cam.streamId(true)}")
            if (cam.isNvr) append(" • NVR")
            if (!cam.enabled) append(" • NONAKTIF")
            cam.cloudDevice?.let { cloud ->
                append(" • CLOUD ").append(cloud.name.ifBlank { cloud.serial })
                if (cloud.isNvr) append(" CH").append(cloud.channel)
                if (!cloud.online) append(" (offline)")
            }
        }
    }

    private fun describeFlags(cam: Camera, flags: CameraFlags): String {
        val base = when {
            !flags.any -> "Status: normal"
            flags.rtspOff -> if (cam.isNvr) {
                "Status: kanal dihentikan (main + sub); kanal lain tetap berjalan."
            } else {
                "Status: layanan RTSP dimatikan — semua stream berhenti."
            }
            flags.streamOff -> "Status: sub-stream dimatikan."
            flags.nvrOff -> "Status: kanal NVR dihentikan."
            flags.black -> "Status: privacy mask aktif."
            else -> "Status: normal"
        }
        val parts = mutableListOf<String>()
        parts += if (cam.movementDetect) "SENSOR GERAK: ON" else "SENSOR GERAK: OFF"
        parts += when (cam.dayNight) {
            DayNight.NIGHT -> "MODE IR: ON"
            DayNight.DAY -> "MODE IR: OFF"
            DayNight.AUTO -> "MODE IR: AUTO"
        }
        if (flags.nightBoost) parts += "NIGHT BOOST: ON"
        return "$base  |  ${parts.joinToString(" • ")}"
    }



    private fun toggleBlack() {
        val cam = camera ?: return
        val enable = !prefs.flags(cameraId).black
        if (enable) {
            // Peringatan sebelum mengaktifkan privacy mask: pengaturan ini berada
            // di level kamera dan persist. Bila dilakukan dari jaringan luar dan
            // perintah nonaktifkan gagal, kamera akan tetap hitam.
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Aktifkan privacy mask?")
                .setMessage(
                    "Kamera akan tampak hitam hingga privacy mask dimatikan kembali. " +
                        "Perintah dikirim lewat Hik-Connect saat kamera berada di luar jaringan " +
                        "lokal, jadi tetap bisa dimatikan dari seluler selama sesi cloud aktif."
                )
                .setPositiveButton("Lanjutkan") { _, _ ->
                    runAction("Mengaktifkan privacy mask…") {
                        repository.setBlack(cam, enable)
                        "Kamera dihitamkan (privacy mask aktif)."
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        runAction("Membuka privacy mask…") {
            repository.setBlack(cam, enable)
            val all = prefs.cameras().toMutableList()
            val idx = all.indexOfFirst { it.id == cam.id }
            if (idx >= 0) {
                all[idx].dayNight = DayNight.AUTO
                prefs.saveCameras(all)
            }
            "Privacy mask dibuka; kamera normal."
        }
    }

    private fun toggleStream() {
        val cam = camera ?: return
        val turnOn = prefs.flags(cameraId).streamOff
        if (turnOn) {
            runAction("Mengaktifkan stream…") {
                val applied = repository.setStream(cam, true)
                if (applied) "Stream ${cam.streamId(false)} diaktifkan kembali."
                else "Stream diaktifkan di aplikasi; perangkat tidak mengonfirmasi perubahan."
            }
            return
        }
        confirm(
            "Hentikan sub-stream?",
            "Sub-stream ${cam.streamId(false)} akan dimatikan dan aplikasi berhenti memutarnya. " +
                if (cam.isNvr) {
                    "Hanya kanal ${cam.channel} yang terpengaruh."
                } else {
                    "Beberapa firmware tidak mengizinkan perubahan pada perangkat; " +
                        "aplikasi tetap berhenti memutar stream ini."
                }
        ) {
            runAction("Menghentikan sub-stream…") {
                val applied = repository.setStream(cam, false)
                if (applied) "Sub-stream ${cam.streamId(false)} dimatikan."
                else "Stream dihentikan di aplikasi; firmware perangkat tidak mengonfirmasi perubahan."
            }
        }
    }

    private fun toggleRtsp() {
        val cam = camera ?: return
        val turnOn = prefs.flags(cameraId).rtspOff
        if (turnOn) {
            runAction("Mengaktifkan kanal…") {
                val applied = repository.setRtsp(cam, true)
                if (applied) "Kanal aktif kembali."
                else "Kanal diaktifkan di aplikasi; perangkat tidak mengonfirmasi perubahan."
            }
            return
        }
        confirm(
            if (cam.isNvr) "Hentikan kanal ${cam.channel}?" else "Matikan layanan RTSP?",
            if (cam.isNvr) {
                "Kanal ${cam.channel} (main + sub) akan dihentikan di aplikasi. " +
                    "Kanal lain pada NVR tetap berjalan."
            } else {
                "Semua stream kamera ini akan berhenti. HTTP/ISAPI tetap hidup, " +
                    "jadi kamera masih bisa dipulihkan lewat tombol RTSP ON."
            }
        ) {
            runAction("Menghentikan kanal…") {
                val applied = repository.setRtsp(cam, false)
                if (applied) "Kanal dihentikan. Gunakan tombol ini lagi untuk mengaktifkannya."
                else "Kanal dihentikan di aplikasi; firmware perangkat tidak mengonfirmasi perubahan."
            }
        }
    }

    private fun toggleNvr() {
        val cam = camera ?: return
        val turnOn = prefs.flags(cameraId).nvrOff
        if (turnOn) {
            runAction("Mengaktifkan kanal NVR…") {
                val applied = repository.setNvr(cam, true)
                if (applied) "Kanal NVR aktif kembali."
                else "Kanal diaktifkan di aplikasi; perangkat tidak mengonfirmasi perubahan."
            }
            return
        }
        confirm(
            "Hentikan kanal NVR?",
            "Kanal ${cam.channel} pada NVR akan dihentikan di aplikasi. " +
                "Aktifkan lagi lewat tombol KANAL AKTIF."
        ) {
            runAction("Menghentikan kanal NVR…") {
                val applied = repository.setNvr(cam, false)
                if (applied) "Kanal NVR dihentikan."
                else "Kanal dihentikan di aplikasi; firmware perangkat tidak mengonfirmasi perubahan."
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
            // Aksi gagal tetap tercatat di log, tapi tidak boleh memicu refresh
            // pemutaran; cukup beri tahu beranda untuk memperbarui card log.
            sendResult(refresh = false, activityOnly = !success)
        }
    }

    private fun setBusy(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
        btnBlack.isEnabled = !value
        btnDayNight.isEnabled = !value
        btnMovement.isEnabled = !value
        btnNightBoost.isEnabled = !value
        btnSnapshot.isEnabled = !value
        btnRecord.isEnabled = !value
        btnLed.isEnabled = !value
        btnProbe.isEnabled = !value
        btnMotionTest.isEnabled = !value
        btnStream.isEnabled = !value
        btnRtsp.isEnabled = !value
        btnNvr.isEnabled = !value
        btnRefresh.isEnabled = !value
    }

    private fun sendResult(refresh: Boolean, activityOnly: Boolean = false) {
        if (!isAdded) return
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(
                KEY_CAMERA_ID to cameraId,
                KEY_REFRESH to refresh,
                KEY_ACTIVITY_ONLY to activityOnly
            )
        )
    }
}
