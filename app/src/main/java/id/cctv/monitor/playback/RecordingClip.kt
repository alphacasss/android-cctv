package id.cctv.monitor.playback

data class RecordingClip(
    val trackId: Int,
    val startMillis: Long,
    val endMillis: Long,
    val playbackUri: String = "",
    val eventKind: PlaybackEventKind = PlaybackEventKind.CONTINUOUS
)
