package id.cctv.monitor.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTransportResolverTest {
    @Test fun choices_areDeterministic() {
        assertEquals(PlaybackTransport.STREAM_RTSP, PlaybackTransportResolver.resolve(true, false))
        assertEquals(PlaybackTransport.DOWNLOAD_CACHE, PlaybackTransportResolver.resolve(false, true))
        assertEquals(PlaybackTransport.ERROR_NO_ROUTE, PlaybackTransportResolver.resolve(false, false))
    }

    @Test fun seekMode_disabledForRtsp() {
        assertEquals(SeekMode.DISABLED, PlaybackTransportResolver.seekMode(PlaybackTransport.STREAM_RTSP))
        assertEquals(SeekMode.ENABLED, PlaybackTransportResolver.seekMode(PlaybackTransport.DOWNLOAD_CACHE))
    }
}
