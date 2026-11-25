package com.js8call.example.storeforward

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldTest {
    private val now = 1_000_000_000L
    private fun relay(id: Int, to: String, from: String = "W1AW", text: String = "HELLO", at: Long = now - 1000, delivered: Set<String> = emptySet()) =
        Held(id, to, from, text, at, Held.Kind.RELAY, deliveredTo = delivered)
    private fun posted(id: Int, to: String, expires: Long? = null, delivered: Set<String> = emptySet()) =
        Held(id, to, "KQ4BR", "SITREP ALL QUIET", now - 1000, Held.Kind.POSTED, expiresMs = expires, deliveredTo = delivered)

    @Test fun baseCallsign() {
        assertEquals("K9DGY", Held.base("K9DGY/P"))
        assertEquals("K9DGY", Held.base("VE3/K9DGY"))
        assertEquals("@GHOSTNET", Held.base("@ghostnet"))
    }

    @Test fun queryMsgsDirectedAnswersYesOrNo() {
        val all = listOf(relay(5, "K9DGY"), relay(6, "W9ZOM"))
        assertEquals("K9DGY YES MSG ID 5", HeldLogic.answerQueryMsgs(all, "K9DGY", "KQ4BR", toMe = true, now = now))
        assertEquals("KD8SKZ NO", HeldLogic.answerQueryMsgs(all, "KD8SKZ", "KQ4BR", toMe = true, now = now))
        // To @ALLCALL with nothing: stay quiet (desktop only answers NO when asked directly).
        assertNull(HeldLogic.answerQueryMsgs(all, "KD8SKZ", "@ALLCALL", toMe = false, now = now))
        assertEquals("K9DGY/P YES MSG ID 5", HeldLogic.answerQueryMsgs(all, "K9DGY/P", "@ALLCALL", toMe = false, now = now))
    }

    @Test fun deliveredIndividualMessagesAreNotOfferedAgainButCanBeRefetched() {
        val all = listOf(relay(5, "K9DGY", delivered = setOf("K9DGY")))
        assertEquals("K9DGY NO", HeldLogic.answerQueryMsgs(all, "K9DGY", "KQ4BR", toMe = true, now = now))
        assertEquals("K9DGY MSG HELLO FROM W1AW", HeldLogic.answerQueryMsg(all, 5, "K9DGY", now)?.first)
        // Someone else can't fetch it.
        assertNull(HeldLogic.answerQueryMsg(all, 5, "W9ZOM", now))
    }

    @Test fun groupPostsGoToAnyoneOnceEach() {
        val all = listOf(posted(7, "@GHOSTNET", delivered = setOf("W9ZOM")))
        assertEquals("K9DGY YES MSG ID 7", HeldLogic.answerQueryMsgs(all, "K9DGY", "@GHOSTNET", toMe = false, now = now))
        assertNull(HeldLogic.answerQueryMsgs(all, "W9ZOM", "@GHOSTNET", toMe = false, now = now))
        // Asking this station directly also finds group posts.
        assertEquals("K9DGY YES MSG ID 7", HeldLogic.answerQueryMsgs(all, "K9DGY", "KQ4BR", toMe = true, now = now))
        assertEquals("K9DGY MSG SITREP ALL QUIET FROM KQ4BR", HeldLogic.answerQueryMsg(all, 7, "K9DGY", now)?.first)
    }

    @Test fun expiryAndTheDesktopGroupWindow() {
        val all = listOf(posted(1, "@GHOSTNET", expires = now - 1), relay(2, "@GHOSTNET", at = now - Held.GROUP_FLOOR_MS - 1), relay(3, "@GHOSTNET"))
        assertEquals("K9DGY YES MSG ID 3", HeldLogic.answerQueryMsgs(all, "K9DGY", "@GHOSTNET", toMe = false, now = now))
        assertNull(HeldLogic.answerQueryMsg(all, 1, "K9DGY", now))
    }

    @Test fun nextMsgIdLooksAhead() {
        val all = listOf(relay(5, "K9DGY", text = "ONE"), relay(9, "K9DGY", text = "TWO"))
        assertEquals("K9DGY MSG ONE FROM W1AW NEXT MSG ID 9", HeldLogic.answerQueryMsg(all, 5, "K9DGY", now)?.first)
        assertEquals("K9DGY MSG TWO FROM W1AW", HeldLogic.answerQueryMsg(all, 9, "K9DGY", now)?.first)
    }

    @Test fun heartbeatAckMentionsWaitingMessage() {
        val all = listOf(relay(5, "K9DGY"), posted(7, "@GHOSTNET"))
        assertEquals(" MSG ID 5", HeldLogic.heartbeatExtra(all, "K9DGY", "@HB", now))
        // Group posts aren't pushed on every @HB heartbeat (as desktop).
        assertEquals("", HeldLogic.heartbeatExtra(all, "W9ZOM", "@HB", now))
    }

    @Test fun parsing() {
        assertEquals(SfParse.Offer("K9DGY", 5), SfParse.offer("K9DGY: KQ4BR YES MSG ID 5", "KQ4BR"))
        assertEquals(SfParse.Offer("K9DGY", 12), SfParse.offer("K9DGY: KQ4BR HEARTBEAT SNR -10 MSG ID 12", "KQ4BR"))
        assertNull(SfParse.offer("K9DGY: W9ZOM YES MSG ID 5", "KQ4BR"))
        assertEquals(5, SfParse.offerTail("MSG ID 5 "))
        assertEquals("K9DGY" to "MEET AT 1800", SfParse.msgTo("K9DGY MEET AT 1800"))
        assertNull(SfParse.msgTo("K9DGY"))
        assertEquals(42, SfParse.queryMsgId("MSG 42"))
        val d = SfParse.delivered("MEET AT 1800 FROM W1AW NEXT MSG ID 9")
        assertEquals("MEET AT 1800", d.text); assertEquals("W1AW", d.author); assertEquals(9, d.next)
        assertNull(SfParse.delivered("GREETINGS FROM HOME").author)
    }

    @Test fun deliveredFlag() {
        assertTrue(relay(1, "K9DGY", delivered = setOf("K9DGY/P")).delivered)
        assertFalse(relay(1, "@GHOSTNET", delivered = setOf("K9DGY")).delivered)
    }

    // --- Keeping the held list bounded (audit L9)

    private fun relay(id: Int, to: String, receivedMs: Long, delivered: Boolean = false, lastSentMs: Long? = null) =
        Held(id = id, to = to, from = "W1ABC", text = "T$id", receivedMs = receivedMs, kind = Held.Kind.RELAY,
            deliveredTo = if (delivered) setOf(to) else emptySet(), lastSentMs = lastSentMs)

    @Test
    fun relayedMessagesForOneStationAreNotKeptForever() {
        val day = 24 * 3600_000L
        val now = 100 * day
        assertTrue(Held.kept(relay(1, "K2XYZ", now - 29 * day), now))
        assertFalse(Held.kept(relay(2, "K2XYZ", now - 31 * day), now))
        assertTrue(Held.kept(relay(3, "K2XYZ", now - 20 * day, delivered = true, lastSentMs = now - 6 * day), now))
        assertFalse(Held.kept(relay(4, "K2XYZ", now - 20 * day, delivered = true, lastSentMs = now - 8 * day), now))
        // Group messages keep their own window; the operator's posts aren't touched.
        assertFalse(Held.kept(relay(5, "@RACES", now - 4 * day), now))
        val post = Held(id = 6, to = "@RACES", from = "KN4CRD", text = "P", receivedMs = 0, kind = Held.Kind.POSTED)
        assertTrue(Held.kept(post, now))
    }

    @Test
    fun theOldestRelayedHoldsGiveWayPastTheCeiling() {
        val post = Held(id = 99, to = "@RACES", from = "KN4CRD", text = "P", receivedMs = 0, kind = Held.Kind.POSTED)
        val held = (1..5).map { relay(it, "K2XYZ", it * 1000L) } + post
        val room = Held.makeRoomForRelay(held, max = 3)
        assertEquals(listOf(4, 5, 99), room.map { it.id })
        assertEquals(held, Held.makeRoomForRelay(held, max = 10))
    }
}
