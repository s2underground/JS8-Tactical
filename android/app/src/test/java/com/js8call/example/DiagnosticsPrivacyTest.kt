package com.js8call.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsPrivacyTest {
    private fun scrub(s: String) = DiagnosticsPrivacy.scrub(s, "KQ4BR", "EM96")

    @Test fun ownCallAndGridAnyCaseOrSuffix() {
        val out = scrub("KQ4BR: @HB HEARTBEAT EM96\nkq4br/p sent; grid em96ab; VE3/KQ4BR")
        assertFalse(out.contains("KQ4BR", ignoreCase = true))
        assertFalse(out.contains("EM96", ignoreCase = true))
        assertEquals("MY-STATION: @HB HEARTBEAT [GRID]\nMY-STATION sent; grid [GRID]; MY-STATION", out)
    }

    @Test fun otherCallsGetStableLabels() {
        val out = scrub("K9DGY: W9ZOM SNR -10\nW9ZOM: K9DGY ACK\nK9DGY/M heard FN31")
        assertEquals("STATION-1: STATION-2 SNR -10\nSTATION-2: STATION-1 ACK\nSTATION-1 heard [GRID]", out)
    }

    @Test fun groupsVersionsAndRadioNamesStay() {
        val s = "@GHOSTNET MSG · JS8-Tactical 1.2.0-alpha.3 · JS8CALL · Xiegu X6100 · G90 · Android 14 · SM-X306B · 7.107 MHz"
        assertEquals(s, scrub(s))
    }

    @Test fun addressesMasked() {
        val out = scrub("bt addr=00:1A:7D:DA:71:13 rigctld 192.168.1.40:4532 mail joe@example.com, report to js8tactical@proton.me, local 127.0.0.1")
        assertTrue(out.contains("XX:XX:XX:XX:XX:13"))
        assertFalse(out.contains("00:1A:7D"))
        assertTrue(out.contains("[IP]:4532"))
        assertTrue(out.contains("[EMAIL]"))
        assertTrue(out.contains("js8tactical@proton.me"))
        assertTrue(out.contains("127.0.0.1"))
        assertEquals("build E031.47.18.42", scrub("build E031.47.18.42"))
    }

    // Found by the Kotlin fuzzer: a "/" right before a callsign used to protect it from the scrub.
    @Test fun callsAfterSlashMasked() {
        val out = scrub("via VE3/K9DGY, path 8.21./kq4br[6")
        assertFalse(out.contains("K9DGY"))
        assertFalse(out.contains("KQ4BR", ignoreCase = true))
        assertEquals("via VE3/STATION-1, path 8.21./MY-STATION[6", out)
    }
}
