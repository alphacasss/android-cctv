package id.cctv.monitor

import android.net.Uri
import android.os.Bundle
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
        const val EXTRA_TITLE = "recording_title"
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
    }

    override fun onStop() {
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
        val path = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        val file = File(path)
        if (path.isBlank() || !file.isFile) {
            Toast.makeText(this, "File rekaman tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            val libVlc = SharedVlc.get(applicationContext)
            val active = MediaPlayer(libVlc)
            active.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
            active.setEventListener { event -> onPlayerEvent(event.type) }

            val media = Media(libVlc, Uri.fromFile(file))
            media.addOption(":no-video-title-show")
            media.addOption(":file-caching=300")

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

    private fun onPlayerEvent(type: Int) {
        when (type) {
            MediaPlayer.Event.Opening,
            MediaPlayer.Event.Buffering -> showOverlay("Memuat…")

            MediaPlayer.Event.Playing -> {
                finished = false
                hideOverlay()
                updatePlayPauseIcon()
            }

            MediaPlayer.Event.Paused -> updatePlayPauseIcon()

            MediaPlayer.Event.LengthChanged -> {
                durationMillis = player?.length ?: 0L
                durationView.text = RecordingPlaybackTime.format(durationMillis)
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
                finished = true
                currentView.text = RecordingPlaybackTime.format(durationMillis)
                seekBar.progress = seekBar.max
                updatePlayPauseIcon()
                showOverlay("Selesai")
            }

            MediaPlayer.Event.EncounteredError -> showOverlay("Gagal memutar rekaman")
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
