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
