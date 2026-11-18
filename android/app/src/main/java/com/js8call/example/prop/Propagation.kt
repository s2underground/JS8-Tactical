package com.js8call.example.prop

import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** JS8 dial frequencies used for predictions. */
enum class Band(val label: String, val mhz: Double) {
    B160("160m", 1.842), B80("80m", 3.578), B60("60m", 5.357), B40("40m", 7.078), B30("30m", 10.130),
    B20("20m", 14.078), B17("17m", 18.104), B15("15m", 21.078), B12("12m", 24.922), B10("10m", 28.078);

    companion object {
        /** Band for a dial frequency in Hz, or null if outside the HF amateur bands above. */
        fun forDialHz(hz: Long?): Band? {
            if (hz == null || hz <= 0) return null
            val mhz = hz / 1e6
            return entries.minByOrNull { kotlin.math.abs(it.mhz - mhz) }?.takeIf { kotlin.math.abs(it.mhz - mhz) < 0.6 }
        }
    }
}

/** Solar activity inputs. SFI is the 10.7 cm flux; K is the planetary K index (0–9). */
data class SpaceWeather(val sfi: Double, val kIndex: Double, val updatedMs: Long, val source: String) {
    /** Approximate sunspot number from SFI (common linear fit). */
    val ssn: Double get() = max(0.0, 1.14 * sfi - 73.21)
}

/** Where the sun is overhead, and how high it is anywhere. */
object Sun {
    fun subsolarPoint(timeMs: Long): LatLon {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMs }
        val dayOfYear = c.get(Calendar.DAY_OF_YEAR)
        val hours = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0 + c.get(Calendar.SECOND) / 3600.0
        val g = 2 * PI / 365.0 * (dayOfYear - 1 + (hours - 12) / 24)
        // NOAA approximations for declination and equation of time.
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val eqTimeMin = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val lon = -15.0 * (hours - 12 + eqTimeMin / 60.0)
        return LatLon(Math.toDegrees(decl), ((lon + 540) % 360) - 180)
    }

    /** Solar zenith angle in degrees (0 = overhead, 90 = horizon). */
    fun zenithDeg(p: LatLon, timeMs: Long): Double = zenithDeg(p, subsolarPoint(timeMs))

    /** Same, with the subsolar point already worked out (much faster in loops). */
    fun zenithDeg(p: LatLon, s: LatLon): Double {
        val phi = Math.toRadians(p.lat)
        val dec = Math.toRadians(s.lat)
        val h = Math.toRadians(p.lon - s.lon)
        val cosZ = sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h)
        return Math.toDegrees(acos(cosZ.coerceIn(-1.0, 1.0)))
    }
}

/**
 * A compact HF skywave model, calibrated against real reception reports.
 *
 * Structure (MINIMUF-like): F2 critical frequency at each hop's midpoint from
 * solar activity, sun angle (with the F2 layer's lag behind the sun), season
 * (winter anomaly), geomagnetic latitude and storms; curved-earth secant law
 * for the MUF; D-layer absorption for the LUF; plus sporadic-E for summer
 * short skip. Every coefficient in [K] was fitted to WSPR spots (wspr.live,
 * 2019-2026, held-out days and stations used for checking), see
 * tools/propdata. It answers "which band, how far, about now", not VOACAP.
 */
object PropagationModel {

    private const val R = 6371.0       // earth radius, km
    private const val MAX_HOP_KM = 3500.0
    private const val MAX_SECANT = 3.6
    /** Geomagnetic north pole (dipole approximation). */
    private val POLE = LatLon(80.8, -72.7)

