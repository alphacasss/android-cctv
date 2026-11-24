package id.cctv.monitor.playback

import id.cctv.monitor.net.Endpoint

data class PlaybackSearchResult(
    val clips: List<RecordingClip>,
    val directReachable: Boolean,
    val cloudAvailable: Boolean,
    val endpoint: Endpoint,
    val startUtc: String,
    val endUtc: String
)
