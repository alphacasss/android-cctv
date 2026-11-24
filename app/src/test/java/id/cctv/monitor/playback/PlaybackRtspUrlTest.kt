package id.cctv.monitor.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRtspUrlTest {
    @Test fun build_encodesCredentials() {
        val url = PlaybackRtspUrl.build(
            101, "admin", "p@ss:w ord", "192.168.1.64", 8554,
            "20261123T170000Z", "20261123T170500Z"
        )
        assertEquals(
            "rtsp://admin:p%40ss%3Aw%20ord@192.168.1.64:8554/Streaming/tracks/101" +
                "?starttime=20261123T170000Z&endtime=20261123T170500Z",
            url
        )
    }
}
