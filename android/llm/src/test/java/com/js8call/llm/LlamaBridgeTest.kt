package com.js8call.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LlamaBridgeTest {
    @Test fun textFromTheEngineIsCappedAndCleaned() {
        assertEquals("ROAD BLOCKED\nat bridge\tETA 1630", LlamaBridge.clean("ROAD BLOCKED\nat bridge\tETA 1630", 100))
        // Control characters (escape sequences, NULs) never reach the screen.
        assertEquals("ab[31mc", LlamaBridge.clean("a\u0000b\u001b[31mc", 100))
        assertEquals(LlamaBridge.MAX_TEXT, LlamaBridge.clean("x".repeat(LlamaBridge.MAX_TEXT + 10), LlamaBridge.MAX_TEXT).length)
    }
}
