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
        assertTrue(out.contains("1 report (1 relayed or repeated copy merged)"))
        assertTrue(out.contains("2 short messages"))
        assertTrue(out.contains("1 garbled message"))
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

class ExportSanitizeTest {
    private val t0 = 1_763_700_000_000L
    private val h = Header("KF4ABC", t0, t0 + 3_600_000L, "last 6 h")

    @Test fun everyCallsignBecomesAStableLabel() {
        val entries = listOf(
            Entry(t0 + 1000, false, "N0AAA", "@GHOSTNET", "N0AAA: GRID DOWN IN EN52. RELAY VIA W1XYZ/P AND KF4ABC. H5N1 IN 3 COUNTIES ON 40M", -12),
            Entry(t0 + 2000, false, "W1XYZ/P", "KF4ABC", "SNR? DE W1XYZ", -5),
            Entry(t0 + 3000, true, null, "N0AAA", "QSL N0AAA DE KF4ABC/M"),
            Entry(t0 + 4000, false, "VK2ZZ", "n0aaa", "HELLO", -1),
        )
        val (sh, se) = ExportBuilder.sanitize(h, entries)
        val text = ExportBuilder.rawLog(sh, se) + ExportBuilder.rollup(sh, se)
        listOf("N0AAA", "W1XYZ", "KF4ABC", "VK2ZZ").forEach { assertFalse(it, text.uppercase().contains(it)) }
        assertEquals(se[0].sender, se[3].recipient)
        assertEquals("STATION-1", se[0].sender)
        assertTrue(se[0].body.contains("EN52") && se[0].body.contains("H5N1") && se[0].body.contains("40M"))
        assertTrue(se[0].body.contains(ExportBuilder.MY_LABEL))
        assertEquals(se[1].sender, se[0].body.substringAfter("RELAY VIA ").substringBefore(" "))
        assertEquals("@GHOSTNET", se[0].recipient)
        assertTrue(text.contains("Sanitized:"))
        assertEquals("js8tactical-raw-log-sanitized-20251121-0440Z.txt", ExportBuilder.fileName(false, t0, "KF4ABC", true))
    }
}

class ExportPartialTest {
    private val t0 = 1_763_700_000_000L
    private val h = Header("KF4ABC", t0, t0 + 3_600_000L, "last 6 h")
    private val start = "QST AVIATION // TERROR ALERT FBI/DHS/TSA ISSUED A NOV 19 ADVISORY TO U.S. AIRLINES, AIRPORTS AND CARGO OPERATORS"
    private val end = "AFFILIATES PRAISED SEPTEMBER S THWARTED FLYDUBAI COCKPIT HIJACKING. NO SPECIFIC CREDIBLE ACTIVE PLOT IDENTIFIED."

    @Test fun oneOrTwoMissingFramesAreKeptAndMarked() {
        val e = listOf(
            Entry(t0 + 1000, false, "K9DGY", "@GHOSTNET", "$start [\u2026] $end", -12, complete = false, missingFrames = 1),
            Entry(t0 + 2000, false, "N0AAA", "@GHOSTNET", "$start [\u2026] [\u2026] [\u2026] $end", -12, complete = false, missingFrames = 3),
        )
        val out = ExportBuilder.rollup(h, e)
        assertEquals(1, Regex("^\\[\\d+]", RegexOption.MULTILINE).findAll(out).count())
        assertTrue(out.contains("INCOMPLETE: 1 frame missing"))
        assertTrue(out.contains("CARGO OPERATORS [...] AFFILIATES"))
        assertTrue(out.contains("1 garbled message"))
    }

    @Test fun partialCopyFoldsIntoAWholeOne() {
        val e = listOf(
            Entry(t0 + 1000, false, "K9DGY", "@GHOSTNET", "$start [\u2026] $end", -12, complete = false, missingFrames = 1),
            Entry(t0 + 9000, false, "N0DDK", "@GHOSTNET", "$start WARNING THAT $end", -9),
        )
        val out = ExportBuilder.rollup(h, e)
        assertEquals(1, Regex("^\\[\\d+]", RegexOption.MULTILINE).findAll(out).count())
        assertTrue(out.contains("WARNING THAT"))
        assertFalse(out.contains("INCOMPLETE"))
    }
}
