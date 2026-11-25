package com.js8call.example.service

import com.js8call.example.util.CallsignValidator
import com.js8call.example.util.Js8Commands
import kotlin.math.abs
import kotlin.math.roundToInt

/** A stored-message command (MSG, MSG TO:, QUERY) heard in full. */
data class ReceivedMsg(
    /** MSG, MSG TO: or QUERY. */
    val command: String,
    val from: String,
    val to: String,
    /** The text without its checksum or end marker; a gap reads "[… n frames missing …]". */
    val text: String,
    val snr: Int,
    val freq: Float,
    val submode: Int,
    /** The checksum the sender added matches the text: nothing lost or garbled. */
    val intact: Boolean,
    /** Frame slots that went by with nothing heard while it came in. */
    val gaps: Int,
)

/**
 * Collects stored-message commands addressed to us or our groups as they are heard: a
 * "FROM: TO MSG" frame starts one at its audio offset, and the data frames that follow on that
 * offset (±[toleranceHz]) fill it in until the last frame. Plain Kotlin with no Android classes,
 * so it is unit-tested and fuzzed (MsgAssemblerTest, tools/fuzz/kotlin/MsgAssemblerFuzz.kt):
 * every frame comes from the air, from anyone, and nothing here may throw.
 *
 * Integrity follows JS8Call: these commands carry a 16-bit checksum of their text (CRC-16/KERMIT,
 * 3 characters), and [ReceivedMsg.intact] says whether it matched. JS8Call discards a message
 * whose checksum fails; the app keeps it visible, marked, and leaves the decision to act on it
 * (an automatic ACK, a store-and-forward reply) to the caller.
 *
 * Memory is bounded whatever is heard: one message per offset, dropped after four frame periods
 * without a frame, or when it passes [maxChars] (hours of airtime, far beyond a real message).
 */
class MsgAssembler(
    private val toleranceHz: Float = DEFAULT_TOLERANCE_HZ,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    private class Pending(
        val command: String,
        val from: String,
        val to: String,
        val snr: Int,
        val freq: Float,
        val submode: Int,
        val periodMs: Long,
        var lastUpdated: Long,
        var gaps: Int = 0,
        val text: StringBuilder = StringBuilder(),
    ) {
        val timeoutMs: Long get() = 4 * periodMs
    }

    private val pending = LinkedHashMap<Int, Pending>()

    /**
     * One decoded frame. Returns the message it completes, if any. [periodMs] is the frame
     * period of the speed it was heard at; [accepts] says whether a command to that target is
     * ours to take (our call, a group we follow).
     */
    @Synchronized
    fun onFrame(
        text: String, snr: Int, freq: Float, type: Int, now: Long, submode: Int, periodMs: Long,
        accepts: (command: String, to: String) -> Boolean,
    ): ReceivedMsg? {
        if (!freq.isFinite() || periodMs <= 0) return null
        expire(now)

        val directed = RelayProtocol.parseDirectedCommand(text)
        val command = directed?.command?.uppercase()
        if (directed != null && command in COMMANDS) {
            val to = directed.to.trim().uppercase()
            if (!accepts(command!!, to)) return null
            // An ACK and any reply go back to the sender, so it must be a callsign.
            if (!CallsignValidator.isAmateurCallsign(directed.from)) return null
            val msg = Pending(
                command = command,
                from = directed.from.trim().uppercase(),
                to = to,
                snr = snr,
                freq = freq,
                submode = submode,
                periodMs = periodMs,
                lastUpdated = now,
            )
            val key = keyNear(freq) ?: freq.roundToInt()
            pending[key] = msg
            if (directed.payload.isNotBlank() && !append(key, msg, directed.payload)) return null
            return if (RelayAssembler.isLastFrame(type)) finish(key, msg) else null
        }

        if (!RelayAssembler.isDataFrame(type)) return null
        val key = keyNear(freq) ?: return null
        val msg = pending[key] ?: return null
        // A frame slot went by with nothing heard (often our own transmission over it): mark the
        // gap, so the Inbox doesn't show a message with words missing as if it were whole.
        val silent = now - msg.lastUpdated
        if (silent > msg.periodMs * 8 / 5) {
            val missed = ((silent + msg.periodMs / 2) / msg.periodMs - 1).coerceAtLeast(1)
            msg.gaps += missed.toInt()
            if (!append(key, msg, " [… $missed frame${if (missed == 1L) "" else "s"} missing …] ")) return null
        }
        msg.lastUpdated = now
        if (!append(key, msg, text)) return null
        return if (RelayAssembler.isLastFrame(type)) finish(key, msg) else null
    }

    /** Messages still being heard. */
    @Synchronized
    fun pendingCount(): Int = pending.size

    private fun append(key: Int, msg: Pending, part: String): Boolean {
        if (msg.text.length + part.length > maxChars) {
            pending.remove(key)
            return false
        }
        msg.text.append(part)
        return true
    }

    private fun finish(key: Int, msg: Pending): ReceivedMsg? {
        pending.remove(key)
        val (text, intact) = clean(msg.text.toString(), checksummed = !(msg.to == "@APRSIS" && msg.command != Js8Commands.CMD_QUERY))
        if (text.isBlank()) return null
        return ReceivedMsg(msg.command, msg.from, msg.to, text, msg.snr, msg.freq, msg.submode, intact && msg.gaps == 0, msg.gaps)
    }

    private fun expire(now: Long) {
        pending.values.removeAll { now - it.lastUpdated > it.timeoutMs }
    }

    /** The pending message nearest [freq], within the tolerance. */
    private fun keyNear(freq: Float): Int? =
        pending.keys.filter { abs(freq - it) <= toleranceHz }.minByOrNull { abs(freq - it) }

    companion object {
        const val DEFAULT_TOLERANCE_HZ = 10.0f
        const val DEFAULT_MAX_CHARS = 4_000

        val COMMANDS = setOf(Js8Commands.CMD_MSG, Js8Commands.CMD_MSG_TO, Js8Commands.CMD_QUERY)

        private val endMarkers = Regex("\\s*[♢▪■]+\\s*$")

        /**
         * The text of a stored-message command without its end marker and checksum, and whether
         * the checksum matched. As JS8Call does, the last three characters are the checksum when
         * one is expected ([checksummed]; JS8Call leaves it off a MSG to @APRSIS).
         */
        fun clean(raw: String, checksummed: Boolean = true): Pair<String, Boolean> {
            val text = raw.replace(endMarkers, "").trim()
            if (!checksummed) return text to true
            val (valid, body) = RelayProtocol.validateChecksum(text)
            if (valid) return body.trim() to true
            // Failed: drop a trailing token that has a checksum's shape, as it almost surely is one.
            return RelayProtocol.stripOptionalChecksum(text).trim() to false
        }
    }
}
