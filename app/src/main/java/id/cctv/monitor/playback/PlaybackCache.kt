package id.cctv.monitor.playback

import java.io.File

object PlaybackCache {

    private const val DIRECTORY = "playback"

    fun directory(cacheDir: File): File = File(cacheDir, DIRECTORY)

    fun fileFor(cacheDir: File, clip: RecordingClip): File =
        File(directory(cacheDir), "nvr-${clip.trackId}-${clip.startMillis}.mp4")
}
