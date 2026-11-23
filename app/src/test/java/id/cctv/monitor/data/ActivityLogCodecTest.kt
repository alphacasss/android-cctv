package id.cctv.monitor.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regresi karaker khusus dan batas penyimpanan log aktivitas. */
class ActivityLogCodecTest {

    @Test
    fun roundTrip_menjagaKarakterKhusus() {
        val entry = ActivityLogEntry(
            at = 1700000000123L,
            title = "MATIKAN|RTSP",
            detail = "baris\nbaru\\akhir"
        )

        assertEquals(
            listOf(entry),
            ActivityLogCodec.decode(ActivityLogCodec.encode(listOf(entry)))
        )
    }

    @Test
    fun decode_mengabaikanBarisRusak() {
        val raw = "1700000000000|OK|halo\nbukan|log"

        assertEquals(
            listOf(ActivityLogEntry(1700000000000L, "OK", "halo")),
            ActivityLogCodec.decode(raw)
        )
    }

    @Test
    fun append_menyimpanTigaPuluhEntriTerbaru() {
        val raw = ActivityLogCodec.encode(
            (30L downTo 1L).map { ActivityLogEntry(it, "T$it", "d$it") }
        )

        val result = ActivityLogCodec.decode(
            ActivityLogCodec.append(raw, ActivityLogEntry(31L, "BARU", "x"))
        )

        assertEquals(30, result.size)
        assertEquals(31L, result.first().at)
        assertEquals(2L, result.last().at)
    }
}
