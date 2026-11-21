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
        val submode = speedOf(ctx, d)
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

    // ---------------------------------------------------------------- speed

    /** JS8 speeds, slowest first: submode, name, frame length (s), rough weakest signal copied (dB). */
    data class Speed(val submode: Int, val label: Int, val frameS: Int, val weakestDb: Int)

    val SPEEDS = listOf(
        Speed(JS8EngineService.SUBMODE_SLOW, R.string.tx_speed_slow, 30, -28),
        Speed(JS8EngineService.SUBMODE_NORMAL, R.string.tx_speed_normal, 15, -24),
        Speed(JS8EngineService.SUBMODE_FAST, R.string.tx_speed_fast, 10, -20),
        Speed(JS8EngineService.SUBMODE_TURBO, R.string.tx_speed_turbo, 6, -18),
    )

    /** The speed [d] goes out at: its own, else the one set on Traffic. */
    fun speedOf(ctx: Context, d: ReportDrafts.Draft): Int = d.speed ?: TxOffsets.submode(ctx)

    fun speedName(ctx: Context, submode: Int): String =
        ctx.getString(SPEEDS.firstOrNull { it.submode == submode }?.label ?: R.string.tx_speed_normal)

    /**
     * Speed picker chips. [selected] null = "use the Traffic speed" (offered when [allowTraffic]).
     * Faster means less time keyed up but needs a stronger signal at the other end; nothing is blocked.
     */
    fun speedChips(ctx: Context, selected: Int?, allowTraffic: Boolean, onPick: (Int?) -> Unit): com.google.android.material.chip.ChipGroup {
        val group = com.google.android.material.chip.ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        fun chip(label: String, value: Int?) = com.google.android.material.chip.Chip(ctx).apply {
            text = label
            isCheckable = true
            isChecked = value == selected
            id = android.view.View.generateViewId()
            setOnClickListener { onPick(value) }
        }
        if (allowTraffic) group.addView(chip(ctx.getString(R.string.reports_speed_traffic, speedName(ctx, TxOffsets.submode(ctx))), null))
        SPEEDS.forEach { sp -> group.addView(chip(ctx.getString(sp.label), sp.submode)) }
        return group
    }

    /** One line on what the chosen speed means on air. */
    fun speedNote(ctx: Context, submode: Int): String {
        val sp = SPEEDS.firstOrNull { it.submode == submode } ?: SPEEDS[1]
        return ctx.getString(R.string.reports_speed_note, sp.frameS, sp.weakestDb)
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

    /** Confirm (who, how long, at what speed), then queue. Two taps from the list; nothing keys up by accident. */
    fun confirmSend(f: Fragment, draft: ReportDrafts.Draft, onQueued: () -> Unit = {}) {
        val ctx = f.requireContext()
        if (draft.isEmpty) {
            Snackbar.make(f.requireView(), R.string.reports_nothing_to_send, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (!f.readyToSend()) return
        val to = draft.to.ifBlank { ctx.getString(R.string.reports_everyone) }.uppercase()
        var pick = speedOf(ctx, draft)
        val dp = ctx.resources.displayMetrics.density
        val info = android.widget.TextView(ctx).apply { textSize = 15f }
        val note = android.widget.TextView(ctx).apply { textSize = 12.5f; alpha = 0.75f; setPadding(0, (4 * dp).toInt(), 0, 0) }
        fun render() {
            val d = draft.copy(speed = pick)
            info.text = buildString {
                append(ctx.getString(R.string.reports_send_to, to)).append("\n")
                append(if (isCounted(ctx, d)) cachedAirtime(ctx, d)?.label().orEmpty() else ctx.getString(R.string.reports_counting))
                if (!JS8EngineService.engineRunning) append("\n\n").append(ctx.getString(R.string.reports_send_waits))
            }
            note.text = speedNote(ctx, pick)
            if (!isCounted(ctx, d)) airtimeAsync(ctx, d) { if (pick == d.speed) render() }
        }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(info)
            addView(android.widget.TextView(ctx).apply {
                text = ctx.getString(R.string.reports_speed_label); textSize = 12f; letterSpacing = 0.06f
                setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt())
            })
            addView(speedChips(ctx, pick, allowTraffic = false) { v -> pick = v ?: pick; render() })
            addView(note)
        }
        render()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(draft.displayTitle())
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.reports_send) { _, _ ->
                val tx = TransmitViewModel.of(ctx)
                if (draft.to.isBlank()) tx.queueManualMessage(draft.onAir, pick) else tx.queueThreadMessage(draft.to.uppercase(), draft.onAir, pick)
                // The speed picked here is remembered for this report (and its automatic sends).
                ReportStore.of(ctx).put((ReportStore.of(ctx).get(draft.id) ?: draft)
                    .copy(speed = pick, lastSentMs = System.currentTimeMillis(), lastResult = "queued"))
                f.view?.let { Snackbar.make(it, ctx.getString(R.string.reports_queued_at, to, speedName(ctx, pick)), Snackbar.LENGTH_SHORT).show() }
                onQueued()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
