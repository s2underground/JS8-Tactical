package com.js8call.example.ui.reports

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.js8call.core.JS8Engine
import com.js8call.example.R
import com.js8call.example.reports.ReportDrafts
import com.js8call.example.reports.ReportStore
import com.js8call.example.service.JS8EngineService
import com.js8call.example.service.TxOffsets
import com.js8call.example.ui.TransmitViewModel
import com.js8call.example.ui.readyToSend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Airtime, time labels and the send action shared by the Reports list and editor. */
object ReportUi {

    data class Airtime(val frames: Int, val seconds: Int, val speed: String) {
        fun label(): String = "$frames frame${if (frames == 1) "" else "s"} · ${seconds / 60}:${"%02d".format(seconds % 60)} at $speed"
    }

    /**
     * Counting frames runs the full JS8 encoder over the text: several seconds for a long report.
     * It is never done on the main thread. Results are cached by exactly what is counted, and the
     * latest request per screen wins, so typing never waits for it.
     */
    private val cache = android.util.LruCache<String, Int>(64)
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "frame-count").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val waiting = HashMap<String, MutableList<(Airtime?) -> Unit>>()

    private data class Job(val key: String, val call: String, val grid: String, val sent: String, val submode: Int)

    private fun job(ctx: Context, d: ReportDrafts.Draft): Job? {
        val text = d.onAir
        if (text.isBlank()) return null
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val submode = TxOffsets.submode(ctx)
        val sent = if (d.to.isBlank()) text else "${d.to.uppercase()} $text"
        val call = prefs.getString("callsign", "").orEmpty().uppercase()
        val grid = prefs.getString("grid", "").orEmpty().uppercase()
        return Job("$call|$grid|$submode|$sent", call, grid, sent, submode)
    }

    private fun toAirtime(frames: Int, submode: Int): Airtime? {
        if (frames <= 0) return null
        val frameS = when (submode) {
            JS8EngineService.SUBMODE_SLOW -> 30
            JS8EngineService.SUBMODE_FAST -> 10
            JS8EngineService.SUBMODE_TURBO -> 6
            else -> 15
        }
        val speed = when (submode) {
            JS8EngineService.SUBMODE_SLOW -> "Slow"
            JS8EngineService.SUBMODE_FAST -> "Fast"
            JS8EngineService.SUBMODE_TURBO -> "Turbo"
            else -> "Normal"
        }
        return Airtime(frames, frames * frameS, speed)
    }

    /** Airtime if already counted, else null (and nothing is computed). */
    fun cachedAirtime(ctx: Context, d: ReportDrafts.Draft): Airtime? =
        job(ctx, d)?.let { j -> cache.get(j.key)?.let { toAirtime(it, j.submode) } }

    /** True when [d]'s airtime has been counted (so [cachedAirtime] is the answer, null or not). */
    fun isCounted(ctx: Context, d: ReportDrafts.Draft): Boolean = job(ctx, d)?.let { cache.get(it.key) != null } ?: true

    /** Count in the background; [onReady] runs on the main thread (at once if cached). */
    fun airtimeAsync(ctx: Context, d: ReportDrafts.Draft, onReady: (Airtime?) -> Unit) {
        val j = job(ctx, d) ?: return onReady(null)
        cache.get(j.key)?.let { return onReady(toAirtime(it, j.submode)) }
        synchronized(waiting) {
            waiting[j.key]?.let { it += onReady; return }
            waiting[j.key] = mutableListOf(onReady)
        }
        worker.execute {
            val frames = runCatching { JS8Engine.countFrames(j.call, j.grid, "", j.sent, j.submode) }.getOrDefault(-1)
            cache.put(j.key, frames)
            val cbs = synchronized(waiting) { waiting.remove(j.key).orEmpty() }
            main.post { cbs.forEach { it(toAirtime(frames, j.submode)) } }
        }
    }

    fun zulu(ms: Long): String = SimpleDateFormat("EEE dd MMM HHmm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    fun local(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    fun whenLabel(ms: Long): String = "${zulu(ms)} (${local(ms)} local)"

    fun repeatLabel(min: Int): String = when {
        min <= 0 -> "once"
        min % 1440 == 0 -> if (min == 1440) "daily" else "every ${min / 1440} days"
        min % 60 == 0 -> "every ${min / 60} h"
        else -> "every $min min"
    }

    fun scheduleLine(s: ReportDrafts.Schedule): String = "Auto-send ${whenLabel(s.atMs)}, ${repeatLabel(s.repeatMin)}"

    fun lastLine(d: ReportDrafts.Draft): String? {
        val at = d.lastSentMs ?: return null
        return when (d.lastResult) {
            "missed" -> "Missed ${zulu(at)}: the receiver was off"
            "auto" -> "Queued automatically ${zulu(at)}"
            else -> "Queued ${zulu(at)}"
        }
    }

    /** Confirm (who, how long), then queue. Two taps from the list; nothing keys up by accident. */
    fun confirmSend(f: Fragment, draft: ReportDrafts.Draft, onQueued: () -> Unit = {}) {
        val ctx = f.requireContext()
        if (draft.isEmpty) {
            Snackbar.make(f.requireView(), R.string.reports_nothing_to_send, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (!f.readyToSend()) return
        val to = draft.to.ifBlank { ctx.getString(R.string.reports_everyone) }.uppercase()
        fun msg(air: String?) = buildString {
            append(ctx.getString(R.string.reports_send_to, to))
            air?.let { append("\n").append(it) }
            if (!JS8EngineService.engineRunning) append("\n\n").append(ctx.getString(R.string.reports_send_waits))
        }
        val counted = isCounted(ctx, draft)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(draft.displayTitle())
            .setMessage(msg(if (counted) cachedAirtime(ctx, draft)?.label() else ctx.getString(R.string.reports_counting)))
            .setPositiveButton(R.string.reports_send) { _, _ ->
                val tx = TransmitViewModel.of(ctx)
                if (draft.to.isBlank()) tx.queueManualMessage(draft.onAir) else tx.queueThreadMessage(draft.to.uppercase(), draft.onAir)
                ReportStore.of(ctx).put(draft.copy(lastSentMs = System.currentTimeMillis(), lastResult = "queued"))
                f.view?.let { Snackbar.make(it, ctx.getString(R.string.reports_queued, to), Snackbar.LENGTH_SHORT).show() }
                onQueued()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        if (!counted) airtimeAsync(ctx, draft) { air -> if (dialog.isShowing) dialog.setMessage(msg(air?.label())) }
    }
}
