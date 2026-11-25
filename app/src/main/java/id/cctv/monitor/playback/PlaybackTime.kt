package id.cctv.monitor.playback

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object PlaybackTime {

    private val utcZone = TimeZone.getTimeZone("UTC")
    private const val STAMP_PATTERN = "yyyyMMdd'T'HHmmss'Z'"
    private const val XML_STAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    /** Format dipakai RTSP replay Hikvision (query starttime/endtime). */
    fun utcStamp(millis: Long): String = format(STAMP_PATTERN, millis)

    /** Format dipakai ISAPI XML (CMSearchDescription). */
    fun xmlStamp(millis: Long): String = format(XML_STAMP_PATTERN, millis)

    fun parseStamp(value: String): Long? =
        parse(XML_STAMP_PATTERN, value) ?: parse(STAMP_PATTERN, value)

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

    fun dayLabel(dayMillis: Long, nowMillis: Long, zone: TimeZone): String {
        if (dayRange(dayMillis, zone).first == dayRange(nowMillis, zone).first) {
            return "Hari ini"
        }
        return SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
            .apply { timeZone = zone }
            .format(Date(dayMillis))
    }

    private fun format(pattern: String, millis: Long): String =
        stampFormat(pattern).format(Date(millis))

    private fun parse(pattern: String, value: String): Long? = try {
        stampFormat(pattern).apply { isLenient = false }.parse(value.trim())?.time
    } catch (e: Exception) {
        null
    }

    private fun stampFormat(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = utcZone }
}
