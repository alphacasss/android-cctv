package id.cctv.monitor.playback

import id.cctv.monitor.data.Camera
import java.util.TimeZone

/** Aturan tampil halaman playback yang bisa diuji tanpa Android. */
object PlaybackUi {

    fun nvrCameras(cameras: List<Camera>): List<Camera> =
        cameras.filter { it.isNvr && it.enabled }

    fun rowLabel(clip: RecordingClip, camera: Camera, zone: TimeZone): String =
        "${PlaybackTime.label(clip.startMillis, clip.endMillis, zone)} • ${camera.displayName}"
}
