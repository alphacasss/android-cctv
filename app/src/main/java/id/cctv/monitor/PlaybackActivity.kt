package id.cctv.monitor

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import id.cctv.monitor.control.CloudIsapiClient
import id.cctv.monitor.data.Camera
import id.cctv.monitor.net.Endpoint
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.IsapiClient
import id.cctv.monitor.playback.CloudPlaybackIsapi
import id.cctv.monitor.playback.DirectPlaybackIsapi
import id.cctv.monitor.playback.PlaybackDownloader
import id.cctv.monitor.playback.PlaybackFilter
import id.cctv.monitor.playback.PlaybackRtspUrl
import id.cctv.monitor.playback.PlaybackSearchRepository
import id.cctv.monitor.playback.PlaybackSearchResult
import id.cctv.monitor.playback.PlaybackTime
import id.cctv.monitor.playback.PlaybackTimeline
import id.cctv.monitor.playback.PlaybackTimelineView
import id.cctv.monitor.playback.PlaybackTransport
import id.cctv.monitor.playback.PlaybackTransportResolver
import id.cctv.monitor.playback.PlaybackUi
import id.cctv.monitor.playback.RecordingClip
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Playback model Hik-Connect: tanggal, filter, timeline 24 jam, satu pemutar. */
class PlaybackActivity : AppCompatActivity() {

    private lateinit var channelSpinner: Spinner
    private lateinit var dateView: TextView
    private lateinit var dateButton: MaterialButton
    private lateinit var prevDayButton: ImageButton
    private lateinit var nextDayButton: ImageButton
    private lateinit var filterGroup: MaterialButtonToggleGroup
    private lateinit var timeline: PlaybackTimelineView
    private lateinit var timeView: TextView
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var hintView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloader: PlaybackDownloader

    private var cameras: List<Camera> = emptyList()
    private var selectedDay: Long = startOfDay(System.currentTimeMillis())
    private var selectedTime: Long = selectedDay
    private var dayStart: Long = selectedDay
    private var dayEnd: Long = selectedDay
    private var rawClips: List<RecordingClip> = emptyList()
    private var selectedFilter: PlaybackFilter = PlaybackFilter.ALL
    private var searchContext: SearchContext? = null
    private var busy = false
    private var spinnerReady = false
    private var pendingSearch = false
    private var cacheHandedOff = false

