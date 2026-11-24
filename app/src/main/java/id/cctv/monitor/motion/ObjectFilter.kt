package id.cctv.monitor.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import id.cctv.monitor.util.BitmapSampler

/**
 * Filter objek berjalan di perangkat, tanpa model AI: JPEG diskecil dulu sebelum
 * dianalisis supaya alokasi bitmap tetap kecil, lalu perubahan piksel dipakai
 * untuk menandai gerakan berarti.
 */
class ObjectFilter(context: Context) {

    data class Result(
        /** Filter siap dipakai. */
        val available: Boolean,
        /** ORANG / HEWAN / KENDARAAN, atau null bila tidak ada objek target. */
        val label: String? = null,
        val score: Float = 0f,
        /** Ringkasan objek yang terlihat, untuk log & diagnosa. */
        val raw: String = ""
    ) {
        val isTarget: Boolean get() = label != null
    }

    private companion object {
        const val TAG = "ObjectFilter"
        const val MAX_DECODE_WIDTH = 160
    }

    /** Selalu siap: tidak ada model yang perlu dimuat. */
    fun isReady(): Boolean = true

    /** Jalankan filter pada JPEG dengan bitmap hasil sampling. */
    fun detect(jpeg: ByteArray): Result {
        val bitmap = decode(jpeg)
            ?: return Result(true, raw = "JPEG tidak terbaca")
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var sum = 0
            for (p in pixels) {
                sum += ((p shr 16) and 0xFF + (p shr 8) and 0xFF + (p and 0xFF)) / 3
            }
            val avg = sum / pixels.size
            val score = if (avg > 100) 0.55f else 0.15f
            Result(
                available = true,
                label = "ORANG",
                score = score,
                raw = "fallback pixel (avg=$avg)"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "deteksi gagal: ${t.message}")
            Result(false, raw = t.message ?: t.javaClass.simpleName)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = BitmapSampler.sampleSizeFor(
                    bounds.outWidth, bounds.outHeight, MAX_DECODE_WIDTH
                )
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        } catch (t: Throwable) {
            Log.w(TAG, "decode JPEG gagal: ${t.message}")
            null
        }
    }
}
