package id.cctv.monitor.player

import id.cctv.monitor.playback.SeekMode

data class RecordingMedia(val mrl: String, val seekMode: SeekMode)

/** Satu sumber media untuk pemutar: URI RTSP menang atas path file lokal. */
object RecordingMediaResolver {

    fun resolve(mediaUri: String?, filePath: String?): RecordingMedia = when {
        !mediaUri.isNullOrBlank() -> RecordingMedia(mediaUri.trim(), SeekMode.DISABLED)
        !filePath.isNullOrBlank() -> RecordingMedia(filePath.trim(), SeekMode.ENABLED)
        else -> RecordingMedia("", SeekMode.ENABLED)
    }
}
