package com.js8call.example.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TxOffsetsTest {
    @Test
    fun zonePicksKeepTheWholeSignalInsideTheZone() {
        val r = Random(7)
        listOf(25f, 50f, 80f, 160f).forEach { bw ->
            repeat(2000) {
                val hz = TxOffsets.pickInZone(bw, r)
                assertTrue("$hz @ $bw", TxOffsets.inZone(hz, bw))
            }
        }
    }

    @Test
    fun inZoneCountsTheSignalWidth() {
        assertTrue(TxOffsets.inZone(950f, 50f))
        assertFalse(TxOffsets.inZone(951f, 50f))
        assertFalse(TxOffsets.inZone(499f, 50f))
        assertFalse(TxOffsets.inZone(1674f, 50f))
    }
}
