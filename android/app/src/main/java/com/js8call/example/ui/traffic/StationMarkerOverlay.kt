package com.js8call.example.ui.traffic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.js8call.example.ui.WaterfallRenderer

/**
 * Draws a coloured marker and callsign tag over the waterfall at each active
 * station's latest offset, using the same bin mapping as [WaterfallView].
 */
class StationMarkerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var renderer: WaterfallRenderer? = null
        set(value) { field = value; invalidate() }

    var markers: List<TrafficViewModel.Marker> = emptyList()
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f * density }
    private val tagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE60B0E12.toInt() }
    private val tagStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 11f * density
    }
    private val rect = RectF()

    init {
        // Markers are informational; touches go to the waterfall underneath.
        isClickable = false
        isFocusable = false
    }

    /** X position for an audio offset, or null when it is off screen. */
    private fun xFor(hz: Float): Float? {
        val r = renderer ?: return null
        val binHz = r.getCurrentBinHz()
        val start = r.getDisplayStartBin()
        val end = r.getDisplayEndBin()
        if (binHz <= 0f || end <= start) return null
        val x = ((hz / binHz) - start) / (end - start) * width
        return x.takeIf { it in 0f..width.toFloat() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padX = 5f * density
        val tagH = textPaint.textSize + 6f * density
        markers.sortedBy { it.hz }.forEachIndexed { i, m ->
            val x = xFor(m.hz) ?: return@forEachIndexed
            linePaint.color = (m.color and 0x00FFFFFF) or 0xD9000000.toInt()
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
            // Alternate tag rows so neighbouring stations don't overlap.
            val top = if (i % 2 == 0) 3f * density else height / 2f
            val w = textPaint.measureText(m.label) + padX * 2
            val left = (x + 4f * density).coerceAtMost(width - w - 2f * density)
            rect.set(left, top, left + w, top + tagH)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, tagFill)
            tagStroke.color = m.color
            canvas.drawRoundRect(rect, 4f * density, 4f * density, tagStroke)
            textPaint.color = m.color
            canvas.drawText(m.label, left + padX, top + tagH - 4.5f * density, textPaint)
        }
    }
}
