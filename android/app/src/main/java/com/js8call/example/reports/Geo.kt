package com.js8call.example.reports

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class LatLon(val lat: Double, val lon: Double)

/**
 * Coordinate conversions used by reports and the map: MGRS (WGS84 UTM with
 * 100 km grid letters) and Maidenhead locators. No network, no libraries.
 */
object Geo {

    // ------------------------------------------------------------ Maidenhead

    /** Centre of a 2-, 4-, 6- or 8-character Maidenhead locator. */
    fun maidenheadToLatLon(locator: String): LatLon? {
        val g = locator.trim().uppercase()
        if (!Regex("^[A-R]{2}([0-9]{2}([A-X]{2}([0-9]{2})?)?)?$").matches(g)) return null
        var lon = -180.0 + (g[0] - 'A') * 20.0
        var lat = -90.0 + (g[1] - 'A') * 10.0
        var lonSize = 20.0
        var latSize = 10.0
        if (g.length >= 4) {
            lon += (g[2] - '0') * 2.0; lat += (g[3] - '0') * 1.0
            lonSize = 2.0; latSize = 1.0
        }
        if (g.length >= 6) {
            lon += (g[4] - 'A') * (5.0 / 60.0); lat += (g[5] - 'A') * (2.5 / 60.0)
            lonSize = 5.0 / 60.0; latSize = 2.5 / 60.0
        }
        if (g.length >= 8) {
            lon += (g[6] - '0') * (0.5 / 60.0); lat += (g[7] - '0') * (0.25 / 60.0)
            lonSize = 0.5 / 60.0; latSize = 0.25 / 60.0
        }
        return LatLon(lat + latSize / 2, lon + lonSize / 2)
    }