    /** Fitted coefficients (see tools/propdata/README.md). */
    object K {
        const val A0 = 10.01; const val A1 = 0.02908          // daytime foF2 base and SSN slope
        const val B0 = 5.47; const val B1 = 0.004771        // night-time foF2 base and SSN slope
        const val WD = 0.2119; const val WN = 0.1671         // season: winter-day boost, summer-night boost
        const val C0 = 0.3118; const val P = 0.4933           // daylight curve
        const val LAG = 0.6103                               // weight of the sun 2 h earlier (F2 lag)
        const val HL = 0.6566; const val EQ = 0.9812         // high-latitude cut, equatorial-anomaly boost
        const val KS = 0.02864                              // storm depression per K above 3
        const val H = 318.4; const val MM = 1.142            // layer height (km), MUF scale
        const val KA = 0.008166                              // absorption growth with SSN
        const val L0 = 0.1276; const val L1 = 7.387; const val L2 = 1.94; const val L3 = 0.598  // LUF
        const val U0 = 1.037; const val U1 = 1.098; const val W = 5.685  // ramp shapes near MUF / LUF
        const val GW1 = 87.56; const val GW2 = 54.37         // ground-wave reach (km) below 4 / 10 MHz
        const val ES0 = 0.2626; const val ESW = 0.0001575; const val ESP = 6.955  // sporadic-E strength, season
        const val ESC = 6.079; const val ESK = 0.0345; const val ES2 = 0.2853   // its day curve, freq fall-off, 2-hop share
        /** Raw path score → display score (share of spots relative to the best paths, fitted). */
        val SCORE_REL = doubleArrayOf(0.0, 0.005, 0.074, 0.124, 0.174, 0.225, 0.274, 0.324, 0.374, 0.426, 0.474, 0.524, 0.575, 0.622, 0.673, 0.725, 0.78)
        val SCORE_VAL = doubleArrayOf(0.0, 0.056, 0.155, 0.214, 0.281, 0.373, 0.463, 0.548, 0.647, 0.741, 0.833, 0.871, 0.907, 0.917, 0.924, 0.973, 1.0)
    }

    /**
     * A path's propagation summary. [mufMHz]/[lufMHz] are the model's raw
     * limits; [medianMufMHz] is the frequency where the upper ramp is half
     * way, the number shown to the operator.
     */
    data class Path(
        val distanceKm: Double,
        val hops: Int,
        val mufMHz: Double,
        val lufMHz: Double,
        /** Sporadic-E strength before the frequency fall-off. */
        val esBase: Double = 0.0,
    ) {
        val medianMufMHz: Double get() = mufMHz * (K.U0 - 0.5 * K.U1)
        val fotMHz: Double get() = 0.85 * medianMufMHz
    }

    private fun sigmoid(x: Double) = 1 / (1 + kotlin.math.exp(-x))

    fun magLat(p: LatLon): Double {
        val phi = Math.toRadians(p.lat); val pl = Math.toRadians(POLE.lat)
        val s = sin(phi) * sin(pl) + cos(phi) * cos(pl) * cos(Math.toRadians(p.lon - POLE.lon))
        return Math.toDegrees(asin(s.coerceIn(-1.0, 1.0)))
    }

