package id.cctv.monitor.playback

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object PlaybackTime {

    private val utcZone = TimeZone.getTimeZone("UTC")
    private const val STAMP_PATTERN = "yyyyMMdd'T'HHmmss'Z'"

    fun utcStamp(millis: Long): String = stampFormat().format(Date(millis))

    fun parseStamp(value: String): Long? = try {
        stampFormat().apply { isLenient = false }.parse(value.trim())?.time
    } catch (e: Exception) {
        null
    }

    fun dayRange(dayMillis: Long, zone: TimeZone): Pair<Long, Long> {
        val calendar = Calendar.getInstance(zone, Locale.US)
        calendar.timeInMillis = dayMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis
        calendar.add(Calendar.DATE, 1)
        return start to calendar.timeInMillis
    }

    fun label(startMillis: Long, endMillis: Long, zone: TimeZone): String {
        val format = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }
        return "${format.format(Date(startMillis))}–${format.format(Date(endMillis))}"
    }

    private fun stampFormat(): SimpleDateFormat =
        SimpleDateFormat(STAMP_PATTERN, Locale.US).apply { timeZone = utcZone }
}
