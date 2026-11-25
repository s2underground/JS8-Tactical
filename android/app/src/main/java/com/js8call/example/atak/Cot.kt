package com.js8call.example.atak

import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import com.js8call.example.reports.Reports
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Cursor-on-Target (CoT) XML for ATAK, WinTAK and iTAK.
 *
 * Every geolocated report heard on JS8 becomes an ATAK "spot map" point
 * (type b-m-p-s-m, the same coloured dots a user drops by hand), so a TAK user
 * sees JS8 traffic on the map without any plugin. The full report goes in the
 * remarks as plain text.
 *
 * Location precision is honest: the point's circular error (ce) comes from the
 * precision of what was sent: a 4-character grid square is ±~100 km, an
 * 8-digit MGRS is ±7 m. ATAK shows this in the point's details.
 *
 * Pure Kotlin; no Android, so it is unit-tested.
 */
object Cot {

    // ATAK spot-map colours (signed ARGB ints, as ATAK writes them).
    const val RED = -65536            // 0xFFFF0000
    const val YELLOW = -256           // 0xFFFFFF00
    const val GREEN = -16711936       // 0xFF00FF00
    const val CYAN = -16711681        // 0xFF00FFFF
    const val MAGENTA = -65281        // 0xFFFF00FF
    const val WHITE = -1

    const val SPOT_TYPE = "b-m-p-s-m"
    private const val UID_PREFIX = "JS8-Tactical"

    data class Point(
        val uid: String,
        val type: String,
        val callsign: String,
        val position: LatLon,
        /** Circular error, metres (9999999 = unknown). */
        val ceM: Double,
        val timeMs: Long,
        val staleMs: Long,
        val argb: Int,
        val remarks: String,
    ) {
        /** Changes whenever anything a user would see changes; used to avoid resending duplicates. */
        val signature: String get() = "$type|$callsign|${position.lat}|${position.lon}|$ceM|$argb|$remarks|$timeMs"
    }

    /** Colour for a report: status reports by their status, observation reports magenta, positions cyan. */
    fun colorFor(report: Reports.Report): Int {
        val status = report.values["S"]?.trim()?.uppercase().orEmpty()
        return when (report.type) {
            Reports.CHECKIN -> when {
                status.startsWith("EMERG") -> RED
                status.startsWith("NEED") -> YELLOW
                status.startsWith("GOOD") -> GREEN
                else -> CYAN
            }
            Reports.SITREP -> when {
                status.startsWith("RED") -> RED
                status.startsWith("AMBER") -> YELLOW
                status.startsWith("GREEN") -> GREEN
                else -> CYAN
            }
            Reports.SPOT, Reports.SALUTE -> MAGENTA
            Reports.JAM -> YELLOW
            else -> CYAN
        }
    }

    /**
     * A received report as a spot-map point. It goes stale [staleAfterMs] after it was heard.
     * @param messageId a stable key for the message (first-heard time, seconds), so the same
     *   report keeps the same uid across app restarts.
     */
    fun reportPoint(
        report: Reports.Report,
        sender: String?,
        position: LatLon,
        heardMs: Long,
        messageId: Long,
        staleAfterMs: Long,
        nowMs: Long,
    ): Point {
        val from = sender?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
        val status = report.values["S"]?.takeIf { report.type == Reports.CHECKIN || report.type == Reports.SITREP }
        // "(JS8)" marks it as heard over the radio, not from a TAK user: anyone can transmit a
        // report under any callsign, and ATAK passes the point on to the whole team (audit I2).
        val name = listOfNotNull(report.type.tag, from, status?.let { "($it)" }, "(JS8)").joinToString(" ")
        val locText = report.type.locationField?.let { report.values[it.key] }.orEmpty()
        val lines = mutableListOf<String>()
        lines += "${report.type.title} from $from, heard on JS8 ${utcHuman(heardMs)}"
        lines += "Unverified: heard over the radio. JS8 has no sign-in, so anyone can send under any callsign."
        report.type.fields.forEach { f -> report.values[f.key]?.let { lines += "${f.label}: $it" } }
        lines += "Position as sent: $locText (${precisionText(locText)})"
        lines += "Raw: ${Reports.encode(report.type, report.values)}"
        return Point(
            uid = "$UID_PREFIX.$from.${report.type.tag}.$messageId".replace(Regex("[^A-Za-z0-9._/-]"), "_"),
            type = SPOT_TYPE,
            callsign = name,
            position = position,
            ceM = ceFor(locText),
            timeMs = heardMs,
            staleMs = heardMs + staleAfterMs,
            argb = colorFor(report),
            remarks = lines.joinToString("\n"),
        )
    }

    /** A heard station at the centre of its grid square (white). */
    fun stationPoint(
        call: String,
        grid: String,
        position: LatLon,
        heardMs: Long,
        snr: Int,
        staleAfterMs: Long,
        nowMs: Long,
    ): Point = Point(
        uid = "$UID_PREFIX.station.$call".replace(Regex("[^A-Za-z0-9._/-]"), "_"),
        type = SPOT_TYPE,
        callsign = "$call (JS8)",
        position = position,
        ceM = ceFor(grid),
        timeMs = heardMs,
        staleMs = heardMs + staleAfterMs,
        argb = WHITE,
        remarks = "JS8 station $call, last heard ${utcHuman(heardMs)} at $snr dB.\n" +
            "Grid $grid: the dot is the centre of the square (${precisionText(grid)}), not the station's exact position.",
    )

