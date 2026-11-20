package com.js8call.example.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * Custom view that displays a scrolling waterfall visualization of spectrum data.
 * Emulates the JS8Call waterfall display algorithm.
 */
class WaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Light tint plus solid edges, so your own signal stays visible inside the marker.
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 255, 60, 60)
        style = Paint.Style.FILL
    }
    private val markerEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 60, 60)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val density = resources.displayMetrics.density
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 230, 234, 240)
        textSize = 11f * resources.displayMetrics.scaledDensity
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val tickPaint = Paint().apply { color = Color.argb(160, 230, 234, 240); strokeWidth = 1f * resources.displayMetrics.density }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 242, 163, 58) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(11, 14, 18)
        textSize = 12f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
    /** Finger position while dragging, so the marker and its label follow it before it is set. */
    private var dragHz: Float? = null
    private val srcRect = Rect()
    private val dstRect = Rect()

    private var renderer: WaterfallRenderer? = null

    // TX offset tracking
    var txOffsetHz: Float = 1500f
        set(value) {
            field = value.coerceAtLeast(500f)
            postInvalidate()
        }
    // Callback for offset changes
    var onOffsetChanged: ((Float) -> Unit)? = null

    init {
        isClickable = true
    }

    fun bindRenderer(renderer: WaterfallRenderer?) {
        this.renderer?.setInvalidateCallback(null)
        this.renderer = renderer
        this.renderer?.setInvalidateCallback { postInvalidateOnAnimation() }
        if (renderer != null && width > 0 && height > 0) {
            renderer.setSize(width, height)
        }
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderer?.setSize(w, h)
    }

    fun clear() {
        renderer?.clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bitmap = renderer?.getBitmap()
        if (bitmap == null) {
            canvas.drawColor(Color.BLACK)
            return
        }

        // Draw the waterfall bitmap scaled to fill the view
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)

        // Draw TX offset marker bar (50Hz bandwidth for normal mode)
        val currentBinHz = renderer?.getCurrentBinHz() ?: 0f
        val displayStartBin = renderer?.getDisplayStartBin() ?: 0
        val displayEndBin = renderer?.getDisplayEndBin() ?: -1
        if (currentBinHz > 0 && displayEndBin > displayStartBin) {
            drawScale(canvas, currentBinHz, displayStartBin, displayEndBin)
        }
        if (currentBinHz > 0 && displayEndBin > displayStartBin) {
            val usableBins = displayEndBin - displayStartBin
            val shownHz = dragHz ?: txOffsetHz
            val binIndex = (shownHz / currentBinHz).toInt()
            val displayBinIndex = binIndex - displayStartBin

            if (displayBinIndex in 0 until usableBins) {
                val pixelsPerBin = width.toFloat() / usableBins
                val xPosition = displayBinIndex * pixelsPerBin

                // A JS8 signal starts at the offset and goes up from there (the lowest tone is
                // the offset), as wide as the speed in use: mark exactly the space it fills.
                val barWidth = txBandwidthHz() / currentBinHz * pixelsPerBin
                canvas.drawRect(xPosition, 0f, xPosition + barWidth, height.toFloat(), markerPaint)
                canvas.drawLine(xPosition, 0f, xPosition, height.toFloat(), markerEdgePaint)
                canvas.drawLine(xPosition + barWidth, 0f, xPosition + barWidth, height.toFloat(), markerEdgePaint)
                drawTxLabel(canvas, xPosition + barWidth / 2f, shownHz)
            }
        }
    }

    /** Bandwidth of the speed set for sending (Normal 50 Hz, Fast 80, Turbo 160, Slow 25). */
    private fun txBandwidthHz(): Float =
        when (androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(com.js8call.example.service.JS8EngineService.PREF_TX_SUBMODE, com.js8call.example.service.JS8EngineService.SUBMODE_NORMAL)) {
            com.js8call.example.service.JS8EngineService.SUBMODE_FAST -> 80f
            com.js8call.example.service.JS8EngineService.SUBMODE_TURBO -> 160f
            com.js8call.example.service.JS8EngineService.SUBMODE_SLOW -> 25f
            else -> 50f
        }

    private fun hzToX(hz: Float, binHz: Float, start: Int, end: Int): Float =
        (hz / binHz - start) * width.toFloat() / (end - start)

    /** Ticks every 500 Hz along the bottom, labelled every 1 kHz. */
    private fun drawScale(canvas: Canvas, binHz: Float, start: Int, end: Int) {
        val lowHz = start * binHz
        val highHz = end * binHz
        val tick = 6f * density
        var hz = (kotlin.math.ceil(lowHz / 500f) * 500f)
        while (hz <= highHz) {
            val x = hzToX(hz, binHz, start, end)
            val major = hz.toInt() % 1000 == 0
            canvas.drawLine(x, height - (if (major) tick * 1.6f else tick), x, height.toFloat(), tickPaint)
            if (major && hz > 0f) {
                val label = "${(hz / 1000).toInt()}k"
                val w = scalePaint.measureText(label)
                canvas.drawText(label, (x - w / 2f).coerceIn(2f, width - w - 2f), height - tick * 2f, scalePaint)
            }
            hz += 500f
        }
    }

    /** "TX 1500 Hz" tag at the top of the marker. */
    private fun drawTxLabel(canvas: Canvas, x: Float, hz: Float) {
        val text = "TX ${hz.toInt()} Hz"
        val pad = 4f * density
        val w = labelText.measureText(text) + pad * 2
        val h = labelText.textSize + pad * 1.5f
        val left = (x - w / 2f).coerceIn(0f, (width - w).coerceAtLeast(0f))
        // A quarter of the way down: clear of the Traffic strip's station tags (top row and middle row).
        val top = height * 0.25f
        canvas.drawRoundRect(left, top, left + w, top + h, pad, pad, labelBg)
        canvas.drawText(text, left + pad, top + h - pad, labelText)
    }

    private fun hzAt(x: Float): Float? {
        val binHz = renderer?.getCurrentBinHz() ?: 0f
        val start = renderer?.getDisplayStartBin() ?: 0
        val end = renderer?.getDisplayEndBin() ?: -1
        if (binHz <= 0f || end <= start) return null
        val pixelsPerBin = width.toFloat() / (end - start)
        val bin = (x.coerceIn(0f, width.toFloat()) / pixelsPerBin).toInt() + start
        return bin * binHz
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragHz = hzAt(event.x)
                postInvalidate()
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                dragHz = null
                val newOffsetHz = hzAt(event.x)
                if (newOffsetHz != null) {
                    txOffsetHz = newOffsetHz
                    onOffsetChanged?.invoke(txOffsetHz)
                    // A prompt, not a limit: most radios' SSB filters stop around 2,700 Hz.
                    if (txOffsetHz > PASSBAND_TOP_HZ) {
                        android.widget.Toast.makeText(
                            context,
                            "TX ${txOffsetHz.toInt()} Hz is above what most radios pass (about 2,700 Hz). Your signal may not go out.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                postInvalidate()
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                dragHz = null
                postInvalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val PASSBAND_TOP_HZ = 2700f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer?.setInvalidateCallback { postInvalidateOnAnimation() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderer?.setInvalidateCallback(null)
        renderer?.stopDrawing()
    }
}
