package com.js8call.example.rollup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Random
import java.util.TimeZone

/**
 * A busy net, heavier than the Nov 19 GhostNet session: 90 stations on one band for three hours,
 * several transmissions every slot, repeats (×2/×3), relays of the same text from other lanes,
 * missed frames, and the refresh pattern of the Traffic screen (tick + read every message's
 * sender, recipient and text after every slot). Must not throw and must stay fast.
 */
class RollupStressTest {
    private val base: Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
        clear(); set(2026, Calendar.NOVEMBER, 20, 0, 0, 0); timeInMillis
    }

    private fun utcOf(ms: Long): Int {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        return c.get(Calendar.HOUR_OF_DAY) * 10000 + c.get(Calendar.MINUTE) * 100 + c.get(Calendar.SECOND)
    }

    private val words = listOf("CHECKIN", "QRV", "WX CLEAR", "NET CONTROL", "POWER OUT", "ROAD OPEN", "73", "SNR -12",
        "GRID EM96", "ALL GOOD", "RELAY", "STANDBY", "TRAFFIC", "SITREP GREEN", "ACK", "HEARING KN4XYZ")

    /** One message as JS8-like frames: header frame, then 0–5 data frames, the last one flagged. */
    private fun message(rnd: Random, sender: String): List<Pair<String, Int>> {
        val to = if (rnd.nextInt(3) == 0) "@GHOSTNET" else "K${rnd.nextInt(90)}ABC"
        val body = (0..rnd.nextInt(6)).map { words[rnd.nextInt(words.size)] }
        if (body.size == 1) return listOf("$sender: $to ${body[0]}" to 3)
        return listOf("$sender: $to " to 1) + body.mapIndexed { i, w -> " $w" to if (i == body.lastIndex) 6 else 4 }
    }

    @Test
    fun threeHourBusyNetStaysFastAndNeverThrows() {
        val rnd = Random(20261120)
        val e = RollupEngine()
        val slots = 3 * 60 * 4 // 15-second slots in three hours
        // Each lane carries one message at a time: (frames, next index, start slot)
        val lanes = HashMap<Int, Triple<List<Pair<String, Int>>, Int, Int>>()
        val repeats = ArrayList<Pair<Int, List<Pair<String, Int>>>>() // (start slot, frames) for ×2/×3 copies
        var reads = 0L
        val t0 = System.nanoTime()
        var worstRefreshMs = 0.0
        for (slot in 0 until slots) {
            val slotMs = base + slot * 15_000L
            // New messages on free lanes
            repeat(1 + rnd.nextInt(5)) {
                val lane = 500 + rnd.nextInt(40) * 50
                if (lane !in lanes) {
                    val m = message(rnd, "K${rnd.nextInt(90)}XY")
                    lanes[lane] = Triple(m, 0, slot)
                    if (rnd.nextInt(3) == 0) repeats += (slot + m.size + 1 + rnd.nextInt(3)) to m
                }
            }
            // Repeats start on a nearby lane
            repeats.filter { it.first == slot }.forEach { (_, m) ->
                val lane = 500 + rnd.nextInt(40) * 50
                if (lane !in lanes) lanes[lane] = Triple(m, 0, slot)
            }
            // This slot's frames, some missed
            val done = ArrayList<Int>()
            for ((lane, st) in lanes) {
                val (frames, i, _) = st
                val (text, type) = frames[i]
                if (rnd.nextInt(6) != 0) {
                    val at = slotMs + 13_000L
                    e.addFrame(RollupEngine.Frame(utcOf(slotMs), lane + rnd.nextFloat() * 4f, type, text, -20 + rnd.nextInt(30), 0, at), at)
                }
                if (i + 1 >= frames.size) done += lane else lanes[lane] = Triple(frames, i + 1, st.third)
            }
            done.forEach { lanes.remove(it) }
            // The screen's refresh after the slot
            val r0 = System.nanoTime()
            e.tick(slotMs + 14_000L)
            e.messages().forEach { m ->
                reads += (m.sender?.length ?: 0) + (m.recipient?.length ?: 0) + m.bodyText.length
            }
            worstRefreshMs = maxOf(worstRefreshMs, (System.nanoTime() - r0) / 1e6)
        }
        e.tick(base + (slots + 10) * 15_000L)
        val totalMs = (System.nanoTime() - t0) / 1e6
        val n = e.messages().size
        println("stress: $n messages, total ${"%.0f".format(totalMs)} ms, worst refresh ${"%.1f".format(worstRefreshMs)} ms, reads $reads")
        assertTrue("expected a busy net, got $n messages", n > 1000)
        // A phone is slower than the build machine; keep plenty of headroom under a 15 s slot.
        assertTrue("worst refresh $worstRefreshMs ms", worstRefreshMs < 250.0)
    }
}
