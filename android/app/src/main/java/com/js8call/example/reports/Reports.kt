package com.js8call.example.reports

/**
 * Structured reports sent as one compact, human-readable JS8 line:
 *
 *     #SALUTE S:4 A:LINE AT FUEL STATION L:17S NU 1234 5678 U:CIVILIANS T:181320Z E:CARS
 *
 * Stock JS8Call users read it as plain text; this app parses it back into
 * fields for the report card, the map and ATAK. Keys are one or two letters
 * to save airtime. Empty fields are left out.
 */
object Reports {

    enum class Kind { TEXT, LOCATION, TIME, CHOICE, FREQUENCY }

    data class Field(
        val key: String,
        val label: String,
        val kind: Kind = Kind.TEXT,
        val hint: String = "",
        val options: List<String> = emptyList(),
    )

    data class Type(val tag: String, val title: String, val fields: List<Field>) {
        fun field(key: String) = fields.firstOrNull { it.key == key }
        val locationField: Field? get() = fields.firstOrNull { it.kind == Kind.LOCATION }
    }

    data class Report(val type: Type, val values: Map<String, String>) {
        val location: LatLon? get() = type.locationField?.let { values[it.key] }?.let { Geo.parseLocation(it) }
        val summary: String
            get() = type.fields.filter { it.kind != Kind.LOCATION && it.kind != Kind.TIME }
                .mapNotNull { values[it.key] }.joinToString(", ")
    }

    val CHECKIN = Type(
        "CHECKIN", "Check-in", listOf(
            Field("S", "Status", Kind.CHOICE, options = listOf("GOOD", "NEED", "EMERG")),
            Field("L", "Location", Kind.LOCATION),
            Field("N", "Note", hint = "Optional"),
        )
    )
    val SPOT = Type(
        "SPOT", "SPOT report", listOf(
            Field("S", "Size", hint = "How many / how big"),
            Field("A", "Activity", hint = "What they are doing"),
            Field("L", "Location", Kind.LOCATION),
            Field("T", "Time", Kind.TIME),
        )
    )
    val SALUTE = Type(
        "SALUTE", "SALUTE report", listOf(
            Field("S", "Size", hint = "Number of people or vehicles"),
            Field("A", "Activity", hint = "What they are doing"),
            Field("L", "Location", Kind.LOCATION),
            Field("U", "Unit", hint = "Who: uniforms, markings, civilians"),
            Field("T", "Time", Kind.TIME),
            Field("E", "Equipment", hint = "Vehicles, gear"),
        )
    )
    val SITREP = Type(
        "SITREP", "Situation report", listOf(
            Field("S", "Status", Kind.CHOICE, options = listOf("GREEN", "AMBER", "RED")),
            Field("L", "Location", Kind.LOCATION),
            Field("R", "Needs", hint = "Water, fuel, medical…"),
            Field("N", "Notes"),
        )
    )
    val POSREP = Type(
        "POSREP", "Position report", listOf(
            Field("L", "Location", Kind.LOCATION),
            Field("S", "Status", hint = "Moving, holding, arrived…"),
            Field("T", "Time", Kind.TIME),
        )
    )

    /** Solar numbers passed station to station so off-grid stations can keep predictions current. */
    val SWX = Type(
        "SWX", "Solar data", listOf(
            Field("F", "Solar flux (SFI)"),
            Field("K", "K index"),
            Field("T", "Observed", Kind.TIME),
        )
    )

    /** Local weather observation (later filled from the Anemos plugin). */
    val WX = Type(
        "WX", "Weather", listOf(
            Field("L", "Location", Kind.LOCATION),
            Field("T", "Observed", Kind.TIME),
            Field("C", "Conditions", hint = "Clear, overcast, rain, fog, snow…"),
            Field("TEMP", "Temperature", hint = "e.g. 41F or 5C"),
            Field("W", "Wind", hint = "e.g. NW 15G25 MPH"),
            Field("P", "Pressure", hint = "e.g. 1013 MB falling"),
            Field("R", "Precipitation", hint = "e.g. 0.4 IN last 24 h"),
            Field("V", "Visibility", hint = "e.g. 2 MI"),
        )
    )

