package com.js8call.example.ui.traffic

import java.util.Locale

/**
 * Rules for the Outbox (what this station sent), kept free of Android classes so they can be
 * tested: which sends are heartbeats (left out, they would bury real traffic), and the text to
 * send again.
 */
object OutboxText {
    /** The note added to a send stopped part-way ("[STOPPED · FRAMES SENT: 2 OF 5]"). */
    private val STOPPED_NOTE = Regex("""\s*\[STOPPED\b[^\]]*]\s*$""", RegexOption.IGNORE_CASE)
    private val HEARTBEAT_WORD = Regex("""(^|\s)HEARTBEAT(\s|$)""")
    private val OWN_LEAD = Regex("""^[A-Z0-9/]+:\s*""")

    /** A heartbeat or a heartbeat acknowledgement: "HB EM96", "@HB HEARTBEAT EM96", "K1ABC HEARTBEAT SNR -10". */
    fun isHeartbeat(raw: String): Boolean {
        val t = raw.trim().uppercase(Locale.ROOT).replaceFirst(OWN_LEAD, "")
        val first = t.substringBefore(' ')
        return first == "HB" || first == "@HB" || HEARTBEAT_WORD.containsMatchIn(t)
    }

    /** Whether this send was stopped before all of it went out. */
    fun wasStopped(raw: String): Boolean = STOPPED_NOTE.containsMatchIn(raw)

    /** The text to queue again: what was sent, without the stopped note. */
    fun resendText(raw: String): String = raw.replace(STOPPED_NOTE, "").trim()
}
