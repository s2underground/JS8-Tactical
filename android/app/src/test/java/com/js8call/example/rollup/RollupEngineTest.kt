package com.js8call.example.rollup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RollupEngineTest {

    // 2026-11-18 13:00:00Z as the base; Normal speed (15 s slots).
    private val base: Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
        clear(); set(2026, Calendar.NOVEMBER, 18, 13, 0, 0); timeInMillis
    }

    /** The frames JS8 sends for "KN4XYZ: @TEAM ROAD BLOCKED AT BRIDGE, REROUTING VIA COUNTY 12, ETA 1630". */
    private val roadMessage = listOf(
        "KN4XYZ: @TEAM " to 1,
        "ROAD BLOCKED AT BRIDGE" to 4, // data frames carry flag 4 (set by the JNI layer)
        ", REROUTING VIA " to 4,
        "COUNTY 12, ETA 1" to 4,
        "630" to 6,
    )

    private fun frame(slot: Int, text: String, type: Int, hz: Float = 1450f, snr: Int = -11): RollupEngine.Frame {
        val startMs = base + slot * 15_000L
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = startMs }
        val utc = cal.get(Calendar.HOUR_OF_DAY) * 10000 + cal.get(Calendar.MINUTE) * 100 + cal.get(Calendar.SECOND)
        return RollupEngine.Frame(utc, hz, type, text, snr, 0, receivedAtMs = startMs + 14_000L)
    }

    /** Send [msg] starting at [startSlot], skipping positions in [drop]. */
    private fun RollupEngine.send(msg: List<Pair<String, Int>>, startSlot: Int, drop: Set<Int> = emptySet(), hz: Float = 1450f) {
        msg.forEachIndexed { i, (text, type) ->
            if (i !in drop) addFrame(frame(startSlot + i, text, type, hz))
        }
    }

    private fun RollupEngine.settle(afterSlot: Int) = tick(base + (afterSlot + 10) * 15_000L)

    @Test
    fun completeMessageRollsUpWithSenderAndRecipient() {
        val e = RollupEngine()
        e.send(roadMessage, startSlot = 0)
        val msgs = e.messages()
        assertEquals(1, msgs.size)
        val m = msgs[0]
        assertEquals("KN4XYZ", m.sender)
        assertEquals("@TEAM", m.recipient)
        assertTrue(m.isComplete)
        assertEquals(1, m.copies)
        assertEquals("ROAD BLOCKED AT BRIDGE, REROUTING VIA COUNTY 12, ETA 1630", m.bodyText)
    }

    @Test
    fun droppedMiddleFrameIsAKnownGapAtTheRightPlace() {
        val e = RollupEngine()
        e.send(roadMessage, startSlot = 0, drop = setOf(2))
        val m = e.messages().single()
        assertFalse(m.isComplete)
        assertEquals(1, m.missingFrames)
        assertEquals("ROAD BLOCKED AT BRIDGE[…]COUNTY 12, ETA 1630", m.bodyText)
        val gap = m.segments.filterIsInstance<RollupEngine.Segment.Gap>().single()
        assertEquals(1, gap.count)
    }

    @Test
    fun twoDamagedCopiesMergeIntoOneCompleteMessage() {
        val e = RollupEngine()
        e.send(roadMessage, startSlot = 0, drop = setOf(2))
        e.send(roadMessage, startSlot = 8, drop = setOf(3), hz = 1520f) // resent at another offset
        val m = e.messages().single()
        assertEquals(2, m.copies)
        assertTrue(m.isComplete)
        assertFalse(m.conflict)
        assertEquals("ROAD BLOCKED AT BRIDGE, REROUTING VIA COUNTY 12, ETA 1630", m.bodyText)
    }

    @Test
    fun copyWithMissedFirstFrameStillMergesAndGainsItsSender() {
        val e = RollupEngine()
        e.send(roadMessage, startSlot = 0, drop = setOf(3))
        e.send(roadMessage, startSlot = 8, drop = setOf(0))
        e.settle(13)
        val m = e.messages().single()
        assertEquals(2, m.copies)
        assertEquals("KN4XYZ", m.sender)
        assertTrue(m.isComplete)
    }

    @Test
    fun headlessCopyAloneHasUnknownSender() {
        val e = RollupEngine()
        e.send(roadMessage, startSlot = 0, drop = setOf(0))
        val m = e.messages().single()
        assertNull(m.sender)
        assertTrue(m.headMissing)
        assertTrue(m.bodyText.startsWith("[…]ROAD BLOCKED"))
    }

    @Test
    fun interleavedStationsStayApart() {
        val e = RollupEngine()
        val other = listOf("N0DEF: @TEAM " to 1, "ARRIVED AT CP1" to 4, " ALL GOOD" to 6)
        // Both transmit in the same slots on different offsets, frames interleaved.
        for (i in 0 until 5) {
            e.addFrame(frame(i, roadMessage[i].first, roadMessage[i].second, 1450f))
            if (i < other.size) e.addFrame(frame(i, other[i].first, other[i].second, 820f))
        }
        val bySender = e.messages().associateBy { it.sender }
        assertEquals(setOf("KN4XYZ", "N0DEF"), bySender.keys)
        assertEquals("ARRIVED AT CP1 ALL GOOD", bySender["N0DEF"]!!.bodyText)
        assertTrue(bySender["KN4XYZ"]!!.isComplete)
    }

    @Test
    fun editedRepeatIsNotMergedIntoTheOriginal() {
        val e = RollupEngine()
        e.send(roadMessage, startSlot = 0)
        val edited = roadMessage.toMutableList().also { it[4] = "645" to 6 }
        e.send(edited, startSlot = 8)
        assertEquals(2, e.messages().size)
    }

    @Test
    fun differentMessagesToTheSameGroupAreNotMergedOnHeaderAlone() {
        val e = RollupEngine()
        e.send(roadMessage, startSlot = 0)
        e.send(listOf("KN4XYZ: @TEAM " to 1, "NEED WATER" to 6), startSlot = 8)
        assertEquals(2, e.messages().size)
    }

    @Test
    fun missingLastFrameClosesAfterIdleAndIsMarkedIncomplete() {
        val e = RollupEngine()
        e.send(roadMessage, startSlot = 0, drop = setOf(4))
        assertTrue(e.messages().isEmpty())
        assertEquals("KN4XYZ", e.live().single().sender)
        e.settle(4)
        val m = e.messages().single()
        assertTrue(m.tailMissing)
        assertFalse(m.isComplete)
        assertTrue(m.bodyText.endsWith("[…]"))
    }

    @Test
    fun sameStationOnDifferentOffsetsIsOneThread() {
        val e = RollupEngine()
        e.addFrame(frame(0, "KQ4BR: @HB HEARTBEAT EM73", 3, 670f))
        e.addFrame(frame(4, "KQ4BR: @HB HEARTBEAT EM73", 3, 821f))
        e.addFrame(frame(9, "KQ4BR: @HB HEARTBEAT EM73", 3, 716f))
        val threads = e.threads()
        assertEquals(setOf("KQ4BR"), threads.keys)
        assertEquals(3, threads["KQ4BR"]!!.size) // header-only frames are never merged
    }

    @Test
    fun compoundIdentityHeaderIsParsed() {
        val h = HeaderParser.parse("KN4XYZ EM73")!!
        assertEquals("KN4XYZ", h.sender)
        assertNull(h.recipient)
        val d = HeaderParser.parse("W4QRP: KQ4BR MSG ")!!
        assertEquals("W4QRP", d.sender)
        assertEquals("KQ4BR", d.recipient)
    }
}
