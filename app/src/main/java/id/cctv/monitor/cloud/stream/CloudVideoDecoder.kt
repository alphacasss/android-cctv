package id.cctv.monitor.cloud.stream

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface

/**
 * Decoder MediaCodec untuk access unit Annex-B (H.264/H.265) hasil
 * [StreamDemuxer]. Satu instance dipakai per tile; semua panggilan harus dari
 * thread yang sama.
 */
internal class CloudVideoDecoder(
    private val surface: Surface,
    private val listener: Listener
) {

    interface Listener {
        fun onConfigured(width: Int, height: Int)
        fun onFrameRendered()
        fun onError(message: String)
    }

    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var lastPtsUs = 0L
    private var firstPtsUs = -1L
    private var loggedWait = false
    private val info = MediaCodec.BufferInfo()
    private var framesIn = 0
    private var framesOut = 0
    private var tryAgain = 0
    private var lastRenderLog = System.currentTimeMillis()

    /**
     * True setelah decoder gagal dikonfigurasi atau macet. Pemutar harus
     * berhenti mengirim frame: mencoba ulang tiap unit hanya mengulang error
     * yang sama dan membebani thread IO.
     */
    @Volatile
    private var failed = false

    @Volatile
    private var released = false

    /** True bila decoder tidak bisa dipakai lagi (gagal atau sudah dilepas). */
    val unusable: Boolean get() = failed || released

    /** Parameter set yang sudah terkumpul lintas access unit (tipe -> NAL). */
    private val csdParts = HashMap<Int, ByteArray>()

    val configured: Boolean get() = codec != null

    /**
     * Umpan satu access unit. [prime] berisi parameter set lengkap dari
     * demuxer (bila parameter set asli hanya dikirim sebelum kunci siap).
     */
    fun feed(unit: VideoUnit, prime: ByteArray? = null) {
        if (released) return
        if (failed) return
        if (codec == null) {
            val mime = if (unit.codec == "h265") "video/hevc" else "video/avc"
            if (prime != null) collect(prime, unit.codec)
            collect(unit.data, unit.codec)
            val sets = parameterSets(unit.codec)
            if (sets == null) {
                // Parameter set (SPS/PPS/VPS) belum lengkap; tunggu keyframe.
                if (!loggedWait) {
                    loggedWait = true
                    android.util.Log.d(
                        "CloudDecoder",
                        "tunggu parameter set; tipe=${AnnexB.nalTypes(unit.data, unit.codec)} " +
                            "len=${unit.data.size} punya=${csdParts.keys}"
                    )
                }
                return
            }
            try {
                configure(mime, sets)
            } catch (e: Exception) {
                fail("Decoder ${unit.codec.uppercase()} tidak tersedia: ${e.message}")
                return
            }
            csdParts.clear()
        }
        val decoder = codec ?: return
        val index = try {
            decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (e: Exception) {
            fail("Decoder gagal menerima frame: ${e.message}")
            return
        }
        if (index >= 0) {
            val buffer = decoder.getInputBuffer(index) ?: return
            buffer.clear()
            if (buffer.capacity() < unit.data.size) {
                // Frame lebih besar dari buffer codec; lewati daripada crash.
                decoder.queueInputBuffer(index, 0, 0, 0, 0)
            } else {
                buffer.put(unit.data)
                decoder.queueInputBuffer(index, 0, unit.data.size, ptsUs(unit.pts), 0)
                framesIn++
            }
        }
        drain()
        logRenderStats()
    }

    private fun configure(mime: String, sets: ParameterSets) {
        val format = MediaFormat.createVideoFormat(mime, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 25)
        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sets.first))
        sets.second?.let { format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(it)) }
        android.util.Log.d(
            "CloudDecoder",
            "configure $mime csd0=${sets.first.size} ${head(sets.first)} " +
                "csd1=${sets.second?.size ?: 0}"
        )
        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && mime in it.supportedTypes }
            .map { it.name }
        android.util.Log.d("CloudDecoder", "available $mime codecs=$all")
        val preferred = all.firstOrNull {
            it.contains("google", ignoreCase = true) || it.contains("android", ignoreCase = true)
        }
        val decoder = if (preferred != null) {
            MediaCodec.createByCodecName(preferred).also {
                android.util.Log.d("CloudDecoder", "using software decoder $preferred")
            }
        } else {
            MediaCodec.createDecoderByType(mime)
        }
        decoder.configure(format, surface, null, 0)
        decoder.start()
        codec = decoder
    }

    private fun head(data: ByteArray): String {
        val count = minOf(24, data.size)
        val out = StringBuilder(count * 2)
        for (i in 0 until count) out.append("%02x".format(data[i]))
        return out.toString()
    }

    private fun drain() {
        val decoder = codec ?: return
        if (failed) return
        while (true) {
            val index = try {
                decoder.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                fail("Decoder gagal mengeluarkan frame: ${e.message}")
                return
            }
            when {
                index >= 0 -> {
                    decoder.releaseOutputBuffer(index, true)
                    framesOut++
                    listener.onFrameRendered()
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = decoder.outputFormat
                    val w = output.getInteger(MediaFormat.KEY_WIDTH, DEFAULT_WIDTH)
                    val h = output.getInteger(MediaFormat.KEY_HEIGHT, DEFAULT_HEIGHT)
                    // Beberapa decoder menaruh crop di sini.
                    val cropW = if (output.containsKey("crop-right")) {
                        output.getInteger("crop-right") - output.getInteger("crop-left", 0) + 1
                    } else {
                        w
                    }
                    val cropH = if (output.containsKey("crop-bottom")) {
                        output.getInteger("crop-bottom") - output.getInteger("crop-top", 0) + 1
                    } else {
                        h
                    }
                    if (cropW > 0 && cropH > 0 && (cropW != width || cropH != height)) {
                        width = cropW
                        height = cropH
                        listener.onConfigured(width, height)
                    }
                }

                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    tryAgain++
                    return
                }
            }
        }
    }

    private fun logRenderStats() {
        val now = System.currentTimeMillis()
        if (now - lastRenderLog < 5_000L) return
        lastRenderLog = now
        android.util.Log.d(
            "CloudDecoder",
            "render in=$framesIn out=$framesOut retry=$tryAgain codec=${codec?.name}"
        )
        framesIn = 0
        framesOut = 0
        tryAgain = 0
    }

    private fun ptsUs(pts: Long): Long {
        // PTS relay dalam 90 kHz.
        val us = if (pts > 0) pts * 1_000_000L / 90_000L else 0L
        if (firstPtsUs < 0) {
            firstPtsUs = us
            lastPtsUs = us
            return 0L
        }
        // Tangani pembungkusan 32-bit dan lompatan waktu yang tak wajar.
        var value = us
        if (value < lastPtsUs - 5_000_000L) value += 0x1_0000_0000L * 1_000_000L / 90_000L
        if (value < lastPtsUs) value = lastPtsUs + 1_000L
        lastPtsUs = value
        return (value - firstPtsUs).coerceAtLeast(0L)
    }

    private data class ParameterSets(val first: ByteArray, val second: ByteArray?)

    /** Kumpulkan parameter set dari buffer Annex-B (dengan start code). */
    private fun collect(data: ByteArray, codec: String) {
        val hevc = codec == "h265"
        for (range in AnnexB.spans(data)) {
            val start = range.first
            val end = range.last + 1
            if (start >= end) continue
            val header = data[start].toInt() and 0xFF
            val type = if (hevc) (header ushr 1) and 0x3F else header and 0x1F
            val wanted = if (hevc) type in 32..34 else type == 7 || type == 8
            if (!wanted) continue
            val nal = ByteArray(4 + end - start)
            System.arraycopy(AnnexB.START_CODE, 0, nal, 0, 4)
            System.arraycopy(data, start, nal, 4, end - start)
            csdParts[type] = nal
        }
    }

    /**
     * Parameter set lengkap. H.265: csd-0 = VPS+SPS+PPS. H.264: csd-0 = SPS,
     * csd-1 = PPS.
     */
    private fun parameterSets(codec: String): ParameterSets? {
        val hevc = codec == "h265"
        if (hevc) {
            if (intArrayOf(32, 33, 34).any { csdParts[it] == null }) return null
            val combined = java.io.ByteArrayOutputStream()
            // Urutan baku: VPS (32), SPS (33), PPS (34).
            for (type in intArrayOf(32, 33, 34)) combined.write(csdParts.getValue(type))
            return ParameterSets(combined.toByteArray(), null)
        }
        val sps = csdParts[7] ?: return null
        val pps = csdParts[8] ?: return null
        return ParameterSets(sps, pps)
    }

    /** Laporkan kegagalan sekali saja; pemanggil berhenti lewat [unusable]. */
    private fun fail(message: String) {
        if (failed || released) return
        failed = true
        listener.onError(message)
    }

    fun release() {
        released = true
        val decoder = codec ?: return
        codec = null
        try {
            decoder.stop()
        } catch (_: Exception) {
        }
        try {
            decoder.release()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 20_000L
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
    }
}
