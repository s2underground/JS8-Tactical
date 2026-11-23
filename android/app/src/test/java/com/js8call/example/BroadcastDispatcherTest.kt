package com.js8call.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastDispatcherTest {

    private class Receiver(val name: String)

    private val pending = ArrayDeque<Runnable>()
    private val delivered = mutableListOf<String>()

    /** Filters are sets of accepted actions; messages are actions. */
    private val dispatcher = BroadcastDispatcher<Receiver, Set<String>, String>(
        post = { pending.addLast(it) },
        matches = { filter, action -> action in filter },
        deliver = { r, action -> delivered += "${r.name}:$action" },
    )

    private fun runPending() {
        while (pending.isNotEmpty()) pending.removeFirst().run()
    }

    @Test
    fun deliversOnlyToMatchingReceiversAndLater() {
        val a = Receiver("a")
        val b = Receiver("b")
        dispatcher.register(a, setOf("DECODE", "TX_SENT"))
        dispatcher.register(b, setOf("SPECTRUM"))

        assertTrue(dispatcher.send("DECODE"))
        assertTrue(delivered.isEmpty()) // not delivered until the posted task runs
        runPending()
        assertEquals(listOf("a:DECODE"), delivered)
    }

    @Test
    fun noMatchReturnsFalseAndPostsNothing() {
        dispatcher.register(Receiver("a"), setOf("DECODE"))
        assertFalse(dispatcher.send("WARNING"))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun keepsSendOrder() {
        val a = Receiver("a")
        dispatcher.register(a, setOf("ONE", "TWO", "THREE"))
        dispatcher.send("ONE")
        dispatcher.send("TWO")
        dispatcher.send("THREE")
        runPending()
        assertEquals(listOf("a:ONE", "a:TWO", "a:THREE"), delivered)
    }

    @Test
    fun receiverUnregisteredBeforeDeliveryIsSkipped() {
        val a = Receiver("a")
        val b = Receiver("b")
        dispatcher.register(a, setOf("DECODE"))
        dispatcher.register(b, setOf("DECODE"))
        dispatcher.send("DECODE")
        dispatcher.unregister(a)
        runPending()
        assertEquals(listOf("b:DECODE"), delivered)
    }

    @Test
    fun unregisterRemovesEveryRegistrationOfThatReceiver() {
        val a = Receiver("a")
        dispatcher.register(a, setOf("DECODE"))
        dispatcher.register(a, setOf("SPECTRUM"))
        dispatcher.unregister(a)
        assertFalse(dispatcher.send("DECODE"))
        assertFalse(dispatcher.send("SPECTRUM"))
    }

    @Test
    fun receiverRegisteredTwiceGetsBothMatches() {
        // Same as LocalBroadcastManager: each registration is its own entry.
        val a = Receiver("a")
        dispatcher.register(a, setOf("DECODE"))
        dispatcher.register(a, setOf("DECODE", "SPECTRUM"))
        dispatcher.send("DECODE")
        runPending()
        assertEquals(listOf("a:DECODE", "a:DECODE"), delivered)
    }
}