    /** Maidenhead locator of [chars] length (2, 4, 6 or 8). */
    fun latLonToMaidenhead(p: LatLon, chars: Int = 6): String {
        var lon = p.lon + 180.0
        var lat = p.lat + 90.0
        val sb = StringBuilder()
        sb.append('A' + (lon / 20).toInt().coerceIn(0, 17)); sb.append('A' + (lat / 10).toInt().coerceIn(0, 17))
        lon %= 20.0; lat %= 10.0
        if (chars >= 4) {
            sb.append('0' + (lon / 2).toInt()); sb.append('0' + lat.toInt())
            lon %= 2.0; lat %= 1.0
        }
        if (chars >= 6) {
            sb.append('A' + (lon / (5.0 / 60)).toInt()); sb.append('A' + (lat / (2.5 / 60)).toInt())
            lon %= 5.0 / 60; lat %= 2.5 / 60
        }
        if (chars >= 8) {
            sb.append('0' + (lon / (0.5 / 60)).toInt()); sb.append('0' + (lat / (0.25 / 60)).toInt())
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ UTM / MGRS

    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private val E2 = F * (2 - F)
    private val EP2 = E2 / (1 - E2)

    data class Utm(val zone: Int, val northern: Boolean, val easting: Double, val northing: Double)

    private fun zoneFor(lat: Double, lon: Double): Int {
        var zone = floor((lon + 180) / 6).toInt() + 1
        if (zone > 60) zone = 60
        // Norway and Svalbard exceptions.
        if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) zone = 32
        if (lat >= 72 && lat < 84) {
            zone = when {
                lon >= 0 && lon < 9 -> 31
                lon >= 9 && lon < 21 -> 33
                lon >= 21 && lon < 33 -> 35
                lon >= 33 && lon < 42 -> 37
                else -> zone
            }
        }
        return zone
    }

    fun latLonToUtm(p: LatLon, forceZone: Int? = null): Utm {
        val zone = forceZone ?: zoneFor(p.lat, p.lon)
        val phi = Math.toRadians(p.lat)
        val lon0 = Math.toRadians((zone - 1) * 6.0 - 180 + 3)
        val n = A / sqrt(1 - E2 * sin(phi).pow(2))
        val t = tan(phi).pow(2)
        val c = EP2 * cos(phi).pow(2)
        val a = cos(phi) * (Math.toRadians(p.lon) - lon0)
        val e4 = E2 * E2
        val e6 = e4 * E2
        val m = A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
            (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
            (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
            (35 * e6 / 3072) * sin(6 * phi))
        val easting = K0 * n * (a + (1 - t + c) * a.pow(3) / 6 +
            (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a.pow(5) / 120) + 500000.0
        var northing = K0 * (m + n * tan(phi) * (a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a.pow(6) / 720))
        if (p.lat < 0) northing += 10_000_000.0
        return Utm(zone, p.lat >= 0, easting, northing)
    }

    fun utmToLatLon(u: Utm): LatLon {
        val x = u.easting - 500000.0
        val y = if (u.northern) u.northing else u.northing - 10_000_000.0
        val lon0 = (u.zone - 1) * 6.0 - 180 + 3
        val e4 = E2 * E2
        val e6 = e4 * E2
        val m = y / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256))
        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) + (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val n1 = A / sqrt(1 - E2 * sin(phi1).pow(2))
        val t1 = tan(phi1).pow(2)
        val c1 = EP2 * cos(phi1).pow(2)
        val r1 = A * (1 - E2) / (1 - E2 * sin(phi1).pow(2)).pow(1.5)
        val d = x / (n1 * K0)
        val lat = phi1 - (n1 * tan(phi1) / r1) * (d * d / 2 -
            (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d.pow(4) / 24 +
            (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d.pow(6) / 720)
        val lon = (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
            (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d.pow(5) / 120) / cos(phi1)
        return LatLon(Math.toDegrees(lat), lon0 + Math.toDegrees(lon))
    }

    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private val COL_SETS = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val ROWS = "ABCDEFGHJKLMNPQRSTUV"

    private fun bandFor(lat: Double): Char = BANDS[((lat + 80) / 8).toInt().coerceIn(0, BANDS.length - 1)]

    /**
     * MGRS string such as "18S UJ 2348 0648" with [digits] per axis
     * (1 = 10 km, 2 = 1 km, 3 = 100 m, 4 = 10 m, 5 = 1 m). Null outside 80S–84N.
     */
    fun latLonToMgrs(p: LatLon, digits: Int = 2): String? {
        if (p.lat < -80 || p.lat > 84) return null
        val u = latLonToUtm(p)
        val colLetters = COL_SETS[(u.zone - 1) % 3]
        val col = colLetters[(floor(u.easting / 100000).toInt() - 1).coerceIn(0, 7)]
        val rowOffset = if (u.zone % 2 == 0) 5 else 0
        val row = ROWS[(floor(u.northing / 100000).toInt() + rowOffset) % 20]
        val scale = 10.0.pow(5 - digits)
        val e = (floor((u.easting % 100000) / scale)).toInt()
        val n = (floor((u.northing % 100000) / scale)).toInt()
        val fmt = "%0${digits}d"
        return if (digits == 0) "${u.zone}${bandFor(p.lat)} $col$row"
        else "${u.zone}${bandFor(p.lat)} $col$row ${fmt.format(e)} ${fmt.format(n)}"
    }

    private val mgrsRegex = Regex("""^(\d{1,2})\s*([C-HJ-NP-X])\s*([A-HJ-NP-Z])([A-HJ-NP-V])\s*(\d*)\s*(\d*)$""")

    /** Centre of the MGRS square, or null if [text] isn't MGRS. */
    fun mgrsToLatLon(text: String): LatLon? {
        val m = mgrsRegex.find(text.trim().uppercase()) ?: return null
        val zone = m.groupValues[1].toInt()
        if (zone !in 1..60) return null
        val band = m.groupValues[2][0]
        val colLetter = m.groupValues[3][0]
        val rowLetter = m.groupValues[4][0]
        // Either "1234 5678" (two equal groups) or "12345678" run together.
        val split = m.groupValues[6].isNotEmpty()
        if (split && m.groupValues[5].length != m.groupValues[6].length) return null
        val digits = m.groupValues[5] + m.groupValues[6]
        if (digits.length % 2 != 0 || digits.length > 10) return null
        val half = digits.length / 2
        val precision = 10.0.pow(5 - half)
        val e = if (half == 0) 0.0 else digits.substring(0, half).toDouble() * precision
        val n = if (half == 0) 0.0 else digits.substring(half).toDouble() * precision

        val col = COL_SETS[(zone - 1) % 3].indexOf(colLetter)
        if (col < 0) return null
        val rowOffset = if (zone % 2 == 0) 5 else 0
        val rowIndex = Math.floorMod(ROWS.indexOf(rowLetter) - rowOffset, 20)
        if (ROWS.indexOf(rowLetter) < 0) return null

        val easting = (col + 1) * 100000.0 + e + precision / 2
        val northern = band >= 'N'
        // Lowest northing in this latitude band, then pick the 2,000 km cycle that lands in it.
        val bandSouth = -80.0 + BANDS.indexOf(band) * 8.0
        val lon0 = (zone - 1) * 6.0 - 180 + 3
        val minNorthing = latLonToUtm(LatLon(bandSouth, lon0), zone).northing - 100000
        var northing = rowIndex * 100000.0 + n + precision / 2
        while (northing < minNorthing) northing += 2_000_000.0
        return utmToLatLon(Utm(zone, northern, easting, northing))
    }

    /** Parse MGRS, a Maidenhead locator, or "lat,lon". */
    fun parseLocation(text: String): LatLon? {
        val t = text.trim()
        if (t.isEmpty()) return null
        mgrsToLatLon(t)?.let { return it }
        maidenheadToLatLon(t)?.let { return it }
        val ll = Regex("""^(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)$""").find(t) ?: return null
        val lat = ll.groupValues[1].toDouble()
        val lon = ll.groupValues[2].toDouble()
        return if (abs(lat) <= 90 && abs(lon) <= 180) LatLon(lat, lon) else null
    }

    /** Great-circle distance in km. */
    fun distanceKm(a: LatLon, b: LatLon): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * Math.asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [a] to [b] in degrees (0 = north). */
    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
    }
}
