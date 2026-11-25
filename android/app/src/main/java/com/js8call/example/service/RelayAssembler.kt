package com.js8call.example.service

import com.js8call.example.util.CallsignValidator
import kotlin.math.abs
import kotlin.math.roundToInt

/** A relay addressed to us, heard in full: the sender, how it arrived, and its text. */
data class ReceivedRelay(
    val from: String,
    val snr: Int,
    val submode: Int,
    /** The text came in the relay frame itself, not in data frames after it. */
    val inlinePayload: Boolean,
    val text: String,
)

/** What a relay heard in full asks of us. */
sealed interface RelayAction {
    /** Pass it on: already "NEXT> text", with "*DE* SENDER" added. */
    data class Forward(val text: String) : RelayAction

    /** It ends here: answer it if it's a query we auto-reply to, otherwise acknowledge it along [path]. */
    data class Deliver(val payload: String, val path: String) : RelayAction
}

/**
 * Collects the relays addressed to us as they are heard: a "FROM: ME> …" frame starts one at its
 * audio offset, and the data frames that follow on the same offset (±[toleranceHz]) fill it in
 * until the last frame. Plain Kotlin with no Android classes, so it is unit-tested and fuzzed
 * (RelayAssemblerTest, tools/fuzz/kotlin/RelayAssemblerFuzz.kt): every frame comes from the air,
 * from anyone, and nothing here may throw.
 *
 * Memory is bounded whatever is heard: a relay quiet for [timeoutMs] is dropped, and one that
 * grows past [maxChars] is dropped too (that is hours of airtime at JS8 speeds, far beyond any
 * real relay, so it only stops a station that never sends a last frame from growing it forever).
 * There is at most one relay per offset, so the 300–2700 Hz passband bounds the count.
 */
class RelayAssembler(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val toleranceHz: Float = DEFAULT_TOLERANCE_HZ,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    private class Pending(
        val from: String,
        val snr: Int,
        val submode: Int,
        val inlinePayload: Boolean,
        var lastUpdated: Long,
        val text: StringBuilder = StringBuilder(),
    )

    private val pending = LinkedHashMap<Int, Pending>()

    /**
     * One decoded frame. Returns the relay it completes, if any. [myCall] is our callsign;
     * [submode] is the speed we would answer at; [type] carries the frame's JS8 flags
     * (data 0x4, last 0x2).
     */
    @Synchronized
    fun onFrame(text: String, snr: Int, freq: Float, type: Int, now: Long, myCall: String, submode: Int): ReceivedRelay? {
        if (!freq.isFinite()) return null
        expire(now)

        val directed = RelayProtocol.parseDirectedCommand(text)
        if (directed != null && directed.command == ">") {
            val target = directed.to.trim().uppercase()
            if (target.contains("@")) return null
            if (!CallsignValidator.matches(myCall, target)) return null
            // Our reply goes back to the sender, so it must be a callsign: free text shaped like
            // "A>B: ME> …" must not steer what we transmit.
            if (!CallsignValidator.isAmateurCallsign(directed.from)) return null

            val key = keyNear(freq) ?: freq.roundToInt()
            val relay = Pending(
                from = directed.from.trim().uppercase(),
                snr = snr,
                submode = submode,
                inlinePayload = directed.payload.isNotBlank(),
                lastUpdated = now,
            )
            pending[key] = relay
            if (directed.payload.isBlank()) return null

            val (payload, hasEom) = RelayProtocol.normalizePayload(directed.payload)
            if (!append(key, relay, payload)) return null
            return if (hasEom || isLastFrame(type)) finish(key, relay) else null
        }

        if (!isDataFrame(type)) return null
        val key = keyNear(freq) ?: return null
        val relay = pending[key] ?: return null
        relay.lastUpdated = now
        if (!append(key, relay, text)) return null
        return if (isLastFrame(type)) finish(key, relay) else null
    }

    /** Relays still being heard. */
    @Synchronized
    fun pendingCount(): Int = pending.size

    private fun append(key: Int, relay: Pending, part: String): Boolean {
        if (relay.text.length + part.length > maxChars) {
            pending.remove(key)
            return false
        }
        relay.text.append(part)
        return true
    }

    private fun finish(key: Int, relay: Pending): ReceivedRelay? {
        pending.remove(key)
        if (relay.text.isEmpty()) return null
        return ReceivedRelay(relay.from, relay.snr, relay.submode, relay.inlinePayload, relay.text.toString())
    }

    private fun expire(now: Long) {
        pending.values.removeAll { now - it.lastUpdated > timeoutMs }
    }

    private fun keyNear(freq: Float): Int? = pending.keys.firstOrNull { abs(freq - it) <= toleranceHz }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 90_000L
        const val DEFAULT_TOLERANCE_HZ = 10.0f
        /** About 400 JS8 frames: no real relay comes near it. */
        const val DEFAULT_MAX_CHARS = 4_000

        fun isDataFrame(type: Int): Boolean = (type and 0x4) != 0
        fun isLastFrame(type: Int): Boolean = (type and 0x2) != 0

        /**
         * What a relay heard in full asks of us ([myCall]), or null when it asks nothing: blank,
         * sent to a group, a failed checksum on text that came in data frames, an ACK, no way
         * back, or a forward that would loop (to us, or to a station it already passed through).
         * Text that came in the relay frame itself is taken even when its checksum doesn't
         * match, as JS8Call senders don't always add one there. Only callsigns go into a reply
         * path: anything else in "*DE* …" is left out.
         */
        fun decide(relay: ReceivedRelay, myCall: String): RelayAction? {
            val combined = RelayProtocol.stripEom(relay.text.trimEnd())
            if (combined.isBlank()) return null
            if (combined.trimStart().startsWith("@")) return null

            val (valid, message) = RelayProtocol.validateChecksum(combined)
            val payload = when {
                valid -> message
                relay.inlinePayload -> RelayProtocol.stripOptionalChecksum(combined)
                else -> return null
            }
            if (payload.isBlank()) return null

            val hops = RelayProtocol.pathCallsigns(relay.from, payload)
                .filter { CallsignValidator.isAmateurCallsign(it) }
                .distinct()

            RelayProtocol.forwardPayload(payload)?.let { onward ->
                val next = onward.substringBefore('>').trim()
                // A relay never goes back to us or to a station it already passed through:
                // "B> ME> B> ME> …" would otherwise bounce between relaying stations.
                if (CallsignValidator.matches(myCall, next)) return null
                if (hops.any { CallsignValidator.matches(it, next) }) return null
                return RelayAction.Forward(if (relay.from.isNotBlank()) "$onward *DE* ${relay.from}" else onward)
            }

            if (payload.trimStart().startsWith("ACK", ignoreCase = true)) return null

            val path = hops.filterNot { CallsignValidator.matches(myCall, it) }.joinToString(">")
            if (path.isBlank()) return null
            return RelayAction.Deliver(payload, path)
        }
    }
}
