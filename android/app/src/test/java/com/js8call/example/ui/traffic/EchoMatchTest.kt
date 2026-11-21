package com.js8call.example.ui.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EchoMatchTest {
    private val t = 1_000_000_000L

    @Test fun firstFrameOfALongMessageMatchesTheSendStillOnTheAir() {
        // Heartbeat at 934 Hz finished; a two-frame message at 1249 Hz is still going when its first frame is heard back.
        val spans = listOf(934f to (t..t + 13_000), 1249f to (t + 45_000..Long.MAX_VALUE))
        val sent = listOf(934f to t + 13_000)
        assertEquals(1249f, TrafficViewModel.matchSentHz(1249.5f, t + 59_700, spans, sent))
    }

    @Test fun realOffsetErrorIsStillReported() {
        val spans = listOf(934f to (t..t + 13_000))
        assertEquals(934f, TrafficViewModel.matchSentHz(1250f, t + 14_500, spans, listOf(934f to t + 13_000)))
    }

    @Test fun nothingSentNearbyMeansNoMatch() {
        assertNull(TrafficViewModel.matchSentHz(1250f, t + 600_000, listOf(934f to (t..t + 13_000)), listOf(934f to t + 13_000)))
    }
}
