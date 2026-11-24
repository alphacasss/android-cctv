package id.cctv.monitor.playback

import id.cctv.monitor.data.Camera
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class PlaybackUiTest {
    @Test fun nvrCameras_onlyEnabledNvr() {
        val cameras = listOf(
            Camera(1, "IP", "192.168.1.20", 1, false, true, "", ""),
            Camera(2, "NVR mati", "192.168.1.64", 2, true, false, "", ""),
            Camera(3, "NVR", "192.168.1.64", 3, true, true, "", "")
        )
        assertEquals(listOf(3), PlaybackUi.nvrCameras(cameras).map { it.id })
    }

    @Test fun rowLabel_usesLocalTimeAndCameraName() {
        val clip = RecordingClip(101, 1_795_478_400_000L, 1_795_478_700_000L)
        val camera = Camera(1, "NVR Depan", "192.168.1.64", 1, true, true, "", "")
        assertEquals(
            "07:00–07:05 • NVR Depan",
            PlaybackUi.rowLabel(clip, camera, TimeZone.getTimeZone("Asia/Jakarta"))
        )
    }
}