    private data class SearchContext(
        val camera: Camera,
        val endpoint: Endpoint,
        val user: String,
        val pass: String,
        val result: PlaybackSearchResult
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        findViewById<MaterialToolbar>(R.id.toolbarPlayback).setNavigationOnClickListener { finish() }
        channelSpinner = findViewById(R.id.spinnerPlaybackChannel)
        dateView = findViewById(R.id.tvPlaybackDate)
        dateButton = findViewById(R.id.btnPlaybackDate)
        prevDayButton = findViewById(R.id.btnPlaybackPrevDay)
        nextDayButton = findViewById(R.id.btnPlaybackNextDay)
        filterGroup = findViewById(R.id.filterPlayback)
        timeline = findViewById(R.id.timelinePlayback)
        timeView = findViewById(R.id.tvPlaybackTime)
        prevButton = findViewById(R.id.btnPlaybackPrev)
        nextButton = findViewById(R.id.btnPlaybackNext)
        hintView = findViewById(R.id.tvPlaybackHint)
        progress = findViewById(R.id.progressPlayback)
        downloader = PlaybackDownloader(this)

        cameras = PlaybackUi.nvrCameras((application as CctvApp).prefs.cameras())
        channelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            cameras.map { it.displayName }
        )
        channelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Callback pertama datang dari setAdapter; search eksplisit dipanggil di bawah.
                if (!spinnerReady) {
                    spinnerReady = true
                    return
                }
                requestSearch()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        filterGroup.check(R.id.filterAll)
        filterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedFilter = when (checkedId) {
                R.id.filterEvent -> PlaybackFilter.EVENT
                R.id.filterPerson -> PlaybackFilter.PERSON
                R.id.filterVehicle -> PlaybackFilter.VEHICLE
                else -> PlaybackFilter.ALL
            }
            selectedTime = dayStart
            renderTimeline()
        }

        prevDayButton.setOnClickListener { shiftDay(-1) }
        nextDayButton.setOnClickListener { shiftDay(1) }
        dateButton.setOnClickListener { pickDate() }
        prevButton.setOnClickListener { stepClip(-1) }
        nextButton.setOnClickListener { stepClip(1) }

        updateDateLabel()
        renderTimeline()
        if (cameras.isEmpty()) {
            hintView.text = "Belum ada kanal NVR aktif. Tambahkan NVR di Setelan terlebih dahulu."
        } else {
            requestSearch()
        }
    }

    override fun onDestroy() {
        // File yang sudah diserahkan ke player dihapus player saat selesai, bukan di sini.
        if (!cacheHandedOff) downloader.clear()
        super.onDestroy()
    }

    /** Search terakhir menang; permintaan saat busy ditunda satu kali, tanpa polling. */
    private fun requestSearch() {
        if (busy) {
            pendingSearch = true
            return
        }
        lifecycleScope.launch { performSearch() }
    }

    private fun shiftDay(delta: Int) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = selectedDay
            add(Calendar.DAY_OF_MONTH, delta)
        }
        selectedDay = startOfDay(calendar.timeInMillis)
        selectedTime = selectedDay
        updateDateLabel()
        requestSearch()
    }

    private fun pickDate() {
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedDay }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedDay = startOfDay(
                    Calendar.getInstance().apply { set(year, month, day, 0, 0, 0) }.timeInMillis
                )
                selectedTime = selectedDay
                updateDateLabel()
                requestSearch()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDateLabel() {
        dateView.text = PlaybackTime.dayLabel(selectedDay, System.currentTimeMillis(), TimeZone.getDefault())
    }

    private fun updateTimeLabel() {
        timeView.text = SimpleDateFormat("HH:mm", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(selectedTime))
    }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun renderTimeline() {
        val zone = TimeZone.getDefault()
        val range = PlaybackTime.dayRange(selectedDay, zone)
        dayStart = range.first
        dayEnd = range.second
        val visible = PlaybackTimeline.filter(rawClips, selectedFilter)
        timeline.dayStart = dayStart
        timeline.dayEnd = dayEnd
        timeline.clips = visible
        timeline.selectedIndex = PlaybackTimeline.index(
            visible,
            PlaybackTimeline.ratio(selectedTime, dayStart, dayEnd),
            dayStart,
            dayEnd
        )
        timeline.onScrub = { millis ->
            selectedTime = millis
            updateTimeLabel()
        }
        timeline.onPick = { clip, millis -> openAt(clip, millis) }
        timeline.render()
        hintView.text = if (visible.isEmpty()) {
            "Tidak ada rekaman pada tanggal ini."
        } else {
            "${visible.size} klip • tap atau geser timeline untuk memutar"
        }
        updateTimeLabel()
    }

    private fun stepClip(delta: Int) {
        val visible = PlaybackTimeline.filter(rawClips, selectedFilter)
        val target = PlaybackTimeline.stepState(visible, selectedTime, dayStart, dayEnd, delta)
            ?: return
        selectedTime = target.second
        renderTimeline()
        openAt(visible[target.first], selectedTime)
    }

    private suspend fun performSearch() {
        if (busy) return
        val camera = cameras.getOrNull(channelSpinner.selectedItemPosition)
        if (camera == null) {
            rawClips = emptyList()
            searchContext = null
            renderTimeline()
            return
        }
        setBusy(true)

        val app = application as CctvApp
        val endpoint = EndpointResolver.resolve(camera, app.prefs.remoteSettings())
        val user = app.prefs.effectiveUser(camera)
        val pass = app.prefs.effectivePass(camera)
        val direct = if (user.isNotBlank() && pass.isNotBlank()) {
            DirectPlaybackIsapi(IsapiClient(endpoint.httpBase, user, pass))
        } else {
            null
        }
        val cloudDevice = camera.cloudDevice?.takeIf { it.valid }
        val cloud = if (app.cloudAuth.session.value != null && cloudDevice != null) {
            CloudPlaybackIsapi(CloudIsapiClient(app.cloudRepo, app.cloudAuth, cloudDevice))
        } else {
            null
        }

        val outcome = runCatching {
            PlaybackSearchRepository(direct, cloud)
                .search(camera.streamId(true), selectedDay, TimeZone.getDefault(), endpoint)
        }.getOrElse { Result.failure(it) }

        outcome.onSuccess { result ->
            searchContext = SearchContext(camera, endpoint, user, pass, result)
            rawClips = result.clips
            renderTimeline()
        }.onFailure { error ->
            rawClips = emptyList()
            searchContext = null
            renderTimeline()
            hintView.text = "Gagal mencari rekaman: ${error.message ?: "tidak diketahui"}"
        }
        setBusy(false)
        if (pendingSearch) {
            pendingSearch = false
            requestSearch()
        }
    }

    private fun openAt(clip: RecordingClip, requested: Long) {
        val context = searchContext ?: return
        val picked = PlaybackTimeline.safePicked(clip, requested)
        val offset = PlaybackTimeline.seekOffset(clip, picked)
        val title = PlaybackUi.rowLabel(clip, context.camera, TimeZone.getDefault())

        when (PlaybackTransportResolver.resolve(
            context.result.directReachable,
            context.result.cloudAvailable
        )) {
            PlaybackTransport.ERROR_NO_ROUTE -> toast(
                "NVR tidak terjangkau. Hubungkan HP ke LAN/VPN atau login Hik-Connect."
            )

            PlaybackTransport.STREAM_RTSP -> {
                val uri = PlaybackRtspUrl.build(
                    clip.trackId,
                    context.user,
                    context.pass,
                    context.endpoint.host,
                    context.endpoint.rtspPort,
                    PlaybackTime.utcStamp(picked),
                    PlaybackTime.utcStamp(clip.endMillis)
                )
                startPlayer(
                    mediaUri = uri,
                    title = title,
                    clip = clip,
                    playMillis = picked,
                    seekOffset = 0L
                )
            }

            PlaybackTransport.DOWNLOAD_CACHE -> lifecycleScope.launch {
                setBusy(true)
                val downloaded = downloader.download(clip, context.endpoint, context.user, context.pass)
                setBusy(false)
                downloaded.onSuccess { file ->
                    startPlayer(
                        filePath = file.absolutePath,
                        title = title,
                        clip = clip,
                        playMillis = picked,
                        seekOffset = offset
                    )
                }.onFailure { error ->
                    toast("Gagal mengunduh rekaman: ${error.message ?: "tidak diketahui"}")
                }
            }
        }
    }

    private fun startPlayer(
        mediaUri: String? = null,
        filePath: String? = null,
        title: String,
        clip: RecordingClip,
        playMillis: Long,
        seekOffset: Long
    ) {
        if (filePath != null) cacheHandedOff = true
        val intent = Intent(this, RecordingPlayerActivity::class.java).apply {
            mediaUri?.let { putExtra(RecordingPlayerActivity.EXTRA_MEDIA_URI, it) }
            filePath?.let { putExtra(RecordingPlayerActivity.EXTRA_FILE_PATH, it) }
            putExtra(RecordingPlayerActivity.EXTRA_CLEAR_CACHE, filePath != null)
            putExtra(RecordingPlayerActivity.EXTRA_TITLE, title)
            putExtra(RecordingPlayerActivity.EXTRA_START_MILLIS, clip.startMillis)
            putExtra(RecordingPlayerActivity.EXTRA_END_MILLIS, clip.endMillis)
            putExtra(RecordingPlayerActivity.EXTRA_PLAY_MILLIS, playMillis)
            putExtra(RecordingPlayerActivity.EXTRA_SEEK_OFFSET_MILLIS, seekOffset)
        }
        startActivity(intent)
    }

    private fun setBusy(active: Boolean) {
        busy = active
        progress.visibility = if (active) View.VISIBLE else View.GONE
        dateButton.isEnabled = !active
        prevDayButton.isEnabled = !active
        nextDayButton.isEnabled = !active
        channelSpinner.isEnabled = !active
        filterGroup.isEnabled = !active
        prevButton.isEnabled = !active
        nextButton.isEnabled = !active
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
