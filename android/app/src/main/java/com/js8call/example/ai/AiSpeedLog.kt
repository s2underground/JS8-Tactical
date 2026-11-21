package com.js8call.example.ai

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The last few AI speed tests, kept for Copy and for the diagnostics report. */
object AiSpeedLog {
    private const val FILE = "ai_speed_tests.txt"
    private const val MAX_BYTES = 24_000

    fun append(ctx: Context, entry: String) {
        runCatching {
            val f = File(ctx.filesDir, FILE)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val all = (if (f.exists()) f.readText() else "") + "===== $stamp\n" + entry.trim() + "\n\n"
            f.writeText(if (all.length > MAX_BYTES) all.takeLast(MAX_BYTES).substringAfter("===== ").let { "===== $it" } else all)
        }
    }

    fun text(ctx: Context): String = runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")
}