    /**
     * Interference or jamming on a frequency, after the military MIJI report (meaconing,
     * intrusion, jamming, interference): what it sounds like, how strong, what it does to
     * traffic, where and when. "Interference" until the operator judges it deliberate; the
     * report says what was observed either way. Without a location, the map places it at the
     * sender's grid (where it was heard).
     */
    val JAM = Type(
        "JAM", "Interference / jamming", listOf(
            Field("F", "Frequency", Kind.FREQUENCY, hint = "Dial in kHz, e.g. 7078"),
            Field("Y", "Type", Kind.CHOICE, options = listOf("NOISE", "CARRIER", "SWEEP", "PULSE", "VOICE", "OTHER")),
            Field("S", "Strength", hint = "e.g. S9, or 15DB over normal"),
            Field("E", "Effect", Kind.CHOICE, options = listOf("DEGRADED", "DENIED")),
            Field("L", "Location", Kind.LOCATION),
            Field("T", "Time", Kind.TIME),
            Field("N", "Note", hint = "Optional: since when, pattern, other bands"),
        )
    )

    val ALL = listOf(CHECKIN, SPOT, SALUTE, SITREP, POSREP, JAM, WX, SWX)

    /** A dial frequency in kHz as written in reports: 7078000 → "7078", 14078500 → "14078.5". */
    fun khz(dialHz: Long): String {
        val whole = dialHz / 1000
        val frac = (dialHz % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
    }
    fun byTag(tag: String) = ALL.firstOrNull { it.tag == tag.uppercase() }

    /** Clean a value for the air: upper case, no field separators, single spaces. */
    fun clean(value: String): String =
        value.uppercase().replace(Regex("[:#]"), " ").replace(Regex("[^ -~]"), "").replace(Regex("\\s+"), " ").trim()

    fun encode(type: Type, values: Map<String, String>): String {
        val parts = type.fields.mapNotNull { f ->
            values[f.key]?.let(::clean)?.takeIf { it.isNotEmpty() }?.let { "${f.key}:$it" }
        }
        return (listOf("#${type.tag}") + parts).joinToString(" ")
    }

    /**
     * Parse a report out of a message body. The "#TAG" may follow other text
     * (e.g. a relay prefix). Returns null if there is no known report tag.
     */
    fun parse(text: String): Report? {
        val m = Regex("""(^|\s)#([A-Z]+)\b""").find(text.uppercase()) ?: return null
        val type = byTag(m.groupValues[2]) ?: return null
        val rest = text.uppercase().substring(m.range.last + 1)
        val keys = type.fields.joinToString("|") { Regex.escape(it.key) }
        val keyRe = Regex("""(?:^|\s)($keys):""")
        val hits = keyRe.findAll(rest).toList()
        val values = linkedMapOf<String, String>()
        hits.forEachIndexed { i, hit ->
            val start = hit.range.last + 1
            val end = if (i + 1 < hits.size) hits[i + 1].range.first else rest.length
            val v = rest.substring(start, end).trim()
            if (v.isNotEmpty()) values[hit.groupValues[1]] = v
        }
        return Report(type, values)
    }

    /** Date-time group for now, e.g. "181342Z". */
    /** "181900Z" → the most recent moment (not in the future) with that day, hour and minute; null if malformed. */
    fun dtgToMs(dtg: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val m = Regex("""^(\d{2})(\d{2})(\d{2})Z?$""").find(dtg.trim().uppercase()) ?: return null
        val (d, h, mi) = m.destructured
        // Calendar would quietly roll "312599Z" over into the next day; refuse impossible times instead.
        if (d.toInt() !in 1..31 || h.toInt() > 23 || mi.toInt() > 59) return null
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
        repeat(2) {
            val t = (c.clone() as java.util.Calendar).apply {
                if (d.toInt() > getActualMaximum(java.util.Calendar.DAY_OF_MONTH)) return@repeat
                set(java.util.Calendar.DAY_OF_MONTH, d.toInt()); set(java.util.Calendar.HOUR_OF_DAY, h.toInt())
                set(java.util.Calendar.MINUTE, mi.toInt()); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            if (t <= nowMs + 5 * 60_000L) return t
            c.add(java.util.Calendar.MONTH, -1)
        }
        return null
    }

    fun dtg(nowMs: Long = System.currentTimeMillis()): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
        return "%02d%02d%02dZ".format(c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }
}
