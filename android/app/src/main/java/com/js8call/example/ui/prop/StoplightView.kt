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

/**
 * Band × distance chart. Each cell is the predicted chance a band works at
 * that range (green/amber/red/closed); a number in the cell counts stations
 * actually heard there near the selected time. Tap a row to pick that band.
 */
class StoplightView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Row(val band: Band, val predicted: List<Double>?, val heard: List<Int>)

    var rows: List<Row> = emptyList()
        set(value) { field = value; requestLayout(); invalidate() }
    var selected: Band? = null
        set(value) { field = value; invalidate() }
    var onBandTapped: ((Band) -> Unit)? = null

    private val headers = listOf(
        R.string.prop_bucket_nvis, R.string.prop_bucket_regional, R.string.prop_bucket_mid,
        R.string.prop_bucket_long, R.string.prop_bucket_dx,
    ).map { context.getString(it) }

    private val d = resources.displayMetrics.density
    private val labelW = 52 * d
    private val cellW = 58 * d
    private val rowH = 26 * d
    private val headH = 20 * d
    private val gap = 2 * d

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tac_text); textSize = 13 * d; typeface = Typeface.MONOSPACE
    }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tac_text_quiet); textSize = 10.5f * d
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = context.getColor(R.color.tac_accent)
    }
    private val count = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B0E12.toInt(); textSize = 12 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = labelW + headers.size * cellW
        val h = headH + rows.size * rowH
        setMeasuredDimension(w.toInt() + 1, h.toInt() + 1)
    }

    override fun onDraw(canvas: Canvas) {
        headers.forEachIndexed { i, h ->
            canvas.drawText(h, labelW + i * cellW + 4 * d, headH - 6 * d, dim)
        }
        rows.forEachIndexed { r, row ->
            val top = headH + r * rowH
            canvas.drawText(row.band.label, 4 * d, top + rowH / 2 + 5 * d, text)
            for (c in headers.indices) {
                rect.set(labelW + c * cellW + gap, top + gap, labelW + (c + 1) * cellW - gap, top + rowH - gap)
                fill.color = colorFor(row.predicted?.getOrNull(c))
                canvas.drawRoundRect(rect, 4 * d, 4 * d, fill)
                val n = row.heard.getOrNull(c) ?: 0
                if (n > 0) {
                    fill.color = 0xFF4FC3F7.toInt()
                    val cx = rect.right - 10 * d
                    val cy = rect.centerY()
                    canvas.drawCircle(cx, cy, 8 * d, fill)
                    canvas.drawText(if (n > 9) "9+" else n.toString(), cx, cy + 4 * d, count)
                }
            }
            if (row.band == selected) {
                rect.set(1 * d, top + 1 * d, labelW + headers.size * cellW - 1 * d, top + rowH - 1 * d)
                canvas.drawRoundRect(rect, 5 * d, 5 * d, ring)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val r = ((event.y - headH) / rowH).toInt()
            rows.getOrNull(r)?.let { onBandTapped?.invoke(it.band); performClick() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun colorFor(p: Double?): Int = when {
        p == null -> 0xFF1A2029.toInt()
        p >= 0.66 -> 0xFF2E8B5E.toInt()
        p >= 0.33 -> 0xFFB7791F.toInt()
        p >= 0.10 -> 0xFF8E3B3B.toInt()
        else -> 0xFF1E242D.toInt()
    }
}
