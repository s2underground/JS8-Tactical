package com.js8call.example.ui.traffic

import com.js8call.example.rollup.RollupEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrafficHistoryTest {

    private fun tmp(): File = File(Files.createTempDirectory("hist").toFile(), "h.tsv")

    @Test
    fun roundTripWithAwkwardText() {
        val h = TrafficHistory(tmp())
        val f = RollupEngine.Frame(181320, 1503.5f, 5, "KX4ABC: @TEAM\tTAB\\SLASH\nLINE", -12, 0, 1_000L)
        h.append(TrafficHistory.Entry.Frame(f))
        h.append(TrafficHistory.Entry.Sent(2_000L, 1200f, "W1AW SNR -10"))
        val back = h.load(0)
        assertEquals(2, back.size)
        val g = (back[0] as TrafficHistory.Entry.Frame).frame
        assertEquals(f, g)
        val o = back[1] as TrafficHistory.Entry.Sent
        assertEquals("W1AW SNR -10", o.text)
        assertEquals(1200f, o.frequencyHz)
    }

    @Test
    fun loadDropsOldEntriesAndRewritesFile() {
        val file = tmp()
        val h = TrafficHistory(file)
        listOf(1_000L, 5_000L, 9_000L).forEach {
            h.append(TrafficHistory.Entry.Sent(it, 1000f, "T$it"))
        }
        file.appendText("garbage line\n")
        assertEquals(listOf("T5000", "T9000"), h.load(5_000L).map { (it as TrafficHistory.Entry.Sent).text })
        assertEquals(2, file.readLines().size)
        h.prune(9_000L)
        assertEquals(1, file.readLines().size)
        h.clear()
        assertTrue(!file.exists())
        assertTrue(h.load(0).isEmpty())
    }

    @Test
    fun replayThroughEngineRebuildsTheMessage() {
        val h = TrafficHistory(tmp())
        val t0 = 1794960015000L // 2026-11-18 00:00:15Z, Normal slot 1
        listOf("KX4ABC: @TEAM " to 1, "WATER AT GRID " to 4, "EM83" to 6).forEachIndexed { i, (text, type) ->
            val at = t0 + i * 15_000L
            val utc = 15 + i * 15 // HHMMSS within minute 00
            h.append(TrafficHistory.Entry.Frame(RollupEngine.Frame(utc, 1450f, type, text, -10, 0, at)))
        }
        val engine = RollupEngine()
        h.load(0).forEach { engine.addFrame((it as TrafficHistory.Entry.Frame).frame) }
        engine.tick(t0 + 120_000L)
        val m = engine.messages().single()
        assertEquals("KX4ABC", m.sender)
        assertTrue(m.bodyText, m.bodyText.contains("WATER AT GRID EM83"))
    }
}
