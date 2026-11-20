package com.js8call.example.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.js8call.example.service.JS8EngineService
import com.js8call.example.service.TxOffsets

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
    private val hbFill = Paint().apply { color = Color.argb(45, 255, 122, 162) }
    private val hbEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 122, 162)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f)
    }
    private val hbLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 122, 162) }
    private val zonePaint = Paint().apply { color = Color.argb(150, 255, 122, 162) }
    private val onAirFill = Paint().apply { color = Color.argb(110, 255, 40, 40) }
    private val onAirEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 40, 40)
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val onAirLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 30, 30) }
    private val onAirText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
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
    var txOffsetHz: Float = TxOffsets.DEFAULT_TX_HZ
        set(value) {
            field = value
            postInvalidate()
        }
    /** Set while a transmission is on the air: the offset and width the engine is sending at. */
    var onAirHz: Float? = null
        set(value) { field = value; postInvalidate() }
    var onAirBandwidthHz: Float? = null
        set(value) { field = value; postInvalidate() }
    /** Heartbeat setting or send speed changed: redraw the markers. */
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == TxOffsets.KEY_HB_MODE || key == TxOffsets.KEY_HB_FIXED || key == TxOffsets.KEY_HB_NEXT ||
            key == TxOffsets.KEY_TX || key == JS8EngineService.PREF_TX_SUBMODE) postInvalidate()
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

        val currentBinHz = renderer?.getCurrentBinHz() ?: 0f
        val displayStartBin = renderer?.getDisplayStartBin() ?: 0
        val displayEndBin = renderer?.getDisplayEndBin() ?: -1
        if (currentBinHz > 0 && displayEndBin > displayStartBin) {
            drawScale(canvas, currentBinHz, displayStartBin, displayEndBin)
        }
        if (currentBinHz > 0 && displayEndBin > displayStartBin) {
            val hzToPx = { hz: Float -> hzToX(hz, currentBinHz, displayStartBin, displayEndBin) }
            val bw = TxOffsets.bandwidthHz(TxOffsets.submode(context))
            val txHz = dragHz ?: txOffsetHz
            val hbMode = TxOffsets.hbMode(context)

            // Heartbeat zone (500–1,000 Hz) as a thin strip along the bottom, when heartbeats use it.
            if (hbMode == TxOffsets.HB_ZONE) {
                val strip = 4f * density
                canvas.drawRect(hzToPx(TxOffsets.ZONE_LOW_HZ), height - strip, hzToPx(TxOffsets.ZONE_HIGH_HZ), height.toFloat(), zonePaint)
            }
            // Where the next heartbeat goes, unless that is the TX offset.
            if (hbMode != TxOffsets.HB_TX) {
                val hb = TxOffsets.nextHeartbeat(context)
                // Same row as the TX label (clear of station tags) unless the two would overlap.
                val labelTop = if (kotlin.math.abs(hb - txHz) < 260f) 0.5f else 0.25f
                drawMarker(canvas, hzToPx(hb), hzToPx(hb + bw), hbFill, hbEdge, "HB ${hb.toInt()} Hz", labelTop, hbLabelBg)
            }
            val txLabel = if (hbMode == TxOffsets.HB_TX) "TX + HB ${txHz.toInt()} Hz" else "TX ${txHz.toInt()} Hz"
            drawMarker(canvas, hzToPx(txHz), hzToPx(txHz + bw), markerPaint, markerEdgePaint, txLabel, 0.25f, labelBg)
            // What is going out right now, as the engine was told: the final word on where you are sending.
            onAirHz?.let { hz ->
                val w = onAirBandwidthHz ?: bw
                drawMarker(canvas, hzToPx(hz), hzToPx(hz + w), onAirFill, onAirEdge, "SENDING ${hz.toInt()} Hz", 0.04f, onAirLabelBg, onAirText)
            }
        }
    }

    private fun drawMarker(
        canvas: Canvas, left: Float, right: Float, fill: Paint, edge: Paint,
        label: String, labelTop: Float, labelBackground: Paint, text: Paint = labelText,
    ) {
        if (right < 0f || left > width) return
        canvas.drawRect(left, 0f, right, height.toFloat(), fill)
        canvas.drawLine(left, 0f, left, height.toFloat(), edge)
        canvas.drawLine(right, 0f, right, height.toFloat(), edge)
        val pad = 4f * density
        val w = text.measureText(label) + pad * 2
        val h = text.textSize + pad * 1.5f
        val x = (left + right) / 2f
        val l = (x - w / 2f).coerceIn(0f, (width - w).coerceAtLeast(0f))
        val top = height * labelTop
        canvas.drawRoundRect(l, top, l + w, top + h, pad, pad, labelBackground)
        canvas.drawText(label, l + pad, top + h - pad, text)
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
                    warnIfOutsidePassband(txOffsetHz)
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

    private var passbandToast: android.widget.Toast? = null

    /** A prompt, not a limit: most radios' SSB filters pass about 300–2,700 Hz. One toast at a time. */
    private fun warnIfOutsidePassband(hz: Float) {
        val text = when {
            hz < PASSBAND_BOTTOM_HZ -> "TX ${hz.toInt()} Hz is below what most radios pass (about 300 Hz). Your signal may not go out."
            hz > PASSBAND_TOP_HZ -> "TX ${hz.toInt()} Hz is above what most radios pass (about 2,700 Hz). Your signal may not go out."
            else -> { passbandToast?.cancel(); passbandToast = null; return }
        }
        passbandToast?.cancel()
        passbandToast = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).also { it.show() }
    }

    companion object {
        private const val PASSBAND_TOP_HZ = 2700f
        private const val PASSBAND_BOTTOM_HZ = 300f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(prefListener)
        renderer?.setInvalidateCallback { postInvalidateOnAnimation() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).unregisterOnSharedPreferenceChangeListener(prefListener)
        renderer?.setInvalidateCallback(null)
        renderer?.stopDrawing()
    }
}
