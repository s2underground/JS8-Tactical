package com.js8call.voacap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoacapRequestTest {
    private fun req(bands: List<Double> = listOf(7.078, 14.078), month: Int = 11) = Voacap.Request(
        txLat = 35.8, txLon = -78.6, rxLat = 41.9, rxLon = -87.6, month = month, year = 2026,
        ssn = 90.0, watts = 20.0, bandsMhz = bands, js8ThresholdDb = -24.0,
    )

    @Test fun jsonCarriesTheRequestInTheEnginesUnits() {
        val json = req().toJson()
        assertTrue(json, json.contains("\"fromLat\":35.8000"))
        assertTrue(json, json.contains("\"bands\":[7.0780,14.0780]"))
        // -24 dB in 2500 Hz is 9.98 dB in 1 Hz, the bandwidth VOACAP works in.
        assertTrue(json, json.contains("\"requiredSnrDb\":9.9794"))
        assertTrue(json, json.contains("\"noiseDbw\":145.0000"))
        assertTrue(json, json.all { it.code < 0x80 })
    }

    @Test fun noDataPathIsEverSent() {
        assertTrue(!req().toJson().contains("itshfbc"))
    }

    @Test fun js8BandwidthIs34Db() {
        assertEquals(33.98, Voacap.JS8_BANDWIDTH_DB, 0.01)
    }

    @Test(expected = IllegalArgumentException::class) fun tooManyBands() {
        req(bands = List(Voacap.MAX_BANDS + 1) { 7.0 + it })
    }

    @Test(expected = IllegalArgumentException::class) fun badMonth() {
        req(month = 13)
    }
}
