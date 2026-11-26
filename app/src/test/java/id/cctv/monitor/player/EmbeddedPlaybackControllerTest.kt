package id.cctv.monitor.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddedPlaybackControllerTest {

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

    @Test fun playAfterEndReachedKeepsPendingOffset() {
        val media = FakeMedia()
        val controller = EmbeddedPlaybackController(media, { it() }, { })
        controller.load(tempFile(), 90_000L)
        media.emit(EmbeddedPlaybackEvent.END_REACHED)

        controller.playPause()
        media.emit(EmbeddedPlaybackEvent.PLAYING)

        assertEquals(90_000L, media.time)
        assertTrue(media.events.contains("play"))
    }

    @Test fun releaseRemovesEventListener() {
        val media = FakeMedia()
        val controller = EmbeddedPlaybackController(media, { it() }, { })
        controller.load(tempFile(), 0L)
        assertFalse(media.listener == null)

        controller.release()

        assertNull(media.listener)
        assertTrue(media.events.contains("detach"))
        assertTrue(media.events.contains("release"))
    }

    private fun tempFile(): File =
        File.createTempFile("embedded-playback", ".mp4").apply { deleteOnExit() }
            .also { it.writeBytes(byteArrayOf(0, 0, 0, 8, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())) }

    private class FakeMedia : EmbeddedPlaybackMedia {
        override var listener: ((EmbeddedPlaybackEvent) -> Unit)? = null
        override var time: Long = 0L
            set(value) {
                field = value
                events += "time=$value"
            }
        override val length: Long = 120_000L
        val events = mutableListOf<String>()

        override fun attachViews() = Unit

        override fun detachViews() {
            events += "detach"
        }

        override fun setMedia(file: File) {
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
            listener?.invoke(event)
        }
    }
}
