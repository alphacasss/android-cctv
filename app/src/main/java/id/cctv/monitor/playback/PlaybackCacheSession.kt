package id.cctv.monitor.playback

import java.io.File

data class PlaybackClipKey(val cameraId: Int, val trackId: Int, val startMillis: Long)

enum class PlaybackCacheAction { REUSE, DOWNLOAD }

data class PlaybackCachePlan(val action: PlaybackCacheAction, val offsetMillis: Long)

/** Keputusan cache murni: klip sama boleh di-seek, klip beda harus diunduh. */
class PlaybackCacheSession {

    private var key: PlaybackClipKey? = null
    private var file: File? = null

    fun plan(
        key: PlaybackClipKey,
        clip: RecordingClip,
        requestedMillis: Long
    ): PlaybackCachePlan {
        val picked = PlaybackTimeline.safePicked(clip, requestedMillis)
        val offsetMillis = PlaybackTimeline.seekOffset(clip, picked)
        val action = if (this.key == key && file != null) {
            PlaybackCacheAction.REUSE
        } else {
            PlaybackCacheAction.DOWNLOAD
        }
        return PlaybackCachePlan(action, offsetMillis)
    }

    fun adopt(key: PlaybackClipKey, file: File): File? {
        val previous = this.file
        this.key = key
        this.file = file
        return previous?.takeIf { it != file }
    }

    fun reset() {
        key = null
        file = null
    }

    fun loadedFile(): File? = file

    fun loadedKey(): PlaybackClipKey? = key
}
