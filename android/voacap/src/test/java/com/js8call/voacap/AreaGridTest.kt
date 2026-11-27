package com.js8call.voacap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AreaGridTest {
    /** The layout js8voacap_area writes: seven f32 then one byte per cell and band. */
    private fun pack(lat0: Float, dLat: Float, rows: Int, lon0: Float, dLon: Float, cols: Int, bands: Int, cell: (Int, Int, Int) -> Int): ByteArray {
        val b = ByteBuffer.allocate(28 + rows * cols * bands).order(ByteOrder.LITTLE_ENDIAN)
        listOf(lat0, dLat, rows.toFloat(), lon0, dLon, cols.toFloat(), bands.toFloat()).forEach { b.putFloat(it) }
        for (r in 0 until rows) for (c in 0 until cols) for (k in 0 until bands) b.put(cell(r, c, k).toByte())
        return b.array()
    }

    @Test fun unpacksAndReadsCells() {
        val g = Voacap.AreaGrid.unpack(pack(-85f, 10f, 18, -175f, 10f, 36, 2) { r, c, k -> if (k == 1 && r == 12 && c == 10) 255 else 0 })
        assertEquals(18, g.rows); assertEquals(36, g.cols); assertEquals(2, g.bands)
        // Row 12 is 35 N, column 10 is 75 W.
        assertEquals(1f, g.sample(35.0, -75.0, 1), 1e-6f)
        assertEquals(0f, g.sample(35.0, -75.0, 0), 1e-6f)
        assertEquals(0.5f, g.sample(40.0, -75.0, 1), 1e-6f)
    }

    @Test fun wrapsAcrossTheDateLineOnAWorldGrid() {
        // Only the westernmost column (175 W) is open: 180 is half way to it from 175 E.
        val g = Voacap.AreaGrid.unpack(pack(-85f, 10f, 18, -175f, 10f, 36, 1) { _, c, _ -> if (c == 0) 255 else 0 })
        assertEquals(0.5f, g.sample(0.0, 180.0, 0), 0.01f)
        assertEquals(0.5f, g.sample(0.0, -180.0, 0), 0.01f)
    }

    @Test(expected = IllegalArgumentException::class) fun refusesAShortBuffer() {
        Voacap.AreaGrid.unpack(ByteArray(10))
    }

    @Test fun areaRequestJson() {
        val j = Voacap.AreaRequest(35.8, -78.6, 11, 2026, 90.0, 20.0, listOf(7.078, 14.078), -24.0, Voacap.Noise.RURAL, 15, 3.0,
            box = doubleArrayOf(20.0, 50.0, -100.0, -60.0)).toJson()
        listOf("\"mode\":\"area\"", "\"hour\":15", "\"freqsMhz\":[7.0780,14.0780]", "\"noiseDbw\":150.0000", "\"latMin\":20.0000", "\"lonMax\":-60.0000")
            .forEach { assertTrue("$it not in $j", j.contains(it)) }
        assertFalse(j.contains("itshfbc"))
    }
}
