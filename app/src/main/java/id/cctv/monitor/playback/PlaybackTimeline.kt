package id.cctv.monitor.playback

/** Aturan pemilihan klip pada timeline 24 jam. Murni JVM, tanpa Android. */
object PlaybackTimeline {

    fun ratio(millis: Long, dayStart: Long, dayEnd: Long): Float {
        if (dayEnd <= dayStart) return 0f
        val raw = (millis - dayStart).toFloat() / (dayEnd - dayStart).toFloat()
        return raw.coerceIn(0f, 1f)
    }

    fun clipAt(
        clips: List<RecordingClip>,
        ratio: Float,
        dayStart: Long,
        dayEnd: Long
    ): RecordingClip? {
        if (clips.isEmpty()) return null
        val span = (dayEnd - dayStart).coerceAtLeast(0L)
        val millis = dayStart + (ratio.coerceIn(0f, 1f) * span.toFloat()).toLong()
        // Interval setengah terbuka: tepat di batas klip pilih klip berikutnya.
        val covering = clips.filter { millis >= it.startMillis && millis < it.endMillis }
        preferred(covering)?.let { return it }
        val endedHere = clips.filter { it.endMillis == millis }
        preferred(endedHere)?.let { return it }
        return clips.minByOrNull { distance(it, millis) }
    }

    fun index(
        clips: List<RecordingClip>,
        ratio: Float,
        dayStart: Long,
        dayEnd: Long
    ): Int {
        val picked = clipAt(clips, ratio, dayStart, dayEnd) ?: return -1
        return clips.indexOf(picked)
    }

    fun filter(clips: List<RecordingClip>, filter: PlaybackFilter): List<RecordingClip> =
        if (filter == PlaybackFilter.ALL) {
            clips
        } else {
            clips.filter { it.eventKind.name == filter.name }
        }

    fun step(size: Int, index: Int, delta: Int): Int {
        if (size <= 0) return -1
        return (index + delta).coerceIn(0, size - 1)
    }

    /** Indeks dan jam tujuan tombol « ». Jam tujuan jadi state untuk tombol berikutnya. */
    fun stepState(
        clips: List<RecordingClip>,
        millis: Long,
        dayStart: Long,
        dayEnd: Long,
        delta: Int
    ): Pair<Int, Long>? {
        val current = index(clips, ratio(millis, dayStart, dayEnd), dayStart, dayEnd)
        val next = step(clips.size, current, delta)
        if (next < 0) return null
        return next to clips[next].startMillis
    }

    fun pickedMillis(clip: RecordingClip, requested: Long): Long =
        requested.coerceIn(clip.startMillis, clip.endMillis)

    /** Tap persis di ujung klip jangan sampai bikin query RTSP start == end. */
    fun safePicked(clip: RecordingClip, requested: Long): Long {
        val picked = pickedMillis(clip, requested)
        return if (picked >= clip.endMillis && clip.endMillis > clip.startMillis) {
            clip.startMillis
        } else {
            picked
        }
    }

    fun seekOffset(clip: RecordingClip, requested: Long): Long =
        (pickedMillis(clip, requested) - clip.startMillis).coerceAtLeast(0L)

    private fun preferred(candidates: List<RecordingClip>): RecordingClip? =
        candidates.sortedWith(
            compareByDescending<RecordingClip> { priority(it.eventKind) }
                .thenBy { it.endMillis - it.startMillis }
        ).firstOrNull()

    private fun priority(kind: PlaybackEventKind): Int = when (kind) {
        PlaybackEventKind.PERSON -> 3
        PlaybackEventKind.VEHICLE -> 2
        PlaybackEventKind.EVENT -> 1
        PlaybackEventKind.CONTINUOUS -> 0
    }

    private fun distance(clip: RecordingClip, millis: Long): Long = when {
        millis < clip.startMillis -> clip.startMillis - millis
        millis > clip.endMillis -> millis - clip.endMillis
        else -> 0L
    }
}
