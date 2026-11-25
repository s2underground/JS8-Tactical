package com.js8call.example.service

import com.js8call.example.util.CallsignValidator
import java.util.Locale

/**
 * What the station sends back on its own when auto-reply is on: the heartbeat ACK and the
 * answers to SNR?, INFO?, STATUS?, HEARING?, GRID? and AGN?, sent direct or back along a relay
 * path. Plain Kotlin with no Android classes, so the decisions can be tested: the engine
 * service only checks that auto-reply is on and the radio idle, then queues what this returns.
 *
 * Everything here reacts to text from anyone on the air, so it never replies to itself, to a
 * group, or to a frame that isn't addressed to this station, and it sends nothing it has no
 * value for (no INFO set → no INFO reply). The sender's name goes into what we transmit, so it
 * must be a callsign: free text shaped like "ANY WORDS: @HB HEARTBEAT" can't put words on the
 * air under our call.
 */
object AutoReply {

    /** What the station can say about itself. Blank values mean "don't answer that query". */
    data class Station(
        val callsign: String,
        val info: String = "",
        val status: String = "",
        val grid: String = "",
        /** The last message the operator sent (not an automatic reply), for AGN?. */
        val lastTx: String = "",
    )

    /** Stations heard recently, leaving out [exclude] (upper-case callsigns). */
    fun interface Heard {
        fun recent(exclude: Set<String>): List<String>
    }

    /**
     * SNR as JS8 writes it: "+05", "-12". Empty outside ±60 dB (not a real reading).
     * Locale.ROOT: it goes on the air, and some locales write digits in another script.
     */
    fun formatSnr(snr: Int): String {
        if (snr < -60 || snr > 60) return ""
        val sign = if (snr >= 0) "+" else ""
        val width = if (snr < 0) 3 else 2
        return String.format(Locale.ROOT, "%s%0${width}d", sign, snr)
    }

    /**
     * The ACK for someone else's heartbeat ("ME: THEM HEARTBEAT SNR -12"), with [extra]
     * appended (desktop JS8Call adds " MSG ID n" when holding a message for them). Null when
     * [text] isn't a heartbeat, is our own, or the SNR isn't a real reading.
     */
    fun heartbeatAck(text: String, snr: Int, me: Station, extra: (String) -> String = { "" }): String? {
        val from = RelayProtocol.heartbeatSender(text) ?: return null
        if (!CallsignValidator.isAmateurCallsign(from)) return null
        if (CallsignValidator.matches(me.callsign, from)) return null
        val snrText = formatSnr(snr)
        if (snrText.isEmpty()) return null
        val target = from.trim().uppercase()
        return "${me.callsign}: $target HEARTBEAT SNR $snrText${extra(target)}"
    }

    /** Whether a directed frame is one this station should answer: to us, by callsign, from another callsign. */
    fun isForMe(me: String, d: DirectedCommand): Boolean =
        !d.to.startsWith("@") &&
            CallsignValidator.matches(me, d.to) &&
            CallsignValidator.isAmateurCallsign(d.from) &&
            !CallsignValidator.matches(me, d.from)

    /** The direct answer to a query addressed to this station, or null when there is none to send. */
    fun directedReply(d: DirectedCommand, snr: Int, me: Station, heard: Heard): String? {
        if (!isForMe(me.callsign, d)) return null
        val asker = d.from.trim().uppercase()
        val prefix = "${me.callsign}: $asker"
        return answer(d.command, snr, me, prefix, heard, exclude = setOf(asker, me.callsign.uppercase()))
    }

    /**
     * The answer to a query that reached us through relays, addressed back along [relayPath]
     * ("RELAY>ORIGIN"). Null when there is none to send.
     */
    fun relayedReply(payload: String, relayPath: String, snr: Int, me: Station, heard: Heard): String? {
        if (relayPath.isBlank()) return null
        val command = payload.trimStart().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
        if (command.isEmpty()) return null
        // Leave every station on the path out of HEARING: the asker knows them already.
        val hops = relayPath.split('>').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        return answer(command, snr, me, relayPath, heard, exclude = hops + me.callsign.uppercase())
    }

    private fun answer(command: String, snr: Int, me: Station, prefix: String, heard: Heard, exclude: Set<String>): String? =
        when (command.uppercase()) {
            "SNR?", "?" -> formatSnr(snr).takeIf { it.isNotEmpty() }?.let { "$prefix SNR $it" }
            "INFO?" -> me.info.trim().takeIf { it.isNotEmpty() }?.let { "$prefix INFO $it" }
            "STATUS?" -> me.status.trim().takeIf { it.isNotEmpty() }?.let { "$prefix STATUS $it" }
            "GRID?" -> me.grid.trim().uppercase().takeIf { it.isNotEmpty() }?.let { "$prefix GRID $it" }
            "HEARING?" -> heard.recent(exclude).takeIf { it.isNotEmpty() }?.let { "$prefix HEARING ${it.joinToString(" ")}" }
            "AGN?" -> me.lastTx.trimEnd().takeIf { it.isNotBlank() }
            else -> null
        }
}

/**
 * Lets an automatic replay (AGN?) go to each station once per [windowMs]. A one-frame "AGN?" can
 * make us send a long message again, so without this one station could keep us transmitting by
 * asking over and over. A repeat ask inside the window is left to the operator, who sees it in
 * Traffic and can send again by hand.
 */
class ReplayGuard(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private val last = HashMap<String, Long>()

    /** Whether to replay automatically for [requester] at [now]; records the replay if so. */
    @Synchronized
    fun allow(requester: String, now: Long): Boolean {
        val key = requester.trim().uppercase().substringBefore('/')
        if (key.isEmpty()) return false
        last.values.removeAll { now - it >= windowMs || now < it }
        if (key in last) return false
        last[key] = now
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 10 * 60_000L
    }
}
