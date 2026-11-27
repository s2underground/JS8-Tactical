package com.js8call.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.net.SocketTimeoutException

class BoundedLineTest {
    private val far = Long.MAX_VALUE

    @Test fun readsOneLineAtATime() {
        val r = StringReader("RPRT 0\r\n14078000\nlast")
        assertEquals("RPRT 0", BoundedLine.read(r, far))
        assertEquals("14078000", BoundedLine.read(r, far))
        assertEquals("last", BoundedLine.read(r, far))
        assertNull(BoundedLine.read(r, far))
    }

    @Test(expected = IOException::class) fun stopsAtTheLengthLimit() {
        // A peer that never sends a newline can't grow the line without end.
        BoundedLine.read(StringReader("x".repeat(BoundedLine.MAX_CHARS + 1)), far)
    }

    @Test fun aLineOfExactlyTheLimitIsFine() {
        assertEquals(BoundedLine.MAX_CHARS, BoundedLine.read(StringReader("y".repeat(BoundedLine.MAX_CHARS) + "\n"), far)!!.length)
    }

    @Test(expected = SocketTimeoutException::class) fun stopsAtTheDeadlineEvenIfBytesKeepComing() {
        // A peer trickling one byte at a time never trips a per-read timeout; the deadline still ends it.
        val trickle = object : Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int { cbuf[off] = 'z'; return 1 }
            override fun close() {}
        }
        var t = 0L
        BoundedLine.read(trickle, deadlineMs = 100, maxChars = Int.MAX_VALUE, now = { t += 10; t })
    }
}
