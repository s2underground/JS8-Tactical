package com.js8call.example.ui.prop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.js8call.example.R
import com.js8call.example.prop.Band
import com.js8call.voacap.Voacap

/**
 * VOACAP's day at a glance: bands down the side (highest at the top), UTC hours across, each
 * cell coloured by reliability like the band chart (green likely, amber possible, red marginal,
 * blank closed). The current hour is outlined; tap a cell to read it.
 */
class VoacapChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var prediction: Voacap.Prediction? = null
        set(value) { field = value; invalidate() }
    /** Bands in the prediction, any order; drawn highest first. */
    var bands: List<Band> = emptyList()
        set(value) { field = value.sortedByDescending { it.mhz }; requestLayout(); invalidate() }
    var nowHourUtc: Int = -1
        set(value) { field = value; invalidate() }
    var onCellTapped: ((Band, Int) -> Unit)? = null

    private var picked: Pair<Band, Int>? = null

    private val d = resources.displayMetrics.density
    private val labelW = 44 * d
    private val rowH = 22 * d
    private val footH = 18 * d
    private val gap = 1 * d

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tac_text); textSize = 12 * d; typeface = Typeface.MONOSPACE
    }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tac_text_quiet); textSize = 10 * d; textAlign = Paint.Align.CENTER
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = context.getColor(R.color.tac_accent)
    }
    private val pick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = context.getColor(R.color.tac_text)
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((labelW + 24 * 14 * d).toInt(), widthMeasureSpec)
        val h = (bands.size * rowH + footH).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    private fun cellW() = (width - labelW) / 24f

    override fun onDraw(canvas: Canvas) {
        val p = prediction
        val cw = cellW()
        bands.forEachIndexed { row, band ->
            val top = row * rowH
            canvas.drawText(band.label, 0f, top + rowH * 0.7f, text)
            for (h in 0 until 24) {
                rect.set(labelW + h * cw + gap, top + gap, labelW + (h + 1) * cw - gap, top + rowH - gap)
                val r = p?.cell(h, band.mhz)?.reliability?.toFloat()
                val c = if (r == null) 0 else PropagationFragment.colorFor(r)
                if (c == 0) canvas.drawRect(rect, empty) else { fill.color = c; canvas.drawRect(rect, fill) }
                if (h == nowHourUtc) canvas.drawRect(rect, ring)
                if (picked?.first == band && picked?.second == h) canvas.drawRect(rect, pick)
            }
        }
        val base = bands.size * rowH + footH * 0.75f
        for (h in 0 until 24 step 3) {
            canvas.drawText(String.format(java.util.Locale.US, "%02d", h), labelW + (h + 0.5f) * cw, base, dim)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        val row = (event.y / rowH).toInt()
        val h = ((event.x - labelW) / cellW()).toInt()
        if (event.x < labelW || row !in bands.indices || h !in 0 until 24) return false
        picked = bands[row] to h
        invalidate()
        performClick()
        onCellTapped?.invoke(bands[row], h)
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
