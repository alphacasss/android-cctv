package id.cctv.monitor.playback

/** Jenis rekaman yang dipakai filter timeline. */
enum class PlaybackEventKind {
    CONTINUOUS, EVENT, PERSON, VEHICLE;

    companion object {
        fun from(raw: String?): PlaybackEventKind {
            val value = raw?.trim()?.lowercase().orEmpty()
            return when {
                value.contains("person") || value.contains("face") -> PERSON
                value.contains("vehicle") || value.contains("car") -> VEHICLE
                value.isEmpty() -> CONTINUOUS
                else -> EVENT
            }
        }
    }
}
