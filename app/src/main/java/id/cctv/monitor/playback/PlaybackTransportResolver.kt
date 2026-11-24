package id.cctv.monitor.playback

enum class PlaybackTransport { STREAM_RTSP, DOWNLOAD_CACHE, ERROR_NO_ROUTE }

enum class SeekMode { ENABLED, DISABLED }

object PlaybackTransportResolver {

    fun resolve(directReachable: Boolean, cloudAvailable: Boolean): PlaybackTransport = when {
        directReachable -> PlaybackTransport.STREAM_RTSP
        cloudAvailable -> PlaybackTransport.DOWNLOAD_CACHE
        else -> PlaybackTransport.ERROR_NO_ROUTE
    }

    fun seekMode(transport: PlaybackTransport): SeekMode = when (transport) {
        PlaybackTransport.DOWNLOAD_CACHE -> SeekMode.ENABLED
        else -> SeekMode.DISABLED
    }
}
