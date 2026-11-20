package com.js8call.example.ui.traffic

import android.content.Context
import java.io.File

/**
 * Where our own transmissions were heard back by our receiver, against where they
 * were sent (own-echo.txt). Included in the diagnostic report.
 */
object OwnEchoLog {
    private const val FILE = "own-echo.txt"
    private const val MAX_BYTES = 24_000

    fun append(ctx: Context, line: String) {
        runCatching {
            val f = File(ctx.filesDir, FILE)
            f.appendText(line + "\n")
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2))
        }
    }

    fun text(ctx: Context): String =
        runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")
}
