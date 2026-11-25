package id.cctv.monitor

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import id.cctv.monitor.control.CloudIsapiClient
import id.cctv.monitor.data.Camera
import id.cctv.monitor.net.Endpoint
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.IsapiClient
import id.cctv.monitor.playback.CloudPlaybackIsapi
import id.cctv.monitor.playback.DirectPlaybackIsapi
import id.cctv.monitor.playback.PlaybackDownloader
import id.cctv.monitor.playback.PlaybackRtspUrl
import id.cctv.monitor.playback.PlaybackSearchRepository
import id.cctv.monitor.playback.PlaybackSearchResult
import id.cctv.monitor.playback.PlaybackTime
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

/** Cari rekaman NVR per tanggal, lalu putar lewat RTSP atau cache download. */
class PlaybackActivity : AppCompatActivity() {

    private lateinit var channelSpinner: Spinner
    private lateinit var dateView: TextView
    private lateinit var dateButton: MaterialButton
    private lateinit var searchButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var listView: LinearLayout
    private lateinit var downloader: PlaybackDownloader

    private var cameras: List<Camera> = emptyList()
    private var selectedDay: Long = startOfDay(System.currentTimeMillis())
    private var searching = false

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
        searchButton = findViewById(R.id.btnPlaybackSearch)
        progress = findViewById(R.id.progressPlayback)
        listView = findViewById(R.id.listRecording)
        downloader = PlaybackDownloader(this)

        cameras = PlaybackUi.nvrCameras((application as CctvApp).prefs.cameras())
        channelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            cameras.map { it.displayName }
        )
        updateDateLabel()

        dateButton.setOnClickListener { pickDate() }
        searchButton.setOnClickListener {
            if (!searching) lifecycleScope.launch { performSearch() }
        }

        if (cameras.isEmpty()) {
            renderMessage("Belum ada kanal NVR aktif. Tambahkan NVR di Setelan terlebih dahulu.")
            searchButton.isEnabled = false
        }
    }

    override fun onDestroy() {
        downloader.clear()
        super.onDestroy()
    }

    private fun pickDate() {
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedDay }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedDay = startOfDay(
                    Calendar.getInstance().apply { set(year, month, day, 0, 0, 0) }.timeInMillis
                )
                updateDateLabel()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDateLabel() {
        dateView.text = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date(selectedDay))
    }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private suspend fun performSearch() {
        val camera = cameras.getOrNull(channelSpinner.selectedItemPosition) ?: return
        setBusy(true)
        listView.removeAllViews()

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
            render(result, SearchContext(camera, endpoint, user, pass, result))
        }.onFailure { error ->
            renderMessage("Gagal mencari rekaman: ${error.message ?: "tidak diketahui"}")
        }
        setBusy(false)
    }

    private fun render(result: PlaybackSearchResult, context: SearchContext) {
        listView.removeAllViews()
        if (result.clips.isEmpty()) {
            renderMessage("Tidak ada rekaman pada tanggal ini.")
            return
        }
        result.clips.take(100).forEach { clip ->
            val row = TextView(this).apply {
                text = PlaybackUi.rowLabel(clip, context.camera, TimeZone.getDefault())
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@PlaybackActivity, R.color.text))
                setBackgroundColor(ContextCompat.getColor(this@PlaybackActivity, R.color.surface2))
                setPadding(dp(12), dp(14), dp(12), dp(14))
                isClickable = true
                isFocusable = true
                setOnClickListener { openClip(clip, context) }
            }
            listView.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(8)) }
            )
        }
    }

    private fun renderMessage(message: String) {
        listView.removeAllViews()
        listView.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@PlaybackActivity, R.color.muted))
            setPadding(dp(8), dp(16), dp(8), dp(16))
        })
    }

    private fun openClip(clip: RecordingClip, context: SearchContext) {
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
                    PlaybackTime.utcStamp(clip.startMillis),
                    PlaybackTime.utcStamp(clip.endMillis)
                )
                startPlayer(
                    mediaUri = uri,
                    title = PlaybackUi.rowLabel(clip, context.camera, TimeZone.getDefault()),
                    clip = clip
                )
            }

            PlaybackTransport.DOWNLOAD_CACHE -> lifecycleScope.launch {
                setBusy(true)
                val downloaded = downloader.download(clip, context.endpoint, context.user, context.pass)
                setBusy(false)
                downloaded.onSuccess { file ->
                    startPlayer(
                        filePath = file.absolutePath,
                        title = PlaybackUi.rowLabel(clip, context.camera, TimeZone.getDefault()),
                        clip = clip
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
        clip: RecordingClip
    ) {
        val intent = Intent(this, RecordingPlayerActivity::class.java).apply {
            mediaUri?.let { putExtra(RecordingPlayerActivity.EXTRA_MEDIA_URI, it) }
            filePath?.let { putExtra(RecordingPlayerActivity.EXTRA_FILE_PATH, it) }
            putExtra(RecordingPlayerActivity.EXTRA_TITLE, title)
            putExtra(RecordingPlayerActivity.EXTRA_START_MILLIS, clip.startMillis)
            putExtra(RecordingPlayerActivity.EXTRA_END_MILLIS, clip.endMillis)
        }
        startActivity(intent)
    }

    private fun setBusy(busy: Boolean) {
        searching = busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        searchButton.isEnabled = !busy
        dateButton.isEnabled = !busy
        channelSpinner.isEnabled = !busy
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