    /** Everything about the sky at one instant that the per-point maths needs. */
    class Sky(val timeMs: Long) {
        val sub: LatLon = Sun.subsolarPoint(timeMs)
        val subLag: LatLon = Sun.subsolarPoint(timeMs - 2 * 3_600_000L)
        val doy: Int = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMs }.get(Calendar.DAY_OF_YEAR)
        fun season(lat: Double) = cos(2 * PI * (doy - 172) / 365.0) * kotlin.math.sign(lat)
    }

    /** F2-layer critical frequency (MHz) over [p] at [timeMs]. */
    fun foF2(p: LatLon, timeMs: Long, wx: SpaceWeather): Double = foF2(p, Sky(timeMs), wx)

    fun foF2(p: LatLon, sky: Sky, wx: SpaceWeather): Double {
        val ssn = wx.ssn
        val cz = (1 - K.LAG) * cosZ(p, sky.sub) + K.LAG * cosZ(p, sky.subLag)
        val lit = (max(0.0, cz + K.C0) / (1 + K.C0) + 1e-6).pow(K.P)
        val s = sky.season(p.lat)
        val aml = kotlin.math.abs(magLat(p))
        val day = (K.A0 + K.A1 * ssn) * (1 - K.WD * s)
        val night = (K.B0 + K.B1 * ssn) * (1 + K.WN * s)
        var f = night + (day - night) * lit
        f *= 1 - K.HL * sigmoid((aml - 60) / 4)
        f *= 1 + K.EQ * kotlin.math.exp(-((aml - 15) / 10).pow(2)) * lit
        f *= 1 - K.KS * max(0.0, wx.kIndex - 3) * (1 + aml / 45)
        return max(1.0, f)
    }

    private fun cosZ(p: LatLon, sub: LatLon) = cos(Math.toRadians(Sun.zenithDeg(p, sub)))

    /** Secant of the incidence angle at the layer for a hop of [hopKm]. */
    fun secant(hopKm: Double): Double {
        val theta = hopKm / (2 * R)
        val ap = sqrt(R * R + (R + K.H).pow(2) - 2 * R * (R + K.H) * cos(theta))
        val sinI = (R * sin(theta) / ap).coerceIn(0.0, 0.999)
        return min(MAX_SECANT, 1 / sqrt(1 - sinI * sinI))
    }

    fun path(tx: LatLon, rx: LatLon, timeMs: Long, wx: SpaceWeather): Path = path(tx, rx, Sky(timeMs), wx)

    fun path(tx: LatLon, rx: LatLon, sky: Sky, wx: SpaceWeather): Path {
        val d = Geo.distanceKm(tx, rx)
        val hops = max(1, ceil(d / MAX_HOP_KM).toInt())
        val sec = secant(d / hops)
        var muf = Double.MAX_VALUE
        var absorption = 0.0
        var es = 0.0
        for (i in 0 until hops) {
            val mid = intermediate(tx, rx, (i + 0.5) / hops)
            muf = min(muf, foF2(mid, sky, wx) * sec)
            val cz = max(0.0, cosZ(mid, sky.sub))
            absorption = max(absorption, cz.pow(0.75) * (1 + K.KA * wx.ssn))
            if (i == 0) {
                val summer = K.ESW + ((1 + sky.season(mid.lat)) / 2).pow(K.ESP)
                val day = max(0.0, cosZ(mid, sky.sub) + K.ESC) / (1 + K.ESC)
                val midlat = kotlin.math.exp(-((kotlin.math.abs(magLat(mid)) - 40) / 18).pow(2))
                val one = sigmoid((d - 400) / 80) * sigmoid((2300 - d) / 200)
                val two = K.ES2 * sigmoid((d - 2300) / 200) * sigmoid((4500 - d) / 300)
                es = K.ES0 * summer * day * midlat * (one + two)
            }
        }
        val luf = K.L0 * (1 + K.L1 * absorption) * (1 + K.L2 * (sec - 1)) * hops.toDouble().pow(K.L3)
        return Path(d, hops, muf * K.MM, luf, es)
    }

    /** Model's raw path score (0..1) for [mhz], before display calibration. */
    fun rawScore(p: Path, mhz: Double): Double {
        val ground = if (mhz < 10) max(0.0, 1 - p.distanceKm / (if (mhz < 4) K.GW1 else K.GW2)) else 0.0
        val upper = ((p.mufMHz * K.U0 - mhz) / (K.U1 * p.mufMHz)).coerceIn(0.0, 1.0)
        val lower = ((mhz - p.lufMHz) / (K.W * p.lufMHz)).coerceIn(0.0, 1.0)
        val es = (p.esBase * kotlin.math.exp(-K.ESK * (mhz - 14))).coerceIn(0.0, 1.0)
        return max(max(ground, min(upper, lower)), es)
    }

    /**
     * 0..1 score that [mhz] works on [p], calibrated so that 1 means "as good
     * as the best paths in the reception data" (chart thresholds: 0.10 / 0.33 / 0.66).
     */
    fun reliability(p: Path, mhz: Double): Double = calibrate(rawScore(p, mhz))

    fun calibrate(raw: Double): Double {
        val xs = K.SCORE_REL; val ys = K.SCORE_VAL
        if (raw <= xs[0]) return ys[0]
        for (i in 1 until xs.size) {
            if (raw <= xs[i]) return ys[i - 1] + (ys[i] - ys[i - 1]) * (raw - xs[i - 1]) / (xs[i] - xs[i - 1])
        }
        return ys.last()
    }

    /** Point a fraction [f] of the way along the great circle from [a] to [b]. */
    fun intermediate(a: LatLon, b: LatLon, f: Double): LatLon {
        val lat1 = Math.toRadians(a.lat); val lon1 = Math.toRadians(a.lon)
        val lat2 = Math.toRadians(b.lat); val lon2 = Math.toRadians(b.lon)
        val d = 2 * asin(sqrt(sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin((lon2 - lon1) / 2).pow(2)))
        if (d < 1e-9) return a
        val x1 = sin((1 - f) * d) / sin(d)
        val x2 = sin(f * d) / sin(d)
        val x = x1 * cos(lat1) * cos(lon1) + x2 * cos(lat2) * cos(lon2)
        val y = x1 * cos(lat1) * sin(lon1) + x2 * cos(lat2) * sin(lon2)
        val z = x1 * sin(lat1) + x2 * sin(lat2)
        return LatLon(Math.toDegrees(atan2(z, sqrt(x * x + y * y))), Math.toDegrees(atan2(y, x)))
    }

    /** Average reliability for each distance bucket over 12 bearings: the band/distance stoplight. */
    fun stoplight(me: LatLon, band: Band, timeMs: Long, wx: SpaceWeather, bucketsKm: List<Double>): List<Double> {
        val sky = Sky(timeMs)
        return bucketsKm.map { km ->
            (0 until 12).map { i -> reliability(path(me, destination(me, i * 30.0, km), sky, wx), band.mhz) }.average()
        }
    }

    /** Distance-bucket centres used by the band chart (NVIS, regional, mid, long, DX). */
    val CHART_BUCKETS_KM = listOf(150.0, 500.0, 1400.0, 3000.0, 6000.0)
    /** Upper edges of the same buckets, for sorting observations into them. */
    val CHART_EDGES_KM = listOf(250.0, 800.0, 2000.0, 4000.0, Double.MAX_VALUE)

    /**
     * Predicted reliability from [me] to every point of a Web-Mercator grid
     * [width] × [height] spanning ±[maxLat]. Row 0 is the north edge. Used to
     * paint the coverage heatmap.
     */
    fun coverageGrid(me: LatLon, band: Band, timeMs: Long, wx: SpaceWeather, width: Int, height: Int, maxLat: Double): FloatArray {
        val sky = Sky(timeMs)
        val out = FloatArray(width * height)
        val yMax = mercY(maxLat)
        for (row in 0 until height) {
            val y = yMax - (row + 0.5) / height * 2 * yMax
            val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(y)))
            for (col in 0 until width) {
                val lon = -180.0 + (col + 0.5) / width * 360.0
                out[row * width + col] = reliability(path(me, LatLon(lat, lon), sky, wx), band.mhz).toFloat()
            }
        }
        return out
    }

    fun mercY(latDeg: Double): Double {
        val phi = Math.toRadians(latDeg)
        return kotlin.math.ln(kotlin.math.tan(PI / 4 + phi / 2))
    }

    /**
     * Day/night boundary: for each longitude, the latitude where the sun is
     * on the horizon. Returns (lon, lat) pairs west to east.
     */
    fun terminator(timeMs: Long, steps: Int = 180): List<LatLon> {
        val sub = Sun.subsolarPoint(timeMs)
        val dec = Math.toRadians(sub.lat).let { if (kotlin.math.abs(it) < 1e-4) 1e-4 else it }
        return (0..steps).map { i ->
            val lon = -180.0 + 360.0 * i / steps
            val h = Math.toRadians(lon - sub.lon)
            LatLon(Math.toDegrees(kotlin.math.atan(-cos(h) / kotlin.math.tan(dec))), lon)
        }
    }

    /** Point [km] from [p] along [bearingDeg]. */
    fun destination(p: LatLon, bearingDeg: Double, km: Double): LatLon {
        val d = km / R
        val b = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(p.lat); val lon1 = Math.toRadians(p.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(Math.toDegrees(lat2), ((Math.toDegrees(lon2) + 540) % 360) - 180)
    }
}
