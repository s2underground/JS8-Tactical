package com.js8call.example.util

/**
 * The end of [text], at most [maxChars] long, starting on a whole line: a plain takeLast could
 * begin mid-line (a diagnostics report showed "very send at 1987 Hz" as its first line).
 */
fun tailLines(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val tail = text.takeLast(maxChars)
    // The cut landed exactly on a line start when the character before it ends a line.
    if (text[text.length - maxChars - 1] == '\n') return tail
    val nl = tail.indexOf('\n')
    return if (nl < 0) tail else tail.substring(nl + 1)
}
