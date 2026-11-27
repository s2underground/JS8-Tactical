package com.js8call.example.service

import java.io.IOException
import java.io.Reader
import java.net.SocketTimeoutException

/**
 * One reply line from a network peer, with limits: [read] stops at [maxChars] and at an overall
 * deadline. A plain readLine() has neither, so a rigctld (or anything listening on that port)
 * that sends no newline could grow the line without end, or trickle a byte at a time to keep a
 * per-read socket timeout from ever firing while PTT commands wait behind it.
 */
object BoundedLine {

    /** rigctld replies are a few dozen characters ("RPRT 0", a frequency). */
    const val MAX_CHARS = 1024

    /**
     * The line without its "\n" (and a trailing "\r"), or null at end of stream before any text.
     * Throws [IOException] past [maxChars], [SocketTimeoutException] past [deadlineMs].
     */
    fun read(
        reader: Reader,
        deadlineMs: Long,
        maxChars: Int = MAX_CHARS,
        now: () -> Long = System::currentTimeMillis,
    ): String? {
        val sb = StringBuilder()
        while (true) {
            if (now() > deadlineMs) throw SocketTimeoutException("reply took too long")
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString().removeSuffix("\r")
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            if (sb.length >= maxChars) throw IOException("reply longer than $maxChars characters")
            sb.append(c.toChar())
        }
    }
}
