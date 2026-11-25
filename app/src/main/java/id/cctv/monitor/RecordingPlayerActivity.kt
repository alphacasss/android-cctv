package id.cctv.monitor

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import id.cctv.monitor.playback.SeekMode
import id.cctv.monitor.player.RecordingMediaResolver
import id.cctv.monitor.player.RecordingPlaybackTime
import id.cctv.monitor.player.SharedVlc
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/** Pemutar rekaman lokal. Memakai LibVLC yang sama dengan grid, tanpa jaringan. */
class RecordingPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "recording_file_path"
        const val EXTRA_MEDIA_URI = "recording_media_uri"
        const val EXTRA_TITLE = "recording_title"
        const val EXTRA_START_MILLIS = "recording_start_millis"
        const val EXTRA_END_MILLIS = "recording_end_millis"
        const val EXTRA_PLAY_MILLIS = "recording_play_millis"
        const val EXTRA_SEEK_OFFSET_MILLIS = "recording_seek_offset_millis"
    }

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var overlay: TextView
    private lateinit var titleView: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var currentView: TextView
    private lateinit var durationView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var topBar: LinearLayout
    private lateinit var controls: LinearLayout

    private var player: MediaPlayer? = null
    private var viewsAttached = false
    private var durationMillis = 0L
    private var seeking = false
    private var finished = false
    private var mediaMrl: String = ""
    private var seekMode: SeekMode = SeekMode.ENABLED
    private var playingSeen = false
    private var expectedDurationMillis = 0L
    private var elapsedMillis = 0L
    private var lastTickAt = 0L
    private var seekOffsetMillis = 0L
    private var seekOffsetApplied = false

    private val clock = Handler(Looper.getMainLooper())
    private val timeTick = object : Runnable {
        override fun run() {
            tickTime()
            clock.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        videoLayout = findViewById(R.id.videoRecording)
        overlay = findViewById(R.id.tvRecordingOverlay)
        titleView = findViewById(R.id.tvRecordingTitle)
        playPauseButton = findViewById(R.id.btnRecordingPlayPause)
        currentView = findViewById(R.id.tvRecordingCurrent)
        durationView = findViewById(R.id.tvRecordingDuration)
        seekBar = findViewById(R.id.seekRecording)
        topBar = findViewById(R.id.topBarRecording)
        controls = findViewById(R.id.controlsRecording)

        val root = findViewById<View>(R.id.rootRecordingPlayer)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(bars.left + dp(8), bars.top + dp(8), bars.right + dp(8), dp(8))
            controls.setPadding(
                bars.left + dp(10),
                dp(8),
                bars.right + dp(10),
                bars.bottom + dp(8)
            )
            insets
        }

        val resolved = RecordingMediaResolver.resolve(
            intent.getStringExtra(EXTRA_MEDIA_URI),
            intent.getStringExtra(EXTRA_FILE_PATH)
        )
        mediaMrl = resolved.mrl
        seekMode = resolved.seekMode
        seekBar.isEnabled = seekMode == SeekMode.ENABLED
        val clipStart = intent.getLongExtra(EXTRA_START_MILLIS, 0L)
        val clipEnd = intent.getLongExtra(EXTRA_END_MILLIS, 0L)
        if (clipEnd > clipStart) {
            expectedDurationMillis = clipEnd - clipStart
        }
        if (seekMode == SeekMode.DISABLED && expectedDurationMillis > 0L) {
            durationMillis = expectedDurationMillis
            durationView.text = RecordingPlaybackTime.format(durationMillis)
        }
        val playMillis = intent.getLongExtra(EXTRA_PLAY_MILLIS, clipStart)
        seekOffsetMillis = intent.getLongExtra(EXTRA_SEEK_OFFSET_MILLIS, 0L).coerceAtLeast(0L)
        if (playMillis > clipStart) {
            elapsedMillis = playMillis - clipStart
            currentView.text = RecordingPlaybackTime.format(elapsedMillis)
        }

        titleView.text = intent.getStringExtra(EXTRA_TITLE).orBlankOr("Rekaman")
        findViewById<ImageButton>(R.id.btnRecordingClose).setOnClickListener { finish() }

        playPauseButton.setOnClickListener {
            val active = player ?: return@setOnClickListener
            // Setelah EndReached, VLC butuh stop() sebelum play() agar state EOF hilang.
            if (finished) {
                active.stop()
                active.time = 0L
                finished = false
                hideOverlay()
                active.play()
                updatePlayPauseIcon()
                return@setOnClickListener
            }
            if (active.isPlaying) {
                active.pause()
            } else {
                active.play()
            }
            updatePlayPauseIcon()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentView.text =
                    RecordingPlaybackTime.format(
                        RecordingPlaybackTime.seekMillis(durationMillis, progress, seek.max)
                    )
            }

            override fun onStartTrackingTouch(seek: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(seek: SeekBar) {
                seeking = false
                val target = RecordingPlaybackTime.seekMillis(durationMillis, seek.progress, seek.max)
                player?.time = target
                finished = false
                hideOverlay()
            }
        })

        showOverlay("Memuat…")
    }

    override fun onStart() {
        super.onStart()
        if (player == null) initializePlayer()
        if (!finished && player?.isPlaying == false) player?.play()
        clock.removeCallbacks(timeTick)
        clock.post(timeTick)
    }

    override fun onStop() {
        clock.removeCallbacks(timeTick)
        try {
            player?.pause()
        } catch (_: Throwable) {
        }
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun initializePlayer() {
        if (mediaMrl.isBlank()) {
            invalidSource("Sumber rekaman tidak ditemukan")
            return
        }
        val localFile = if (seekMode == SeekMode.ENABLED) {
            val file = File(mediaMrl)
            if (!file.isFile) {
                invalidSource("File rekaman tidak ditemukan")
                return
            }
            file
        } else {
            null
        }

        try {
            val libVlc = SharedVlc.get(applicationContext)
            val active = MediaPlayer(libVlc)
            active.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
            active.setEventListener { event ->
                runOnUiThread { onPlayerEvent(event.type) }
            }

            val media = if (localFile != null) {
                Media(libVlc, Uri.fromFile(localFile)).apply {
                    addOption(":no-video-title-show")
                    addOption(":file-caching=300")
                }
            } else {
                Media(libVlc, Uri.parse(mediaMrl)).apply {
                    addOption(":no-video-title-show")
                    addOption(":rtsp-tcp")
                    addOption(":network-caching=300")
                }
            }

            active.attachViews(videoLayout, null, false, true)
            viewsAttached = true
            active.setMedia(media)
            media.release()
            player = active
        } catch (t: Throwable) {
            Toast.makeText(this, "Gagal menyiapkan pemutar: ${t.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun invalidSource(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** VLC replay RTSP sering tidak memancarkan TimeChanged; baca langsung, lalu pakai jam lokal. */
    private fun tickTime() {
        val active = player ?: return
        if (finished) return
        val now = SystemClock.elapsedRealtime()
        val vlcLength = runCatching { active.length }.getOrDefault(0L)
        if (vlcLength > 0L && vlcLength != durationMillis) {
            durationMillis = vlcLength
            durationView.text = RecordingPlaybackTime.format(durationMillis)
        }
        val vlcTime = runCatching { active.time }.getOrDefault(0L)
        if (vlcTime > 0L) {
            elapsedMillis = vlcTime
        } else if (active.isPlaying && lastTickAt > 0L) {
            elapsedMillis += now - lastTickAt
        }
        lastTickAt = now
        if (expectedDurationMillis > 0L) {
            elapsedMillis = elapsedMillis.coerceIn(0L, expectedDurationMillis)
        }
        currentView.text = RecordingPlaybackTime.format(elapsedMillis)
        seekBar.progress = if (durationMillis > 0L) {
            ((elapsedMillis.toFloat() / durationMillis.toFloat()) * seekBar.max).toInt()
                .coerceIn(0, seekBar.max)
        } else {
            0
        }
    }

    private fun onPlayerEvent(type: Int) {
        when (type) {
            MediaPlayer.Event.Opening,
            MediaPlayer.Event.Buffering -> {
                if (!playingSeen) showOverlay("Memuat…")
            }

            MediaPlayer.Event.Playing,
            MediaPlayer.Event.Vout -> {
                playingSeen = true
                finished = false
                hideOverlay()
                updatePlayPauseIcon()
                // File cache saja. RTSP tidak bisa seek; seekMode enum menjaganya.
                if (seekMode == SeekMode.ENABLED && seekOffsetMillis > 0L && !seekOffsetApplied) {
                    seekOffsetApplied = true
                    runCatching { player?.time = seekOffsetMillis }
                    elapsedMillis = seekOffsetMillis
                }
            }

            MediaPlayer.Event.Paused -> updatePlayPauseIcon()

            MediaPlayer.Event.LengthChanged -> {
                val length = player?.length ?: 0L
                // RTSP replay bisa mengirim LengthChanged(0); jangan timpa durasi klip.
                if (length > 0L) {
                    durationMillis = length
                    durationView.text = RecordingPlaybackTime.format(durationMillis)
                }
            }

            MediaPlayer.Event.TimeChanged -> {
                if (seeking) return
                val time = player?.time ?: 0L
                currentView.text = RecordingPlaybackTime.format(time)
                seekBar.progress = if (durationMillis > 0L) {
                    ((time.toFloat() / durationMillis.toFloat()) * seekBar.max).toInt()
                        .coerceIn(0, seekBar.max)
                } else {
                    0
                }
            }

            MediaPlayer.Event.EndReached -> {
                playingSeen = false
                finished = true
                currentView.text = RecordingPlaybackTime.format(durationMillis)
                seekBar.progress = seekBar.max
                updatePlayPauseIcon()
                showOverlay("Selesai")
            }

            MediaPlayer.Event.EncounteredError -> {
                playingSeen = false
                showOverlay("Gagal memutar rekaman")
            }
        }
    }

    private fun updatePlayPauseIcon() {
        val playing = player?.isPlaying == true && !finished
        playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun showOverlay(message: String) {
        overlay.text = message
        overlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
    }

    private fun releasePlayer() {
        val active = player ?: return
        try {
            active.stop()
        } catch (_: Throwable) {
        }
        if (viewsAttached) {
            try {
                active.detachViews()
            } catch (_: Throwable) {
            }
        }
        try {
            active.release()
        } catch (_: Throwable) {
        }
        player = null
        viewsAttached = false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun String?.orBlankOr(fallback: String): String =
        if (isNullOrBlank()) fallback else this
}
