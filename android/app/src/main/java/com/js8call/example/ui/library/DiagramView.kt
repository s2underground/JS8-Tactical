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

    private val animated = diagram in setOf("wave", "dipole-current", "nvis", "skywave", "waterfall")
    private val reduceMotion: Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    init {
        contentDescription = DESCRIPTIONS[diagram] ?: "Diagram"
        setBackgroundColor(0xFF10141A.toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val ratio = when (diagram) { "throw-line" -> 0.62f; "waterfall" -> 0.6f; "frames" -> 0.45f; else -> 0.52f }
        val h = (w * ratio).toInt().coerceIn((160 * d).toInt(), (340 * d).toInt())
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
            "passband" -> passband(c, w, h)
            "waterfall" -> waterfall(c, w, h)
            "timeslots" -> timeslots(c, w, h)
            "frames" -> frames(c, w, h)
            "line-of-sight" -> lineOfSight(c, w, h)
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

    /** Three steps: throw a weighted line over a branch, pull paracord over, haul the antenna up. */
    private fun throwLine(c: Canvas, w: Float, h: Float) {
        val pw = w / 3f
        val gy = h * 0.82f
        val titles = listOf("1. Throw the weight", "2. Pull paracord over", "3. Haul up the antenna")
        for (k in 0..2) {
            val px = k * pw
            // Panel frame and title.
            if (k > 0) c.drawLine(px, 6 * d, px, h - 6 * d, Paint(thin).apply { color = 0xFF2A313B.toInt() })
            center(c, titles[k], px + pw / 2, 16 * d, text.apply { color = accent }); text.color = ink
            c.drawLine(px + 4 * d, gy, px + pw - 4 * d, gy, Paint(line).apply { color = ground; strokeWidth = 3 * d })
            // Tree: trunk, a branch reaching left, some foliage.
            val tx = px + pw * 0.78f; val by = h * 0.3f; val bx = px + pw * 0.5f
            c.drawLine(tx, gy, tx, h * 0.14f, Paint(line).apply { color = ground; strokeWidth = 7 * d })
            c.drawLine(tx, by + 14 * d, bx, by, Paint(line).apply { color = ground; strokeWidth = 4 * d })
            c.drawCircle(tx, h * 0.17f, pw * 0.14f, Paint(fill).apply { color = 0x553F7D4F })
            c.drawCircle(bx + 6 * d, by - 8 * d, pw * 0.09f, Paint(fill).apply { color = 0x553F7D4F })
            // Person.
            val fx = px + pw * 0.14f
            person(c, fx, gy, k == 0)
            val handX = fx + 9 * d; val handY = gy - 30 * d
            val over = bx + 10 * d // where lines cross the branch
            when (k) {
                0 -> {
                    // Thrown arc to just past the branch; weight falling on the far side.
                    path.reset(); path.moveTo(handX, handY); path.quadTo((handX + over) / 2, by - h * 0.32f, over, by - 2 * d)
                    c.drawPath(path, dash)
                    c.drawLine(over, by - 2 * d, over + 4 * d, by + h * 0.22f, Paint(dash).apply { color = accent })
                    c.drawCircle(over + 4 * d, by + h * 0.22f, 5 * d, Paint(fill).apply { color = accent })
                    c.drawText("throw bag", over - 6 * d - small.measureText("throw bag"), by + h * 0.22f + 4 * d, small)
                    c.drawText("light line", (handX + over) / 2 - 46 * d, by - h * 0.06f, small)
                }
                1 -> {
                    // Light line over the branch; paracord (orange) tied on and being pulled up.
                    val thin2 = Paint(line).apply { color = ink; strokeWidth = 1.4f * d }
                    c.drawLine(handX, handY, over, by - 2 * d, thin2)
                    c.drawLine(over, by - 2 * d, over + 2 * d, by + h * 0.3f, thin2)
                    val cord = Paint(line).apply { color = 0xFFE07A2E.toInt(); strokeWidth = 3 * d }
                    c.drawLine(over + 2 * d, by + h * 0.3f, over + 6 * d, gy - 2 * d, cord)
                    c.drawCircle(over + 2 * d, by + h * 0.3f, 3 * d, Paint(fill).apply { color = 0xFFE07A2E.toInt() })
                    c.drawText("knot", over + 8 * d, by + h * 0.3f + 4 * d, small)
                    c.drawText("paracord", handX + 6 * d, gy - 14 * d, small.apply { color = 0xFFE07A2E.toInt() }); small.color = dim
                }
                else -> {
                    // Paracord over the branch to the end insulator; antenna wire running away to the left.
                    val cord = Paint(line).apply { color = 0xFFE07A2E.toInt(); strokeWidth = 3 * d }
                    val insX = over + 2 * d; val insY = by + 16 * d
                    c.drawLine(insX, by - 2 * d, insX, insY - 4 * d, cord)
                    c.drawLine(insX, by - 2 * d, tx - 4 * d, gy - 30 * d, cord)
                    c.drawRect(insX - 4 * d, insY - 5 * d, insX + 4 * d, insY + 5 * d, Paint(fill).apply { color = 0xFFDDDDDD.toInt() })
                    c.drawLine(insX, insY + 5 * d, px + 6 * d, gy - h * 0.24f, Paint(line).apply { color = cyan })
                    c.drawText("insulator", insX - 8 * d - small.measureText("insulator"), insY - 10 * d, small)
                    c.drawText("antenna wire", px + 8 * d, gy - h * 0.24f - 10 * d, small.apply { color = cyan }); small.color = dim
                    c.drawText("tie off", tx - 8 * d - small.measureText("tie off"), gy - 6 * d, small)
                }
            }
        }
        center(c, "Look up first: never throw anywhere near power lines.", w / 2, h - 18 * d, small.apply { color = red })
        center(c, "Stand clear when throwing: the weight comes back down.", w / 2, h - 4 * d, small)
        small.color = dim
    }

    /** A stick figure standing on the ground at x, optionally with an arm raised to throw. */
    private fun person(c: Canvas, x: Float, gy: Float, throwing: Boolean) {
        val p = Paint(line).apply { strokeWidth = 2 * d; color = ink }
        val hip = gy - 18 * d; val neck = gy - 38 * d
        c.drawLine(x, gy, x + 4 * d, hip, p); c.drawLine(x + 8 * d, gy, x + 4 * d, hip, p)
        c.drawLine(x + 4 * d, hip, x + 4 * d, neck, p)
        c.drawCircle(x + 4 * d, neck - 6 * d, 5 * d, p)
        if (throwing) c.drawLine(x + 4 * d, neck + 4 * d, x + 9 * d, neck - 10 * d, p) else c.drawLine(x + 4 * d, neck + 4 * d, x + 13 * d, neck + 8 * d, p)
        c.drawLine(x + 4 * d, neck + 4 * d, x - 4 * d, neck + 14 * d, p)
    }

    /** USB: the radio's dial frequency plus audio tones; signal = dial + offset. */
    private fun passband(c: Canvas, w: Float, h: Float) {
        val l = w * 0.08f; val r = w * 0.95f; val y = h * 0.62f
        fun xAt(hz: Float) = l + (r - l) * hz / 3000f
        c.drawRect(xAt(300f), y - h * 0.38f, xAt(2800f), y, Paint(fill).apply { color = 0x1F4FC3F7 })
        c.drawRect(xAt(500f), y - h * 0.38f, xAt(1000f), y, Paint(fill).apply { color = 0x26F2A33A })
        c.drawLine(l, y, r, y, line)
        c.drawLine(l, y - h * 0.45f, l, y + 6 * d, line.apply { color = accent }); line.color = ink
        c.drawText("dial 7.078 MHz", l + 4 * d, y - h * 0.4f, text.apply { color = accent }); text.color = ink
        // A few signals: heartbeats low, traffic higher.
        listOf(620f to 0.5f, 840f to 0.35f, 1210f to 0.8f, 1500f to 1f, 1890f to 0.6f, 2240f to 0.7f).forEach { (hz, a) ->
            c.drawRect(xAt(hz) - 3 * d, y - h * 0.3f * a, xAt(hz) + 3 * d, y, Paint(fill).apply { color = if (hz < 1000) accent else green })
        }
        for (hz in listOf(0, 500, 1000, 1500, 2000, 2500, 3000)) {
            c.drawLine(xAt(hz.toFloat()), y, xAt(hz.toFloat()), y + 5 * d, thin)
            center(c, "$hz", xAt(hz.toFloat()), y + 17 * d, small)
        }
        center(c, "heartbeats", xAt(750f), y - h * 0.4f + 26 * d, small.apply { color = accent })
        center(c, "traffic 1,000–2,500 Hz", xAt(1750f), y - h * 0.4f + 26 * d, small.apply { color = green }); small.color = dim
        center(c, "audio offset (Hz) above the dial", (l + r) / 2, y + 32 * d, small)
        center(c, "offset 1,500 Hz: 7.078 MHz + 1.5 kHz = 7.0795 MHz", (l + r) / 2, h - 8 * d, small)
    }

    /** A scrolling waterfall: JS8 signals as short stripes in time slots, noise speckle behind. */
    private fun waterfall(c: Canvas, w: Float, h: Float) {
        val top = 18 * d; val bottom = h - 22 * d; val l = 30 * d; val r = w - 8 * d
        val span = bottom - top
        val slotPx = span / 3.2f
        val scroll = phase() * slotPx
        c.drawRect(l, top, r, bottom, Paint(fill).apply { color = 0xFF07101F.toInt() })
        // Noise: a fixed pseudo-random speckle that scrolls.
        val speck = Paint(fill).apply { color = 0x223D6FA8 }
        var seed = 12345
        repeat(260) {
            seed = seed * 1103515245 + 12345
            val fx = ((seed ushr 8) and 0xFFFF) / 65535f
            seed = seed * 1103515245 + 12345
            val fy = ((seed ushr 8) and 0xFFFF) / 65535f
            val yy = top + ((fy * span + scroll) % span)
            c.drawRect(l + fx * (r - l), yy, l + fx * (r - l) + 2 * d, yy + 2 * d, speck)
        }
        // Slot boundaries and signals (newest at the top, scrolling down).
        val sig = listOf(Triple(0.18f, 0, 0xFFF2A33A.toInt()), Triple(0.45f, 1, 0xFF4CC38A.toInt()), Triple(0.62f, 0, 0xFF4FC3F7.toInt()), Triple(0.80f, 2, 0xFF4CC38A.toInt()))
        for (slot in -1..4) {
            val y0 = top + slot * slotPx + scroll
            if (y0 > top && y0 < bottom) c.drawLine(l, y0, r, y0, Paint(thin).apply { color = 0x55FFFFFF })
            sig.forEach { (fx, every, color) ->
                if ((slot + 10) % 3 == every || (every == 0 && slot % 2 == 0)) {
                    val ys = (y0 + slotPx * 0.08f).coerceIn(top, bottom); val ye = (y0 + slotPx * 0.9f).coerceIn(top, bottom)
                    if (ye > ys) c.drawRect(l + fx * (r - l) - 3 * d, ys, l + fx * (r - l) + 3 * d, ye, Paint(fill).apply { this.color = color })
                }
            }
        }
        c.drawText("time ↓", 2 * d, top + 12 * d, small)
        center(c, "offset →  (low tones left, high right)", (l + r) / 2, h - 6 * d, small)
        center(c, "each stripe = one frame (most of one time slot)", (l + r) / 2, top - 4 * d, small)
    }

    /** Time slots: everyone starts sending at the same instants; a late clock misses the start. */
    private fun timeslots(c: Canvas, w: Float, h: Float) {
        val l = w * 0.06f; val r = w * 0.97f
        val slots = 4
        val sw = (r - l) / slots
        val rows = listOf("Station A" to listOf(0, 2), "Station B" to listOf(1, 3), "Late clock (3 s)" to listOf(1))
        rows.forEachIndexed { i, (label, on) ->
            val y = h * (0.26f + i * 0.2f)
            c.drawText(label, l, y - 8 * d, small.apply { color = if (i == 2) red else dim }); small.color = dim
            on.forEach { s0 ->
                val shift = if (i == 2) sw * 0.2f else 0f
                val x0 = l + s0 * sw + sw * 0.03f + shift; val x1 = x0 + sw * 0.84f
                c.drawRect(x0, y, x1, y + 14 * d, Paint(fill).apply { color = if (i == 2) red else if (i == 0) cyan else green })
            }
        }
        for (k in 0..slots) {
            val x = l + k * sw
            c.drawLine(x, h * 0.16f, x, h * 0.84f, dash)
            center(c, "${k * 15} s", x, h * 0.92f, small)
        }
        center(c, "Normal: a slot every 15 s; each send lasts ~12.6 s", (l + r) / 2, h * 0.1f, small)
        center(c, "clock >2 s off: starts late, not decoded", (l + r) / 2, h - 2 * d, small.apply { color = red }); small.color = dim
    }

    /** A message split into frames, one per slot; a lost frame leaves a gap. */
    private fun frames(c: Canvas, w: Float, h: Float) {
        val parts = listOf("KX4ABC: W1AW", "MEET AT RIVE", "R BRIDGE 18", "00 ♢")
        val l = w * 0.04f; val r = w * 0.96f
        val bw = (r - l) / parts.size
        val y = h * 0.34f
        parts.forEachIndexed { i, p ->
            val x0 = l + i * bw + 3 * d; val x1 = l + (i + 1) * bw - 3 * d
            val lost = i == 2
            c.drawRect(x0, y, x1, y + 36 * d, Paint(fill).apply { color = if (lost) 0x33FF7B7B else 0x334FC3F7 })
            c.drawRect(x0, y, x1, y + 36 * d, Paint(thin).apply { color = if (lost) red else cyan })
            center(c, p, (x0 + x1) / 2, y + 23 * d, small.apply { color = if (lost) red else ink })
            center(c, "slot ${i + 1}", (x0 + x1) / 2, y - 6 * d, small)
        }
        small.color = dim
        center(c, "missed (fading or interference)", l + 2.5f * bw, y + 52 * d, small.apply { color = red }); small.color = dim
        center(c, "app shows: W1AW MEET AT RIVE [missing frame] 00", (l + r) / 2, h - 28 * d, small.apply { color = ink }); small.color = dim
        center(c, "♢ marks the last frame: the message is complete", (l + r) / 2, h - 10 * d, small)
    }

    /** VHF/UHF: line of sight, blocked by the earth's curve and hills; HF bends back from the sky. */
    private fun lineOfSight(c: Canvas, w: Float, h: Float) {
        val gy = h * 0.85f
        path.reset(); path.moveTo(0f, gy); path.quadTo(w / 2, gy - h * 0.18f, w, gy)
        c.drawPath(path, Paint(line).apply { color = ground; strokeWidth = 3 * d })
        val ax = w * 0.08f; val bx = w * 0.92f
        c.drawLine(ax, gy - 2 * d, ax, gy - 34 * d, line); c.drawLine(bx, gy - 2 * d, bx, gy - 34 * d, line)
        c.drawLine(ax, gy - 34 * d, w * 0.47f, gy - h * 0.16f, Paint(line).apply { color = green; strokeWidth = 2 * d })
        c.drawLine(w * 0.47f, gy - h * 0.16f, w * 0.6f, gy - h * 0.13f, Paint(dash).apply { color = red })
        center(c, "VHF/UHF: straight lines, blocked by hills and the horizon", w / 2, gy - h * 0.25f, small.apply { color = green })
        path.reset(); path.moveTo(ax, gy - 34 * d); path.quadTo(w / 2, -h * 0.25f, bx, gy - 34 * d)
        c.drawPath(path, Paint(line).apply { color = cyan; strokeWidth = 2 * d })
        center(c, "HF (JS8): bent back down by the ionosphere", w / 2, 16 * d, small.apply { color = cyan }); small.color = dim
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
            "throw-line" to "Three steps: throw a weighted light line over a branch, use it to pull paracord over, then use the paracord to haul up the antenna's end insulator. Never near power lines.",
            "passband" to "The radio's dial frequency plus audio tones. Heartbeats use 500 to 1000 hertz, traffic 1000 to 2500. A signal's real frequency is the dial plus its offset.",
            "waterfall" to "A waterfall display: time runs downward, audio offset runs left to right. Each JS8 frame shows as a short stripe filling most of a time slot.",
            "timeslots" to "Time slots: stations start sending at the same instants every 15 seconds. A clock more than about two seconds off starts in the wrong place and is not decoded.",
            "frames" to "A message split into frames, one per time slot. A missed frame leaves a gap in the message; the diamond marks the last frame.",
            "line-of-sight" to "VHF and UHF travel in straight lines and are blocked by the curve of the earth; HF is bent back down by the ionosphere.",
        )
    }
}
