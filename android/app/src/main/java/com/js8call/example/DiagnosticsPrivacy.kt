package com.js8call.example

/**
 * Takes personal details out of a diagnostic report before it leaves the phone.
 *
 * A callsign is personal data (licence databases map it to a name and address), and the app log
 * is full of them: the operator's own in every transmission, everyone else's in everything heard.
 * Each callsign becomes a label that stays the same throughout the report (the operator's own is
 * MY-STATION, others STATION-1, STATION-2, …), so traffic still reads correctly for debugging.
 * Grid squares (4, 6 or 8 characters), MGRS references, latitude/longitude pairs, Bluetooth
 * addresses, IP addresses and email addresses are masked too.
 */
object DiagnosticsPrivacy {

    const val MY_LABEL = "MY-STATION"

    /**
     * Callsign-shaped words (prefix, digit, suffix; optional /P style suffix). Upper case only.
     * A "/" before the call doesn't protect it: in VE3/K1ABC the K1ABC part is still masked.
     */
    private val CALL = Regex("(?<![A-Za-z0-9])((?:[A-Z]{1,2}|[0-9][A-Z]|[A-Z][0-9])[0-9][A-Z]{1,4})(?:/[A-Z0-9]{1,4})?(?![A-Za-z0-9])")
    /** Upper-case words of that shape that aren't callsigns. */
    private val NOT_CALLS = setOf("JS8CALL", "JS8CORE", "JS8LIB", "UTF8", "MP3", "H264")
    /** Maidenhead locators: 4, 6 or 8 characters (EM96, EM96ab, EM96ab12; GPS fills in all 8). */
    private val GRID = Regex("(?<![A-Za-z0-9])[A-Ra-r]{2}[0-9]{2}(?:[A-Xa-x]{2}(?:[0-9]{2})?)?(?![A-Za-z0-9])")
    /**
     * MGRS references as the report forms write them: zone and band, 100 km square, then an
     * easting and northing of 1 to 5 digits each, with or without spaces (17S MV 12345 67890,
     * 17SMV1234567890, 4QFJ1234 5678). Bands C–X and square letters skip I and O.
     */
    private val MGRS = Regex(
        "(?<![A-Za-z0-9])[0-9]{1,2}[C-HJ-NP-Xc-hj-np-x] ?[A-HJ-NP-Za-hj-np-z]{2} ?[0-9]{1,10}(?: [0-9]{1,5})?(?![A-Za-z0-9])"
    )
    /** Decimal latitude/longitude pairs ("35.1234, -84.5678"; 3 or more decimals each). */
    private val LAT_LON = Regex("(?<![0-9.])-?[0-9]{1,2}\\.[0-9]{3,}\\s*(?:,|;|/| )\\s*-?[0-9]{1,3}\\.[0-9]{3,}(?![0-9.])")
    /** A labelled coordinate ("lat=35.1234", "longitude: -84.56"). */
    private val COORD = Regex("(\\b(?:lat|lon|lng|latitude|longitude)\\b\\s*(?:=|:)?\\s*)-?[0-9]{1,3}\\.[0-9]+", RegexOption.IGNORE_CASE)
    // No "[:" anywhere in these patterns: Android's regex engine (ICU) reads "[:" as the start of a
    // POSIX class like [:alpha:], so "[:-]" made this object fail to load on phones (but not in JVM
    // tests) and took the whole diagnostics report down. RegexPortabilityTest guards it.
    private val MAC = Regex("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}(?::|-)){5}([0-9A-Fa-f]{2})(?![0-9A-Fa-f:])")
    private val MAC_PLAIN = Regex("(?<![0-9A-Fa-f])[0-9A-F]{12}(?![0-9A-Fa-f])")
    private val IPV4 = Regex("(?<![0-9A-Za-z.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9.])")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** The report with personal details replaced. [myCall] and [myGrid] are the operator's own. */
    fun scrub(report: String, myCall: String, myGrid: String): String {
        val labels = LinkedHashMap<String, String>()
        val me = base(myCall)
        if (me.isNotEmpty()) labels[me] = MY_LABEL
        fun label(call: String): String {
            val b = base(call)
            return labels.getOrPut(b) { "STATION-${labels.values.count { it != MY_LABEL } + 1}" }
        }
        var t = report
        // Emails first (they contain @ and dots the other patterns would split), keeping the app's own address.
        t = EMAIL.replace(t) { m -> if (m.value.equals("js8tactical@proton.me", true)) m.value else "[EMAIL]" }
        t = MAC.replace(t) { m -> "XX:XX:XX:XX:XX:${m.groupValues[1].uppercase()}" }
        t = MAC_PLAIN.replace(t) { m -> "XXXXXXXXXX" + m.value.takeLast(2) }
        t = IPV4.replace(t) { m -> if (m.value == "127.0.0.1" || m.value == "0.0.0.0") m.value else "[IP]" }
        // Positions before grids and calls: an MGRS reference holds letter pairs a later pattern would split.
        t = COORD.replace(t) { m -> m.groupValues[1] + "[COORD]" }
        t = LAT_LON.replace(t, "[LATLON]")
        t = MGRS.replace(t, "[MGRS]")
        // The operator's own grid, even written in lower case or as part of a 6- or 8-character locator.
        if (myGrid.length >= 4) {
            t = Regex("(?<![A-Za-z0-9])" + Regex.escape(myGrid.take(4)) + "(?:[A-Xa-x]{2}(?:[0-9]{2})?)?(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
                .replace(t, "[GRID]")
        }
        t = GRID.replace(t, "[GRID]")
        // The operator's own call in any case or with a suffix, then every other call-shaped word.
        if (me.isNotEmpty()) {
            t = Regex("(?<![A-Za-z0-9])(?:[A-Za-z0-9]{1,4}/)?" + Regex.escape(me) + "(?:/[A-Za-z0-9]{1,4})?(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
                .replace(t, MY_LABEL)
        }
        t = CALL.replace(t) { m -> if (m.groupValues[1] in NOT_CALLS) m.value else label(m.groupValues[1]) }
        return t
    }

    private fun base(call: String): String =
        call.trim().uppercase().split('/').maxByOrNull { p -> if (p.any { it.isDigit() } && p.any { it.isLetter() }) p.length + 10 else p.length }.orEmpty()
}
