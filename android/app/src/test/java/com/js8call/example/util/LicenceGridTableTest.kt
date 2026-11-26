package com.js8call.example.util

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LicenceGridTableTest {
    @Test
    fun encodingMatchesTheBuilder() {
        // Same vectors as tools/callgrid/test_build_callgrid.py.
        assertEquals(1_460_545_511L, LicenceGridTable.encodeCall("K1ABC"))
        assertEquals(2_292_701_263L, LicenceGridTable.encodeCall("w1aw "))
        assertEquals(1_482_983_397L, LicenceGridTable.encodeCall("KD9ABC"))
        assertEquals("EM65", LicenceGridTable.gridText(8_465))
    }

    @Test
    fun portableForeignAndMalformedCallsAreNotLookedUp() {
        assertNull(LicenceGridTable.encodeCall("W1AW/P"))
        assertNull(LicenceGridTable.encodeCall("KH6/W1AW"))
        assertNull(LicenceGridTable.encodeCall("AB"))
        assertNull(LicenceGridTable.encodeCall("VE3ABCD"))
        assertNull(LicenceGridTable.encodeCall("K1-AB"))
        assertNull(LicenceGridTable.gridText(32_400))
    }

    @Test
    fun looksUpSortedRecords() {
        val t = LicenceGridTable.parse(table(listOf("W1AW" to 8_465, "K1ABC" to 0, "KD9ABC" to 32_399)))!!
        assertEquals(3, t.count)
        assertEquals(20_261_126, t.dataDate)
        assertEquals("EM65", t.lookup("W1AW"))
        assertEquals("AA00", t.lookup("k1abc"))
        assertEquals("RR99", t.lookup("KD9ABC"))
        assertNull(t.lookup("N0CALL"))
        assertNull(t.lookup("W1AW/M"))
    }

    @Test
    fun rejectsDamagedFiles() {
        val good = table(listOf("W1AW" to 1)).array()
        assertNull(LicenceGridTable.parse(ByteBuffer.wrap(good.copyOf(10))))
        assertNull(LicenceGridTable.parse(ByteBuffer.wrap(good.copyOf(good.size - 1))))
        assertNull(LicenceGridTable.parse(ByteBuffer.wrap(good.copyOf().also { it[0] = 'X'.code.toByte() })))
        assertNull(LicenceGridTable.parse(ByteBuffer.wrap(good.copyOf().also { it[4] = 2 })))
        assertNull(LicenceGridTable.parse(ByteBuffer.wrap(good.copyOf().also { it[8] = 0x7F })))
        assertNull(LicenceGridTable.parse(ByteBuffer.wrap(good.copyOf().also { it[8] = 0xFF.toByte() })))
    }

    private fun table(entries: List<Pair<String, Int>>): ByteBuffer {
        val recs = entries.map { LicenceGridTable.encodeCall(it.first)!! to it.second }.sortedBy { it.first }
        val b = ByteBuffer.allocate(16 + 6 * recs.size)
        b.put("JS8G".toByteArray()).put(1).put(0).put(0).put(0).putInt(recs.size).putInt(20_261_126)
        recs.forEach { (c, g) -> b.putInt(c.toInt()).putShort(g.toShort()) }
        return b
    }
}
