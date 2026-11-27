package com.js8call.example.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttLedgerTest {
    @Test fun nothingSentIsNotKeyed() {
        assertFalse(PttLedger().keyedSinceRelease())
    }

    @Test fun keyWithoutReleaseIsKeyed() {
        val l = PttLedger()
        l.keySent()
        assertTrue(l.keyedSinceRelease())
    }

    @Test fun confirmedReleaseClearsIt() {
        // Build 342: keyed, released, radio unplugged minutes later: no alarm.
        val l = PttLedger()
        l.keySent()
        l.releaseConfirmed()
        assertFalse(l.keyedSinceRelease())
    }

    @Test fun aKeyAfterTheReleaseCountsAgain() {
        val l = PttLedger()
        l.keySent(); l.releaseConfirmed(); l.keySent()
        assertTrue(l.keyedSinceRelease())
    }

    @Test fun aFailedReleaseLeavesItKeyed() {
        // Only confirmed releases are recorded; a key whose release never took stays keyed.
        val l = PttLedger()
        l.keySent(); l.keySent()
        assertTrue(l.keyedSinceRelease())
    }
}
