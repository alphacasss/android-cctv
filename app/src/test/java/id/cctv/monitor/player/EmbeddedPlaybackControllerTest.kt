package id.cctv.monitor.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddedPlaybackControllerTest {

    @Test fun playBeforeFirstLoadDoesNotTouchMedia() {
        val media = FakeMedia()
        val controller = EmbeddedPlaybackController(media, { it() }, { })

        controller.playPause()
        controller.resume()

        assertTrue(media.events.isEmpty())
    }

    @Test fun seekWhilePausedAppliesTimeToSamePlayer() {
        val media = FakeMedia()
        val controller = EmbeddedPlaybackController(media, { it() }, { })
        controller.load(tempFile(), 0L)
        media.emit(EmbeddedPlaybackEvent.PLAYING)
        media.emit(EmbeddedPlaybackEvent.PAUSED)

        controller.seek(65_000L)

        assertEquals(65_000L, media.time)
        assertEquals(1, media.events.count { it.startsWith("setMedia:") })
    }

    @Test fun seekBeforeFirstFrameDefersUntilPlaying() {
        val media = FakeMedia()
        val controller = EmbeddedPlaybackController(media, { it() }, { })
        controller.load(tempFile(), 60_000L)

        controller.seek(75_000L)
        media.emit(EmbeddedPlaybackEvent.PLAYING)

        assertEquals(75_000L, media.time)
    }

    @Test fun seekAfterEndReachedReplaysAndAppliesRequestedTime() {
        val media = FakeMedia()
        val controller = EmbeddedPlaybackController(media, { it() }, { })
        controller.load(tempFile(), 0L)
        media.emit(EmbeddedPlaybackEvent.PLAYING)
        media.emit(EmbeddedPlaybackEvent.END_REACHED)

        controller.seek(90_000L)
        media.emit(EmbeddedPlaybackEvent.PLAYING)

        assertTrue(media.events.contains("stop"))
        assertTrue(media.events.contains("play"))
        assertEquals(90_000L, media.time)
    }

    @Test fun rapidPlayPauseTogglesSynchronously() {
        val media = FakeMedia()
        val controller = EmbeddedPlaybackController(media, { it() }, { })
        controller.load(tempFile(), 0L)
        media.emit(EmbeddedPlaybackEvent.PLAYING)

        controller.playPause()
        assertFalse(controller.isPlaying)
        controller.playPause()
        assertTrue(controller.isPlaying)
        assertEquals(2, media.events.count { it == "play" })
        assertEquals(1, media.events.count { it == "pause" })
    }

    @Test fun releaseIgnoresEventsDispatchedAfterDestroy() {
        val media = FakeMedia()
        val states = mutableListOf<EmbeddedPlaybackUiState>()
        val queued = mutableListOf<() -> Unit>()
        val controller = EmbeddedPlaybackController(media, { queued += it }, states::add)
        controller.load(tempFile(), 0L)
        queued.forEach { it() }
        media.emit(EmbeddedPlaybackEvent.PLAYING)
        queued.forEach { it() }
        media.emit(EmbeddedPlaybackEvent.END_REACHED)

        controller.release()
        val afterRelease = states.size
        queued.forEach { it() }

        assertNull(media.listener)
        assertEquals(afterRelease, states.size)
        assertTrue(media.events.contains("detach"))
        assertTrue(media.events.contains("release"))
    }

    private fun tempFile(): File =
        File.createTempFile("embedded-playback", ".mp4").apply { deleteOnExit() }
            .also { it.writeBytes(byteArrayOf(0, 0, 0, 8, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())) }

    private class FakeMedia : EmbeddedPlaybackMedia {
        override var listener: ((EmbeddedPlaybackEvent) -> Unit)? = null
        private var frameReady = false
        override var time: Long = 0L
            set(value) {
                if (!frameReady) {
                    events += "timeIgnored=$value"
                } else {
                    field = value
                    events += "time=$value"
                }
            }
        override val length: Long = 120_000L
        val events = mutableListOf<String>()

        override fun attachViews() = Unit

        override fun detachViews() {
            events += "detach"
        }

        override fun setMedia(file: File) {
            frameReady = false
            events += "setMedia:${file.name}"
        }

        override fun play() {
            events += "play"
        }

        override fun pause() {
            events += "pause"
        }

        override fun stop() {
            events += "stop"
        }

        override fun release() {
            events += "release"
        }

        fun emit(event: EmbeddedPlaybackEvent) {
            if (event == EmbeddedPlaybackEvent.PLAYING || event == EmbeddedPlaybackEvent.VOUT) {
                frameReady = true
            }
            listener?.invoke(event)
        }
    }
}
