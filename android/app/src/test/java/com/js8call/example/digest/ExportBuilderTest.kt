package com.js8call.example.digest

import com.js8call.example.digest.ExportBuilder.Entry
import com.js8call.example.digest.ExportBuilder.Header
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportBuilderTest {
    private val t0 = 1_763_700_000_000L // 2025-11-21
    private val h = Header("KF4ABC", t0, t0 + 3_600_000L, "last 6 h")
    private val longText = "SUBSEA CABLE FAULT OFF PAKISTAN. ABOUT HALF OF AFFECTED TRAFFIC REROUTED VIA THE GULF. EXPECT DELAYS."
    private val entries = listOf(
        Entry(t0 + 1000, false, "N0AAA", "@GHOSTNET", longText, -12, 1520f),
        Entry(t0 + 2000, false, "N0BBB", "@GHOSTNET", longText, -5, 1600f),               // relay: same text
        Entry(t0 + 3000, false, "N0CCC", "KF4ABC", "SNR?", -8, 1500f),                     // short traffic
        Entry(t0 + 4000, false, "N0DDD", "@ALLCALL", "HEARTBEAT EN52", -20, 1700f),         // short traffic
        Entry(t0 + 5000, false, null, null, "[…] LONG REPORT WITH THE START MISSING AND QUITE A LOT OF TEXT AFTER IT TO PASS 80", -15, 1800f,
            complete = false, missingFrames = 1),                                            // garbled
        Entry(t0 + 6000, true, null, "N0AAA", "QSL THANKS", laneHz = 1520f),                 // sent
        Entry(t0 - 99_000_000, false, "N0OLD", "@GHOSTNET", longText + " OLD", -1, 1500f),   // outside period
    )

    @Test fun rollupKeepsLongReportsOnceAndCountsTheRest() {
        val out = ExportBuilder.rollup(h, entries)
        assertEquals(1, Regex("^\\[\\d+]", RegexOption.MULTILINE).findAll(out).count())
        assertTrue(out.contains("Also heard from: N0BBB"))
        assertTrue(out.contains("1 report (1 relayed copy merged)"))
        assertTrue(out.contains("2 short messages"))
        assertTrue(out.contains("1 incomplete or garbled message"))
        assertFalse(out.contains("SNR?"))
        assertFalse(out.contains("HEARTBEAT"))
        assertFalse(out.contains("N0OLD"))
        assertFalse(out.contains("QSL THANKS"))
    }

    @Test fun rawLogHasEverythingInThePeriod() {
        val out = ExportBuilder.rawLog(h, entries)
        listOf("SNR?", "HEARTBEAT EN52", "QSL THANKS", "INCOMPLETE (1 frame missing)", "N0BBB", "TX", "1520 Hz").forEach {
            assertTrue(it, out.contains(it))
        }
        assertFalse(out.contains("N0OLD"))
        assertTrue(out.contains("5 received, 1 sent"))
    }

    @Test fun fileNamesAndWrap() {
        assertEquals("js8tactical-KF4ABC-rollup-20251121-0440Z.txt", ExportBuilder.fileName(true, t0, "KF4ABC"))
        assertTrue(ExportBuilder.wrap(longText, 30).all { it.length <= 30 })
        assertEquals(longText, ExportBuilder.wrap(longText, 30).joinToString(" "))
    }
}
