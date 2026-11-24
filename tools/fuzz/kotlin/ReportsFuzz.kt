package com.js8call.example.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.js8call.example.reports.Geo
import com.js8call.example.reports.Reports

/**
 * Report lines (#CHECKIN, #SALUTE, …) and the locations and date-time groups inside them, all
 * read from text received over the air.
 *
 * Checks: any report the app sends reads back with the same values; a parsed location is a
 * real place on Earth; a parsed date-time group is a valid time, not in the future.
 */
object ReportsFuzz {
    @JvmStatic
    fun fuzzerTestOneInput(d: FuzzedDataProvider) {
        // Round trip: what we put on the air must read back the same.
        val type = d.pickValue(Reports.ALL)
        val values = linkedMapOf<String, String>()
        for (f in type.fields) if (d.consumeBoolean()) values[f.key] = d.consumeString(30)
        val line = Reports.encode(type, values)
        val back = Reports.parse(line) ?: error("our own report line didn't parse: $line")
        check(back.type == type) { "type changed: $line" }
        val expected = values.mapValues { Reports.clean(it.value) }.filterValues { it.isNotEmpty() }
        check(back.values == expected) { "values changed: $line → ${back.values}, expected $expected" }

        val now = d.consumeLong(0, 4_000_000_000_000L)
        val dtg = d.consumeString(10)
        Reports.dtgToMs(dtg, now)?.let { t ->
            check(t <= now + 5 * 60_000L) { "DTG $dtg is in the future" }
            val digits = dtg.trim().uppercase().removeSuffix("Z")
            val day = digits.substring(0, 2).toInt()
            val hour = digits.substring(2, 4).toInt()
            val minute = digits.substring(4, 6).toInt()
            check(day in 1..31 && hour in 0..23 && minute in 0..59) { "accepted impossible DTG $dtg" }
        }

        val text = d.consumeRemainingAsString()
        Reports.parse(text)?.let { r ->
            r.location
            r.summary
        }
        Geo.parseLocation(text)?.let { p ->
            check(p.lat in -90.0..90.0 && p.lon in -180.0..180.0) { "location off the Earth: $p from '$text'" }
        }
    }
}
