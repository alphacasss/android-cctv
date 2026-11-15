package id.cctv.monitor.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** FrameLayout dengan rasio 16:9, dipakai untuk tile kamera di grid 3x3. */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (width > 0) {
            (width * 9f / 16f).toInt()
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
