package id.cctv.monitor.motion

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Perhitungan selisih frame untuk deteksi gerakan.
 *
 * Frame diperkecil ke [WIDTH]x[HEIGHT] grayscale lalu dibagi menjadi blok.
 * Deteksi berbasis blok jauh lebih peka terhadap objek kecil (orang berjalan)
 * dibanding rata-rata seluruh frame, dan tahan terhadap noise sensor.
 */
object MotionMath {

    const val WIDTH = 64
    const val HEIGHT = 36
    const val BLOCK_COLS = 8
    const val BLOCK_ROWS = 4
    const val MIN_ACTIVE_BLOCKS = 2

    private const val PIXEL_THRESHOLD = 18
    private const val BLOCK_MEAN_THRESHOLD = 7.0
    private const val BLOCK_CHANGED_RATIO = 0.18

    data class Stats(
        val mean: Double,
        val activeBlocks: Int,
        val totalBlocks: Int,
        val maxBlockMean: Double,
        val changedPixels: Int,
        val totalPixels: Int
    ) {
        val moving: Boolean get() = activeBlocks >= MIN_ACTIVE_BLOCKS
    }

    /** JPEG -> grayscale ukuran tetap. Null bila gambar tidak bisa dibaca. */
    fun grayscale(jpeg: ByteArray): ByteArray? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 4
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        } catch (t: Throwable) {
            null
        } ?: return null
        return try {
            val scaled = Bitmap.createScaledBitmap(decoded, WIDTH, HEIGHT, true)
            val pixels = IntArray(WIDTH * HEIGHT)
            scaled.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            val out = ByteArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[i] = ((r * 77 + g * 151 + b * 28) shr 8).toByte()
            }
            if (scaled !== decoded) scaled.recycle()
            out
        } catch (t: Throwable) {
            null
        } finally {
            decoded.recycle()
        }
    }

    /** Bandingkan dua frame grayscale. Null bila ukuran tidak cocok. */
    fun diff(prev: ByteArray, frame: ByteArray): Stats? {
        val total = WIDTH * HEIGHT
        if (prev.size != total || frame.size != total) return null

        val blockW = WIDTH / BLOCK_COLS
        val blockH = HEIGHT / BLOCK_ROWS
        val blockCount = BLOCK_COLS * BLOCK_ROWS
        val sums = IntArray(blockCount)
        val changed = IntArray(blockCount)
        var totalSum = 0
        var totalChanged = 0

        for (y in 0 until HEIGHT) {
            val rowBlock = (y / blockH) * BLOCK_COLS
            for (x in 0 until WIDTH) {
                val i = y * WIDTH + x
                val d = kotlin.math.abs(
                    (frame[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)
                )
                val b = rowBlock + (x / blockW)
                sums[b] += d
                totalSum += d
                if (d >= PIXEL_THRESHOLD) {
                    changed[b]++
                    totalChanged++
                }
            }
        }

        val perBlock = blockW * blockH
        var activeBlocks = 0
        var maxBlockMean = 0.0
        for (b in 0 until blockCount) {
            val blockMean = sums[b].toDouble() / perBlock
            val ratio = changed[b].toDouble() / perBlock
            if (blockMean > maxBlockMean) maxBlockMean = blockMean
            if (blockMean >= BLOCK_MEAN_THRESHOLD || ratio >= BLOCK_CHANGED_RATIO) {
                activeBlocks++
            }
        }

        return Stats(
            mean = totalSum.toDouble() / total,
            activeBlocks = activeBlocks,
            totalBlocks = blockCount,
            maxBlockMean = maxBlockMean,
            changedPixels = totalChanged,
            totalPixels = total
        )
    }
}
