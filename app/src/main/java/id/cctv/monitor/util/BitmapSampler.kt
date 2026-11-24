package id.cctv.monitor.util

/** Power-of-two sample size untuk BitmapFactory. */
object BitmapSampler {

    fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, maxDimension: Int): Int {
        val largest = maxOf(sourceWidth, sourceHeight)
        if (largest <= 0 || maxDimension <= 0) return 1
        var sample = 1
        while (largest / sample > maxDimension) sample *= 2
        return sample
    }
}
