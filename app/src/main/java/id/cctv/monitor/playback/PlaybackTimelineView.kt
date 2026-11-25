package id.cctv.monitor.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import id.cctv.monitor.R

/** Bilah waktu 24 jam. Tap atau drag; playback baru dipicu saat jari dilepas. */
class PlaybackTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var dayStart: Long = 0L
    var dayEnd: Long = dayStart
    var clips: List<RecordingClip> = emptyList()
    var selectedIndex: Int = -1
    var onScrub: ((Long) -> Unit)? = null
    var onPick: ((RecordingClip, Long) -> Unit)? = null

    private var dragging = false
    private var currentRatio = 0f
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }

    fun render() = invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        if (w <= 0f) return
        val top = height * 0.35f
        val bottom = height * 0.65f

        barPaint.color = ContextCompat.getColor(context, R.color.stroke)
        canvas.drawRect(0f, top, w, bottom, barPaint)

        // Continuous dulu, event di atasnya supaya warna event tidak ketutup.
        clips.sortedBy { it.eventKind != PlaybackEventKind.CONTINUOUS }.forEach { clip ->
            val left = (PlaybackTimeline.ratio(clip.startMillis, dayStart, dayEnd) * w)
                .coerceIn(0f, w)
            val right = (PlaybackTimeline.ratio(clip.endMillis, dayStart, dayEnd) * w)
                .coerceIn(0f, w)
            barPaint.color = ContextCompat.getColor(
                context,
                if (clip.eventKind == PlaybackEventKind.CONTINUOUS) R.color.accent else R.color.warning
            )
            canvas.drawRect(RectF(left, top, maxOf(left, right), bottom), barPaint)
        }

        if (selectedIndex in clips.indices) {
            val clip = clips[selectedIndex]
            val ratio = if (dragging) {
                currentRatio
            } else {
                PlaybackTimeline.ratio(clip.startMillis, dayStart, dayEnd)
            }
            val x = (ratio * w).coerceIn(0f, w)
            markerPaint.color = ContextCompat.getColor(context, R.color.text)
            canvas.drawLine(x, 0f, x, height.toFloat(), markerPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        if (w <= 0f) return false
        val ratio = (event.x / w).coerceIn(0f, 1f)

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                currentRatio = ratio
                onScrub?.invoke(millisAt(ratio))
                render()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                currentRatio = ratio
                onScrub?.invoke(millisAt(ratio))
                render()
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                dragging = false
                val millis = millisAt(ratio)
                PlaybackTimeline.clipAt(clips, ratio, dayStart, dayEnd)?.let { clip ->
                    onPick?.invoke(clip, millis)
                }
                render()
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                render()
                true
            }

            else -> super.onTouchEvent(event)
        }
    }

    private fun millisAt(ratio: Float): Long =
        dayStart + (ratio.coerceIn(0f, 1f) * (dayEnd - dayStart).coerceAtLeast(0L).toFloat()).toLong()
}
