package com.js8call.example.service

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A log of the radio's USB sound card dropping off, for the diagnostic report.
 *
 * Each entry says how long after the last send it happened. Dropouts that keep
 * landing within a few seconds of transmitting point at RF getting into the USB
 * cable (fix: less power, ferrite chokes, a choke on the feedline); dropouts
 * with no send nearby point at the cable, connector or power instead.
 */
object UsbDropouts {
    private const val FILE = "usb-dropouts.txt"
    private const val MAX_BYTES = 32 * 1024
    /** A dropout this soon after a send counts as "right after transmitting". */
    const val NEAR_TX_MS = 10_000L

    data class Event(val atMs: Long, val sinceTxMs: Long, val duringTx: Boolean)

    private val events = ArrayList<Event>()
    private var rfHintShownMs = 0L

    @Synchronized
    fun record(ctx: Context, device: String, nowMs: Long, lastTxMs: Long, duringTx: Boolean) {
        val since = if (lastTxMs > 0) nowMs - lastTxMs else -1L
        events += Event(nowMs, since, duringTx)
        if (events.size > 50) events.removeAt(0)
        val sinceText = when {
            duringTx -> "while transmitting"
            since < 0 -> "no send yet this session"
            else -> "${since / 1000} s after the last send"
        }
        append(ctx, "${stamp(nowMs)}  $device removed · $sinceText")
    }

    @Synchronized
    fun recordRecovery(ctx: Context, nowMs: Long) {
        append(ctx, "${stamp(nowMs)}  came back · receiver restarted automatically")
    }

    /**
     * A one-time hint (per 30 min) when the pattern says RF: three or more dropouts in
     * 30 minutes, most of them during or just after a send. A prompt only.
     */
    @Synchronized
    fun rfHint(ctx: Context, nowMs: Long): String? {
        if (nowMs - rfHintShownMs < 30 * 60_000L) return null
        val (count, nearTx) = rfPattern(events, nowMs) ?: return null
        rfHintShownMs = nowMs
        return "The radio's USB connection has dropped $count times in the last 30 minutes, " +
            "$nearTx of them while or just after transmitting. That usually means RF is getting into the USB " +
            "cable. Try lower power, snap-on ferrite chokes on the USB and audio cables, and a choke on the coax."
    }

    /**
     * Dropouts in the last 30 minutes and how many were during or just after a send,
     * when that looks like RF: at least 3, and at least two thirds near a send.
     */
    fun rfPattern(events: List<Event>, nowMs: Long): Pair<Int, Int>? {
        val recent = events.filter { nowMs - it.atMs in 0..30 * 60_000L }
        if (recent.size < 3) return null
        val nearTx = recent.count { it.duringTx || (it.sinceTxMs in 0..NEAR_TX_MS) }
        if (nearTx * 3 < recent.size * 2) return null
        return recent.size to nearTx
    }

    /** The log for the diagnostic report. */
    fun text(ctx: Context): String =
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText().orEmpty()

    private fun append(ctx: Context, line: String) {
        runCatching {
            val f = File(ctx.filesDir, FILE)
            f.appendText(line + "\n")
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2))
        }
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
