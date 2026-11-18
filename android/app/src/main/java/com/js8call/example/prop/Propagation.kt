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
 * A deliberately simple HF skywave model, in the spirit of MINIMUF:
 * F2 critical frequency from solar activity and sun angle at each hop's
 * midpoint, curved-earth secant law for the MUF, and a daytime D-layer
 * absorption estimate for the LUF. It is an estimate for "which band, how
 * far, right now", not a VOACAP replacement.
 */
object PropagationModel {

    private const val R = 6371.0       // earth radius, km
    private const val H_F2 = 300.0     // reflection height, km
    private const val MAX_HOP_KM = 3500.0
    private const val MAX_SECANT = 3.6
    /** JS8 decodes far below SSB levels, which lowers the usable floor. */
    private const val JS8_LUF_FACTOR = 0.75

    data class Path(
        val distanceKm: Double,
        val hops: Int,
        val mufMHz: Double,
        val lufMHz: Double,
    ) {
        val fotMHz: Double get() = 0.85 * mufMHz
    }

    /** F2-layer critical frequency (MHz) over [p] at [timeMs]. */
    fun foF2(p: LatLon, timeMs: Long, wx: SpaceWeather): Double = foF2(p, Sun.subsolarPoint(timeMs), wx)

    fun foF2(p: LatLon, sub: LatLon, wx: SpaceWeather): Double {
        val ssn = wx.ssn
        val day = 4.2 + 0.042 * ssn
        val night = 2.3 + 0.021 * ssn
        val cosZ = cos(Math.toRadians(Sun.zenithDeg(p, sub)))
        // Lit layer: fades out a few degrees after sunset at layer height.
        val lit = (max(0.0, cosZ + 0.1) / 1.1).pow(0.5)
        var f = night + (day - night) * lit
        // High latitudes run lower, and geomagnetic storms depress the layer.
        if (kotlin.math.abs(p.lat) > 60) f *= 0.85
        val storm = max(0.0, wx.kIndex - 3)
        f *= 1 - 0.04 * storm * (if (kotlin.math.abs(p.lat) > 45) 1.5 else 1.0)
        return max(1.0, f)
    }

    /** Secant of the incidence angle at the layer for a hop of [hopKm]. */
    fun secant(hopKm: Double): Double {
        val theta = hopKm / (2 * R)
        val ap = sqrt(R * R + (R + H_F2).pow(2) - 2 * R * (R + H_F2) * cos(theta))
        val sinI = (R * sin(theta) / ap).coerceIn(0.0, 0.999)
        return min(MAX_SECANT, 1 / sqrt(1 - sinI * sinI))
    }

    fun path(tx: LatLon, rx: LatLon, timeMs: Long, wx: SpaceWeather): Path = path(tx, rx, Sun.subsolarPoint(timeMs), wx)

    fun path(tx: LatLon, rx: LatLon, sub: LatLon, wx: SpaceWeather): Path {
        val d = Geo.distanceKm(tx, rx)
        val hops = max(1, ceil(d / MAX_HOP_KM).toInt())
        val hopKm = d / hops
        val sec = secant(hopKm)
        var muf = Double.MAX_VALUE
        var absorption = 0.0
        for (i in 0 until hops) {
            val mid = intermediate(tx, rx, (i + 0.5) / hops)
            muf = min(muf, foF2(mid, sub, wx) * sec)
            val cosZ = max(0.0, cos(Math.toRadians(Sun.zenithDeg(mid, sub))))
            absorption = max(absorption, cosZ.pow(0.75) * (1 + 0.0037 * wx.ssn))
        }
        val luf = (1.0 + 3.6 * absorption) * (1 + 0.25 * (sec - 1)) * hops.toDouble().pow(0.3) * JS8_LUF_FACTOR
        return Path(d, hops, muf, luf)
    }

    /** 0..1 chance-like score that [mhz] works on [p]. */
    fun reliability(p: Path, mhz: Double): Double {
        // Ground wave covers short range on the lower bands regardless.
        val ground = if (mhz < 10) max(0.0, 1 - p.distanceKm / (if (mhz < 4) 80.0 else 50.0)) else 0.0
        val upper = ((p.mufMHz * 1.05 - mhz) / (0.2 * p.mufMHz)).coerceIn(0.0, 1.0)
        val lower = ((mhz - p.lufMHz) / (0.25 * p.lufMHz)).coerceIn(0.0, 1.0)
        return max(ground, min(upper, lower))
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
        val sub = Sun.subsolarPoint(timeMs)
        return bucketsKm.map { km ->
            (0 until 12).map { i -> reliability(path(me, destination(me, i * 30.0, km), sub, wx), band.mhz) }.average()
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
        val sub = Sun.subsolarPoint(timeMs)
        val out = FloatArray(width * height)
        val yMax = mercY(maxLat)
        for (row in 0 until height) {
            val y = yMax - (row + 0.5) / height * 2 * yMax
            val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(y)))
            for (col in 0 until width) {
                val lon = -180.0 + (col + 0.5) / width * 360.0
                out[row * width + col] = reliability(path(me, LatLon(lat, lon), sub, wx), band.mhz).toFloat()
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
