package com.js8call.example.prop

import com.js8call.example.reports.LatLon
import com.js8call.voacap.Voacap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoacapCoverageTest {
    private fun grid(lat0: Double, dLat: Double, rows: Int, lon0: Double, dLon: Double, cols: Int, value: Int) =
        Voacap.AreaGrid(lat0, dLat, rows, lon0, dLon, cols, Band.entries.size, ByteArray(rows * cols * Band.entries.size) { value.toByte() })

    @Test fun nearGridWinsInsideItsBox() {
        val me = LatLon(35.8, -78.6)
        val world = grid(-88.5, 3.0, 60, -178.5, 3.0, 120, 0)
        val box = VoacapCoverage.nearBox(me)
        val near = grid(box[0] + 0.25, 0.5, 48, box[2] + 0.25, 0.5, 48, 255)
        val m = VoacapCoverage.Maps(world, near, box)
        assertEquals(1f, m.sample(36.0, -79.0, Band.B40.ordinal), 1e-6f)
        assertEquals(0f, m.sample(10.0, 20.0, Band.B40.ordinal), 1e-6f)
    }

    @Test fun bandChartRowAveragesBearingsPerBucket() {
        val me = LatLon(35.8, -78.6)
        val m = VoacapCoverage.Maps(grid(-88.5, 3.0, 60, -178.5, 3.0, 120, 255), null, null)
        val row = VoacapCoverage().stoplight(m, me, Band.B20.ordinal)
        assertEquals(PropagationModel.CHART_BUCKETS_KM.size, row.size)
        assertTrue(row.all { kotlin.math.abs(it - 1.0) < 1e-6 })
    }

    @Test fun nearBoxStaysOnTheMap() {
        val b = VoacapCoverage.nearBox(LatLon(85.0, 175.0))
        assertTrue(b[1] <= 89.0 && b[3] <= 180.0 && b[0] < b[1] && b[2] < b[3])
    }
}