    /** A short-lived test point so a user can check the ATAK connection. */
    fun testPoint(position: LatLon, nowMs: Long): Point = Point(
        uid = "$UID_PREFIX.test",
        type = SPOT_TYPE,
        callsign = "JS8-Tactical test",
        position = position,
        ceM = 9_999_999.0,
        timeMs = nowMs,
        staleMs = nowMs + 5 * 60_000L,
        argb = CYAN,
        remarks = "Test point from JS8-Tactical. If you can see this, ATAK is receiving. It disappears after 5 minutes.",
    )

    /** CoT event XML for a point. */
    fun xml(p: Point, nowMs: Long): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<event version="2.0" uid="${esc(p.uid)}" type="${esc(p.type)}" how="h-g-i-g-o" """)
        append("""time="${iso(nowMs)}" start="${iso(nowMs)}" stale="${iso(p.staleMs)}">""")
        append("""<point lat="${num(p.position.lat)}" lon="${num(p.position.lon)}" hae="9999999.0" ce="${num(p.ceM)}" le="9999999.0"/>""")
        append("<detail>")
        append("""<status readiness="true"/>""")
        append("""<contact callsign="${esc(p.callsign)}"/>""")
        append("""<color argb="${p.argb}"/>""")
        append("""<usericon iconsetpath="COT_MAPPING_SPOTMAP/${p.type}/${p.argb}"/>""")
        append("""<precisionlocation altsrc="???" geopointsrc="???"/>""")
        append("<remarks>").append(esc(p.remarks)).append("</remarks>")
        append("</detail></event>")
    }

    /** Remove a point from every receiving TAK client (ATAK's own remote delete). */
    fun deleteXml(uid: String, nowMs: Long): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<event version="2.0" uid="${esc(java.util.UUID.nameUUIDFromBytes("delete:$uid".toByteArray()).toString())}" """)
        append("""type="t-x-d-d" how="h-g-i-g-o" time="${iso(nowMs)}" start="${iso(nowMs)}" stale="${iso(nowMs + 20_000L)}">""")
        append("""<point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>""")
        append("""<detail><link uid="${esc(uid)}" relation="none" type="none"/><__forcedelete/></detail></event>""")
    }

    // ------------------------------------------------------------------ precision

    /**
     * Circular error (m) implied by how a location was written: half the cell
     * diagonal for MGRS and Maidenhead; 10 m for decimal lat/lon with ≥4 decimals.
     */
    fun ceFor(location: String): Double {
        val t = location.trim().uppercase().replace(" ", "")
        // MGRS: zone+band, two letters, then an even number of digits.
        Regex("""^\d{1,2}[C-X][A-Z]{2}(\d*)$""").find(t)?.let { m ->
            val digits = m.groupValues[1].length / 2
            val cell = Math.pow(10.0, (5 - digits).toDouble())
            return cell * 0.7071
        }
        if (Regex("^[A-R]{2}([0-9]{2}([A-X]{2}([0-9]{2})?)?)?$").matches(t)) {
            val ll = Geo.maidenheadToLatLon(t)
            val latCos = Math.cos(Math.toRadians(ll?.lat ?: 0.0))
            val (dLat, dLon) = when (t.length) {
                2 -> 10.0 to 20.0
                4 -> 1.0 to 2.0
                6 -> 2.5 / 60 to 5.0 / 60
                else -> 0.25 / 60 to 0.5 / 60
            }
            val h = dLat * 111_320.0
            val w = dLon * 111_320.0 * latCos
            return 0.5 * Math.sqrt(h * h + w * w)
        }
        val ll = Regex("""^-?\d+(?:\.(\d+))?,-?\d+(?:\.(\d+))?$""").find(t)
        if (ll != null) {
            val dec = minOf(ll.groupValues[1].length, ll.groupValues[2].length)
            return when {
                dec >= 4 -> 10.0
                dec == 3 -> 80.0
                dec == 2 -> 800.0
                dec == 1 -> 8_000.0
                else -> 80_000.0
            }
        }
        return 9_999_999.0
    }

    fun precisionText(location: String): String {
        val ce = ceFor(location)
        return when {
            ce >= 9_999_999.0 -> "precision unknown"
            ce >= 1000 -> "±${Math.round(ce / 1000.0)} km"
            else -> "±${Math.round(ce)} m"
        }
    }

    // ------------------------------------------------------------------ helpers

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            '\n' -> append("&#10;")
            else -> if (c < ' ' && c != '\t') append(' ') else append(c)
        }
    }

    private fun num(d: Double): String = String.format(Locale.US, "%.7f", d).trimEnd('0').let { if (it.endsWith(".")) it + "0" else it }

    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun utcHuman(ms: Long): String = SimpleDateFormat("dd MMM HH:mm'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
