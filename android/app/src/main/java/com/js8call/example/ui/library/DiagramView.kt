package com.js8call.example.ui.library

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Teaching diagrams for the Library, drawn on a canvas (original drawings,
 * no image files). Some animate slowly; animation stops when the device's
 * "remove animations" setting is on or the view is off screen.
 */
class DiagramView(context: Context, private val diagram: String) : View(context) {

    private val d = resources.displayMetrics.density
    private val ink = 0xFFC9D1DC.toInt()
    private val dim = 0xFF7D8794.toInt()
    private val accent = 0xFFF2A33A.toInt()
    private val cyan = 0xFF4FC3F7.toInt()
    private val green = 0xFF4CC38A.toInt()
    private val red = 0xFFFF7B7B.toInt()
    private val ground = 0xFF5B4A32.toInt()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = ink; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val thin = Paint(line).apply { strokeWidth = 1.2f * d; color = dim }
    private val dash = Paint(thin).apply { pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ink }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 12.5f * d }
    private val small = Paint(text).apply { color = dim; textSize = 11f * d }
    private val path = Path()

    private val animated = diagram in setOf("wave", "dipole-current", "nvis", "skywave")
    private val reduceMotion: Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    init {
        contentDescription = DESCRIPTIONS[diagram] ?: "Diagram"
        setBackgroundColor(0xFF10141A.toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 0.52f).toInt().coerceIn((150 * d).toInt(), (300 * d).toInt())
        setMeasuredDimension(w, h)
    }

    private fun phase(): Float =
        if (!animated || reduceMotion) 0.35f else ((SystemClock.uptimeMillis() % 4000L) / 4000f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        when (diagram) {
            "wave" -> wave(c, w, h)
            "dipole-current" -> dipoleCurrent(c, w, h)
            "nvis" -> nvis(c, w, h)
            "skywave" -> skywave(c, w, h)
            "dipole-pattern" -> pattern(c, w, h)
            "inverted-v" -> invertedV(c, w, h)
            "efhw" -> efhw(c, w, h)
            "vertical" -> vertical(c, w, h)
            "choke" -> choke(c, w, h)
            "throw-line" -> throwLine(c, w, h)
            else -> c.drawText("Diagram \"$diagram\"", 12 * d, 24 * d, small)
        }
        if (animated && !reduceMotion && isShown) postInvalidateDelayed(33)
    }

    // ---------------------------------------------------------------- diagrams

    /** A travelling wave with one wavelength marked. */
    private fun wave(c: Canvas, w: Float, h: Float) {
        val mid = h * 0.5f; val amp = h * 0.25f; val lambda = w * 0.42f
        val shift = phase() * lambda
        path.reset()
        var x = 0f
        while (x <= w) {
            val y = mid - amp * sin(2 * PI * (x - shift) / lambda).toFloat()
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 2 * d
        }
        c.drawLine(0f, mid, w, mid, thin)
        c.drawPath(path, line.apply { color = cyan })
        line.color = ink
        // One wavelength between two crests.
        // Crests are where (x - shift) / λ = ¼ + k; mark from the first crest near the left edge.
        var a = shift + lambda * 0.25f
        while (a > w * 0.08f) a -= lambda
        while (a < w * 0.04f) a += lambda
        val b = a + lambda
        val y = mid - amp - 14 * d
        c.drawLine(a, y, b, y, line.apply { color = accent })
        c.drawLine(a, y - 6 * d, a, y + 6 * d, line); c.drawLine(b, y - 6 * d, b, y + 6 * d, line)
        line.color = ink
        center(c, "one wavelength (λ)", (a + b) / 2, y - 8 * d, text.apply { color = accent })
        text.color = ink
        c.drawText("λ in metres ≈ 300 ÷ frequency in MHz   (40 m band: 300 ÷ 7.078 ≈ 42 m)", 12 * d, h - 12 * d, small)
    }

    /** Half-wave dipole: current largest at the centre, voltage largest at the ends. */
    private fun dipoleCurrent(c: Canvas, w: Float, h: Float) {
        val y = h * 0.55f; val l = w * 0.1f; val r = w * 0.9f; val cx = w / 2
        val swing = cos(2 * PI * phase()).toFloat()
        // Current envelope.
        path.reset()
        var x = l
        while (x <= r) {
            val u = (x - cx) / ((r - l) / 2)
            val cur = cos(u * PI / 2).toFloat() * h * 0.28f * swing
            if (x == l) path.moveTo(x, y - cur) else path.lineTo(x, y - cur)
            x += 2 * d
        }
        c.drawPath(path, line.apply { color = cyan }); line.color = ink
        // Voltage envelope (dashed).
        path.reset(); x = l
        while (x <= r) {
            val u = (x - cx) / ((r - l) / 2)
            val v = sin(u * PI / 2).toFloat() * h * 0.22f * swing
            if (x == l) path.moveTo(x, y + v) else path.lineTo(x, y + v)
            x += 2 * d
        }
        c.drawPath(path, Paint(dash).apply { color = red; strokeWidth = 2f * d })
        // The wire, insulators and feedpoint.
        c.drawLine(l, y, cx - 6 * d, y, line); c.drawLine(cx + 6 * d, y, r, y, line)
        c.drawCircle(l, y, 4 * d, fill); c.drawCircle(r, y, 4 * d, fill)
        c.drawLine(cx - 6 * d, y, cx, y + 26 * d, thin); c.drawLine(cx + 6 * d, y, cx, y + 26 * d, thin)
        c.drawLine(cx, y + 26 * d, cx, h - 8 * d, line.apply { color = dim }); line.color = ink
        center(c, "current (most at the middle)", cx, 18 * d, text.apply { color = cyan }); text.color = ink
        c.drawText("voltage: highest at the ends", l, h - 12 * d, small.apply { color = red }); small.color = dim
        center(c, "feedline", cx + 34 * d, h - 12 * d, small)
        center(c, "½ λ end to end", cx, y - h * 0.32f, small)
    }

    /** NVIS: a low antenna sends energy straight up; it comes back down all around, out to ~400 km. */
    private fun nvis(c: Canvas, w: Float, h: Float) {
        val gy = h * 0.82f; val iy = h * 0.16f
        ionosphere(c, w, iy); groundLine(c, w, gy)
        val ax = w * 0.5f
        antennaMast(c, ax, gy, h * 0.12f)
        val p = phase()
        for (k in -3..3) {
            val land = ax + k * w * 0.09f
            val top = (ax + land) / 2
            ray(c, ax, gy - h * 0.12f, top, iy + 6 * d, land, gy, p, green)
        }
        center(c, "Low antenna (0.1–0.25 λ up): signal goes nearly straight up and comes back all around", w / 2, gy + 16 * d, small)
        center(c, "covers 0 – about 400 km with no gap · 80, 60 and 40 m", w / 2, gy + 30 * d, small)
    }

    /** Sky wave: a higher antenna sends energy at a low angle; it lands far away and skips over the middle. */
    private fun skywave(c: Canvas, w: Float, h: Float) {
        val gy = h * 0.8f; val iy = h * 0.16f
        ionosphere(c, w, iy); groundLine(c, w, gy)
        val ax = w * 0.1f
        antennaMast(c, ax, gy, h * 0.3f)
        val p = phase()
        ray(c, ax, gy - h * 0.3f, w * 0.5f, iy + 6 * d, w * 0.92f, gy, p, cyan)
        ray(c, ax, gy - h * 0.3f, w * 0.42f, iy + 6 * d, w * 0.76f, gy, p, cyan)
        val z0 = w * 0.2f; val z1 = w * 0.7f
        c.drawLine(z0, gy - 8 * d, z1, gy - 8 * d, Paint(dash).apply { color = red })
        center(c, "skip zone: too far for ground wave, too close for the sky wave", (z0 + z1) / 2, gy - 14 * d, small.apply { color = red }); small.color = dim
        center(c, "Higher antenna (½ λ or more): low angle, long distance (hundreds to thousands of km)", w / 2, gy + 18 * d, small)
    }

    /** Top view of a dipole's pattern: strongest broadside, weakest off the ends. */
    private fun pattern(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2; val r = min(w, h) * 0.38f
        path.reset()
        var a = 0.0
        while (a <= 2 * PI + 0.01) {
            val g = abs(sin(a)).toFloat()
            val x = cx + r * g * cos(a).toFloat(); val y = cy - r * g * sin(a).toFloat()
            if (a == 0.0) path.moveTo(x, y) else path.lineTo(x, y)
            a += 0.03
        }
        c.drawPath(path, line.apply { color = green }); line.color = ink
        c.drawLine(cx - r * 0.9f, cy, cx + r * 0.9f, cy, line)
        c.drawCircle(cx - r * 0.9f, cy, 3.5f * d, fill); c.drawCircle(cx + r * 0.9f, cy, 3.5f * d, fill)
        center(c, "strongest", cx, cy - r - 6 * d, text.apply { color = green })
        center(c, "strongest", cx, cy + r + 16 * d, text); text.color = ink
        c.drawText("weak off", cx + r * 0.95f, cy - 6 * d, small); c.drawText("the ends", cx + r * 0.95f, cy + 10 * d, small)
        c.drawText("Seen from above (wire left–right)", 10 * d, 18 * d, small)
        c.drawText("Low antennas (NVIS) lose this shape: nearly round", 10 * d, h - 10 * d, small)
    }

    private fun invertedV(c: Canvas, w: Float, h: Float) {
        val gy = h * 0.88f; val cx = w / 2; val top = h * 0.12f
        groundLine(c, w, gy)
        c.drawLine(cx, gy, cx, top, line.apply { color = dim; strokeWidth = 4 * d }); line.color = ink; line.strokeWidth = 2.5f * d
        val lx = w * 0.12f; val rx = w * 0.88f; val endY = gy - h * 0.22f
        c.drawLine(cx, top, lx, endY, line); c.drawLine(cx, top, rx, endY, line)
        c.drawCircle(lx, endY, 4 * d, fill); c.drawCircle(rx, endY, 4 * d, fill)
        c.drawLine(lx, endY, lx - 14 * d, gy, dash); c.drawLine(rx, endY, rx + 14 * d, gy, dash)
        val arc = RectF(cx - 40 * d, top - 40 * d, cx + 40 * d, top + 40 * d)
        c.drawArc(arc, 90f - 55f, 110f, false, thin.apply { color = accent }); thin.color = dim
        center(c, "apex angle 90–120° (not sharper)", cx, top + 58 * d, small.apply { color = accent }); small.color = dim
        c.drawText("feedpoint and choke at the top", cx + 8 * d, top + 10 * d, small)
        c.drawText("ends at least 8 ft (2.5 m) up:", lx - 6 * d, endY - 26 * d, small)
        c.drawText("high voltage, keep people away", lx - 6 * d, endY - 12 * d, small)
        center(c, "one mast or tree · tie-offs (paracord) to stakes", cx, gy + 14 * d, small)
    }

    private fun efhw(c: Canvas, w: Float, h: Float) {
        val gy = h * 0.88f
        groundLine(c, w, gy)
        // Tree.
        val tx = w * 0.86f
        c.drawLine(tx, gy, tx, h * 0.1f, line.apply { color = ground; strokeWidth = 6 * d }); line.color = ink; line.strokeWidth = 2.5f * d
        val bx = w * 0.18f; val by = gy - h * 0.12f
        c.drawRect(bx - 12 * d, by - 10 * d, bx + 12 * d, by + 10 * d, line)
        center(c, "49:1", bx, by + 4 * d, small)
        c.drawLine(bx + 12 * d, by - 6 * d, tx - 4 * d, h * 0.14f, line.apply { color = cyan }); line.color = ink
        c.drawCircle(tx - 4 * d, h * 0.14f, 4 * d, fill)
        c.drawLine(bx - 12 * d, by + 6 * d, bx - w * 0.1f, gy - 2 * d, Paint(line).apply { color = accent; strokeWidth = 1.8f * d })
        c.drawLine(bx, by + 10 * d, bx, gy, thin)
        c.drawLine(bx, gy, w * 0.05f, gy, thin)
        center(c, "radiator: ½ λ on the lowest band (e.g. 66 ft for 40 m)", w * 0.52f, h * 0.36f, small.apply { color = cyan }); small.color = dim
        c.drawText("short counterpoise", bx - w * 0.12f, gy + 14 * d, small.apply { color = accent }); small.color = dim
        c.drawText("transformer near the ground; coax to radio, choke near the radio", w * 0.05f, h * 0.1f, small)
        center(c, "far end high: high voltage there", tx - 60 * d, h * 0.22f, small)
    }

    private fun vertical(c: Canvas, w: Float, h: Float) {
        val gy = h * 0.8f; val cx = w * 0.5f
        groundLine(c, w, gy)
        c.drawLine(cx, gy - 4 * d, cx, h * 0.1f, line.apply { color = cyan }); line.color = ink
        for (k in -3..3) if (k != 0) c.drawLine(cx, gy - 2 * d, cx + k * w * 0.13f, gy + 8 * d * abs(k) / 3f, Paint(line).apply { color = accent; strokeWidth = 1.8f * d })
        center(c, "¼ λ vertical element (234 ÷ MHz = feet)", cx, h * 0.07f, small.apply { color = cyan }); small.color = dim
        center(c, "radials on or just above the ground: the more the better (4 minimum)", cx, gy + 26 * d, small.apply { color = accent }); small.color = dim
        c.drawText("low-angle signal, all directions:", 10 * d, h * 0.4f, small)
        c.drawText("good for distance, poor nearby", 10 * d, h * 0.4f + 14 * d, small)
    }

    private fun choke(c: Canvas, w: Float, h: Float) {
        val cy = h * 0.5f; val x0 = w * 0.25f; val x1 = w * 0.75f
        c.drawLine(8 * d, cy, x0, cy, line.apply { color = dim; strokeWidth = 5 * d })
        c.drawLine(x1, cy, w - 8 * d, cy, line)
        line.color = ink; line.strokeWidth = 2.5f * d
        val turns = 7
        for (t in 0 until turns) {
            val x = x0 + (x1 - x0) * (t + 0.5f) / turns
            c.drawOval(RectF(x - 10 * d, cy - h * 0.28f, x + 10 * d, cy + h * 0.28f), Paint(line).apply { color = accent; strokeWidth = 4 * d })
        }
        center(c, "coax wound into a coil, or through ferrite cores", w / 2, cy - h * 0.34f, small)
        c.drawText("to antenna", 8 * d, cy - 10 * d, small); c.drawText("to radio", x1 + 4 * d, cy - 10 * d, small)
        center(c, "stops RF coming back down the outside of the coax", w / 2, cy + h * 0.42f, small)
    }

    private fun throwLine(c: Canvas, w: Float, h: Float) {
        val gy = h * 0.88f
        groundLine(c, w, gy)
        val tx = w * 0.6f
        c.drawLine(tx, gy, tx, h * 0.25f, line.apply { color = ground; strokeWidth = 7 * d }); line.color = ink; line.strokeWidth = 2.5f * d
        c.drawLine(tx, h * 0.3f, tx + w * 0.15f, h * 0.18f, line.apply { color = ground; strokeWidth = 4 * d }); line.color = ink; line.strokeWidth = 2.5f * d
        // Arc of the throw.
        path.reset(); path.moveTo(w * 0.15f, gy - 30 * d); path.quadTo(w * 0.45f, h * 0.0f, tx + w * 0.1f, h * 0.22f)
        c.drawPath(path, dash)
        path.reset(); path.moveTo(tx + w * 0.1f, h * 0.22f); path.lineTo(tx + w * 0.14f, gy - 6 * d)
        c.drawPath(path, line.apply { color = accent }); line.color = ink
        c.drawCircle(tx + w * 0.14f, gy - 10 * d, 5 * d, fill.apply { color = accent }); fill.color = ink
        c.drawText("throw weight + light line over a branch", 10 * d, 16 * d, small)
        c.drawText("then pull up paracord, then the antenna", 10 * d, 30 * d, small)
        c.drawText("stand clear; never near power lines", 10 * d, gy - 8 * d, small.apply { color = red }); small.color = dim
    }

    // ---------------------------------------------------------------- helpers

    private fun ionosphere(c: Canvas, w: Float, y: Float) {
        c.drawRect(0f, y - 10 * d, w, y + 4 * d, Paint(fill).apply { color = 0x334FC3F7 })
        c.drawText("ionosphere (F layer, ~250–400 km up)", 8 * d, y - 14 * d, small)
    }

    private fun groundLine(c: Canvas, w: Float, y: Float) {
        c.drawLine(0f, y, w, y, Paint(line).apply { color = ground; strokeWidth = 3 * d })
    }

    private fun antennaMast(c: Canvas, x: Float, gy: Float, height: Float) {
        c.drawLine(x, gy, x, gy - height, thin)
        c.drawLine(x - 18 * d, gy - height, x + 18 * d, gy - height, line)
    }

    /** A ray up to the ionosphere and down, with a moving dot. */
    private fun ray(c: Canvas, x0: Float, y0: Float, xm: Float, ym: Float, x1: Float, y1: Float, p: Float, color: Int) {
        val pen = Paint(line).apply { this.color = color and 0x99FFFFFF.toInt(); strokeWidth = 1.6f * d }
        c.drawLine(x0, y0, xm, ym, pen); c.drawLine(xm, ym, x1, y1, pen)
        val t = p
        val (x, y) = if (t < 0.5f) lerp(x0, y0, xm, ym, t * 2) else lerp(xm, ym, x1, y1, (t - 0.5f) * 2)
        c.drawCircle(x, y, 3.5f * d, Paint(fill).apply { this.color = color })
    }

    private fun lerp(x0: Float, y0: Float, x1: Float, y1: Float, t: Float) = (x0 + (x1 - x0) * t) to (y0 + (y1 - y0) * t)

    private fun center(c: Canvas, s: String, x: Float, y: Float, p: Paint) {
        val tw = p.measureText(s)
        c.drawText(s, max(4 * d, min(x - tw / 2, width - tw - 4 * d)), y, p)
    }

    companion object {
        val DESCRIPTIONS = mapOf(
            "wave" to "A radio wave with one wavelength marked. Wavelength in metres is about 300 divided by the frequency in megahertz.",
            "dipole-current" to "A half-wave dipole. Current is largest at the centre feedpoint; voltage is largest at the two ends.",
            "nvis" to "NVIS: a low antenna sends the signal nearly straight up; it returns all around, out to about 400 km, with no skip zone.",
            "skywave" to "Sky wave: a higher antenna sends the signal at a low angle; it lands far away, skipping over a zone in between.",
            "dipole-pattern" to "Top view of a dipole's pattern: strongest broadside to the wire, weak off the ends.",
            "inverted-v" to "An inverted V: one mast, feedpoint at the top, legs sloping down at 90 to 120 degrees, ends kept high.",
            "efhw" to "An end-fed half-wave: wire from a 49 to 1 transformer near the ground up to a tree, with a short counterpoise.",
            "vertical" to "A quarter-wave vertical with radials on the ground.",
            "choke" to "A choke: coax wound into a coil, or through ferrite, stops RF on the outside of the coax.",
            "throw-line" to "Getting a line into a tree: throw weight and light line over a branch, then pull up paracord, then the antenna.",
        )
    }
}
