package com.js8call.example.ui

import com.js8call.example.model.TransmitMessage

/**
 * Which queued message goes out next. Automatic replies (priority > 0: heartbeat ACKs, query
 * answers, MSG ACKs, relays) normally go first, as they are short and time-sensitive. But other
 * stations decide when those arrive, so a busy frequency (or one station asking every slot) could
 * keep the operator's own message waiting indefinitely. So once one automatic reply has gone
 * ahead of a waiting operator message, the operator's message goes next.
 *
 * Plain Kotlin, unit-tested (TxQueueRulesTest).
 */
object TxQueueRules {
    /** An automatic reply already waiting with the same text and addressee: the new one adds nothing. */
    fun isDuplicateAuto(queue: List<TransmitMessage>, m: TransmitMessage): Boolean =
        m.priority > 0 && queue.any { it.priority > 0 && it.text == m.text && it.directed == m.directed }

    /** Automatic replies allowed to go ahead of a waiting operator message, in a row. */
    const val AUTO_AHEAD_OF_OPERATOR = 1

    data class Pick(val message: TransmitMessage, val autoAheadOfOperator: Int)

    /**
     * The next message from [queue] (already in priority order) among those [eligible] now, and
     * the new count of automatic replies sent in a row while an operator message waited, given
     * the count so far ([autoAheadOfOperator]). Null when nothing is eligible.
     */
    fun pickNext(queue: List<TransmitMessage>, autoAheadOfOperator: Int, eligible: (TransmitMessage) -> Boolean): Pick? {
        val candidates = queue.filter(eligible)
        val first = candidates.firstOrNull() ?: return null
        val operatorWaiting = candidates.firstOrNull { it.priority <= 0 }
        return when {
            operatorWaiting == null -> Pick(first, 0)
            first.priority <= 0 -> Pick(first, 0)
            autoAheadOfOperator >= AUTO_AHEAD_OF_OPERATOR -> Pick(operatorWaiting, 0)
            else -> Pick(first, autoAheadOfOperator + 1)
        }
    }
}

/**
 * The auto-reply safeguard (Settings, on by default): at most [max] automatic replies in any
 * [windowMs]. Other stations decide when automatic replies are due, so without a ceiling a busy
 * band, or one station asking from many callsigns, could keep the radio transmitting. Standard
 * JS8Call has no overall limit (only one reply per station per 15 minutes to @ALLCALL queries).
 * The operator's own sends never count and are never limited.
 */
class AutoReplyBudget(private val max: Int = DEFAULT_MAX, private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private val sent = ArrayDeque<Long>()

    /** Whether another automatic reply may go now. */
    fun allows(now: Long): Boolean {
        while (sent.isNotEmpty() && (now - sent.first() >= windowMs || sent.first() > now)) sent.removeFirst()
        return sent.size < max
    }

    /** An automatic reply went out at [now]. */
    fun record(now: Long) { sent.addLast(now) }

    companion object {
        const val DEFAULT_MAX = 5
        const val DEFAULT_WINDOW_MS = 5 * 60_000L
    }
}
