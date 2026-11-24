package id.cctv.monitor.player

import java.util.Locale

/** Util murni untuk label waktu dan seek slider pemutar rekaman. */
object RecordingPlaybackTime {

    fun format(millis: Long): String {
        val total = (millis.coerceAtLeast(0L) / 1000L).toInt()
        val seconds = total % 60
        val minutes = (total / 60) % 60
        val hours = total / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun seekMillis(durationMillis: Long, progress: Int, max: Int): Long {
        if (durationMillis <= 0L || max <= 0) return 0L
        val ratio = progress.coerceIn(0, max).toFloat() / max.toFloat()
        return (durationMillis.toFloat() * ratio).toLong()
    }
}
