package com.js8call.example.ui

import com.js8call.example.model.TransmitMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxQueueRulesTest {

    private fun auto(text: String) = TransmitMessage(text = text, priority = 1, timestamp = 0)
    private fun mine(text: String) = TransmitMessage(text = text, priority = 0, timestamp = 0)
    private val all: (TransmitMessage) -> Boolean = { true }

    /** Sends everything in [queue] one at a time, as the queue would, and returns the order. */
    private fun drain(queue: MutableList<TransmitMessage>, arriving: (Int) -> List<TransmitMessage> = { emptyList() }): List<String> {
        val sent = mutableListOf<String>()
        var ahead = 0
        var slot = 0
        while (queue.isNotEmpty() && slot < 50) {
            val pick = TxQueueRules.pickNext(queue.sortedByDescending { it.priority }, ahead, all)!!
            ahead = pick.autoAheadOfOperator
            queue.remove(pick.message)
            sent += pick.message.text
            queue += arriving(slot++)
        }
        return sent
    }

    @Test
    fun automaticRepliesGoFirstWhenNoOneIsWaiting() {
        assertEquals(listOf("ACK1", "ACK2", "ACK3"), drain(mutableListOf(auto("ACK1"), auto("ACK2"), auto("ACK3"))))
    }

    @Test
    fun oneAutomaticReplyMayGoAheadOfTheOperator() {
        assertEquals(listOf("ACK1", "MINE", "ACK2"), drain(mutableListOf(mine("MINE"), auto("ACK1"), auto("ACK2"))))
    }

    @Test
    fun aStreamOfQueriesCannotStarveTheOperator() {
        // A new automatic reply arrives every slot: the operator's messages still go out.
        var n = 0
        val sent = drain(mutableListOf(mine("MINE1"), mine("MINE2"), auto("Q0"))) { slot -> if (slot < 8) listOf(auto("Q${++n}")) else emptyList() }
        assertEquals(listOf("Q0", "MINE1", "Q1", "MINE2"), sent.take(4))
    }

    @Test
    fun onlyEligibleMessagesAreConsidered() {
        val held = mine("ON THE OTHER NET")
        val pick = TxQueueRules.pickNext(listOf(auto("ACK"), held), 1) { it !== held }
        assertEquals("ACK", pick!!.message.text)
        assertEquals(0, pick.autoAheadOfOperator)
        assertNull(TxQueueRules.pickNext(listOf(held), 0) { it !== held })
    }

    @Test
    fun theSafeguardAllowsFiveAutomaticRepliesInAnyFiveMinutes() {
        val b = AutoReplyBudget()
        repeat(5) { assertTrue(b.allows(it * 1_000L)); b.record(it * 1_000L) }
        assertFalse(b.allows(10_000))
        assertFalse(b.allows(299_999))
        // The first one leaves the window five minutes after it went.
        assertTrue(b.allows(300_000))
    }

    @Test
    fun aDuplicateAutomaticReplyAddsNothing() {
        val waiting = listOf(auto("W2XYZ: K1ABC SNR -10"), mine("HELLO"))
        assertTrue(TxQueueRules.isDuplicateAuto(waiting, auto("W2XYZ: K1ABC SNR -10")))
        assertFalse(TxQueueRules.isDuplicateAuto(waiting, auto("W2XYZ: K1ABC SNR -12")))
        // The operator's own sends are never dropped as duplicates.
        assertFalse(TxQueueRules.isDuplicateAuto(waiting, mine("HELLO")))
    }
}
