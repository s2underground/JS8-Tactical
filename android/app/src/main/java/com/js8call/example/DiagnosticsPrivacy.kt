package com.js8call.example

/**
 * Takes personal details out of a diagnostic report before it leaves the phone.
 *
 * A callsign is personal data (licence databases map it to a name and address), and the app log
 * is full of them: the operator's own in every transmission, everyone else's in everything heard.
 * Each callsign becomes a label that stays the same throughout the report (the operator's own is
 * MY-STATION, others STATION-1, STATION-2, …), so traffic still reads correctly for debugging.
 * Grid squares are cut to their first 4 characters, the precision JS8 sends over the air (EM96ab12
 * becomes EM96****), so propagation and map problems can still be followed. MGRS references,
 * latitude/longitude pairs, Bluetooth addresses, IP addresses and email addresses are masked.
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
    /** 6- or 8-character Maidenhead locators (EM96ab, EM96ab12; GPS fills in all 8). Group 1 is the 4 kept. */
    private val FINE_GRID = Regex("(?<![A-Za-z0-9])([A-Ra-r]{2}[0-9]{2})([A-Xa-x]{2}(?:[0-9]{2})?)(?![A-Za-z0-9])")
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

    /**
     * Message text the app logs (what was sent and heard) becomes its length only: free text can
     * hold names, places and report details no pattern can recognise (second audit, N8). The
     * log lines stay, so timing and flow can still be followed.
     */
    fun redactMessageText(report: String): String {
        var t = QUOTED_TEXT.replace(report) { m -> "${m.groupValues[1]}='[${m.groupValues[2].length} chars]'" }
        t = DECODED_LINE.replace(t) { m -> "${m.groupValues[1]}[${m.groupValues[2].length} chars]${m.groupValues[3]}" }
        return t
    }

    /** text='…', raw='…', payloadText='…' and similar in the app's and the engine's log lines. */
    private val QUOTED_TEXT = Regex("\\b(text|raw|payload|reply|ack|message|body)='([^'\\n]*)'", RegexOption.IGNORE_CASE)
    /** "Decoded: … (SNR: -12 dB)" and "Sending Heartbeat: … at 1500Hz" style lines. */
    private val DECODED_LINE = Regex("(Decoded: |Auto HB ACK: )([^\\n]*?)( \\(SNR: [^\\n]*\\)|$)", RegexOption.MULTILINE)

    /** The report with personal details replaced. [myCall] is the operator's own callsign. */
    fun scrub(report: String, myCall: String): String {
        val labels = LinkedHashMap<String, String>()
        val me = base(myCall)
        if (me.isNotEmpty()) labels[me] = MY_LABEL
        fun label(call: String): String {
            val b = base(call)
            return labels.getOrPut(b) { "STATION-${labels.values.count { it != MY_LABEL } + 1}" }
        }
        var t = redactMessageText(report)
        // Emails first (they contain @ and dots the other patterns would split), keeping the app's own address.
        t = EMAIL.replace(t) { m -> if (m.value.equals("js8tactical@proton.me", true)) m.value else "[EMAIL]" }
        t = MAC.replace(t) { m -> "XX:XX:XX:XX:XX:${m.groupValues[1].uppercase()}" }
        t = MAC_PLAIN.replace(t) { m -> "XXXXXXXXXX" + m.value.takeLast(2) }
        t = IPV4.replace(t) { m -> if (m.value == "127.0.0.1" || m.value == "0.0.0.0") m.value else "[IP]" }
        // Positions before grids and calls: an MGRS reference holds letter pairs a later pattern would split.
        t = COORD.replace(t) { m -> m.groupValues[1] + "[COORD]" }
        t = LAT_LON.replace(t, "[LATLON]")
        t = MGRS.replace(t, "[MGRS]")
        // Grids keep their 4-character square, which is all JS8 ever transmits; the finer characters
        // (down to a few hundred metres when GPS fills them in) become stars, so the length still shows.
        t = FINE_GRID.replace(t) { m -> m.groupValues[1] + "*".repeat(m.groupValues[2].length) }
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
