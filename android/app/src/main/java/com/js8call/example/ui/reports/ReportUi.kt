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

    fun airtime(ctx: Context, d: ReportDrafts.Draft): Airtime? {
        val text = d.onAir
        if (text.isBlank()) return null
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val submode = TxOffsets.submode(ctx)
        val sent = if (d.to.isBlank()) text else "${d.to.uppercase()} $text"
        val frames = runCatching {
            JS8Engine.countFrames(
                prefs.getString("callsign", "").orEmpty().uppercase(),
                prefs.getString("grid", "").orEmpty().uppercase(), "", sent, submode,
            )
        }.getOrDefault(-1)
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
        val air = airtime(ctx, draft)
        val to = draft.to.ifBlank { ctx.getString(R.string.reports_everyone) }.uppercase()
        val msg = buildString {
            append(ctx.getString(R.string.reports_send_to, to))
            air?.let { append("\n").append(it.label()) }
            if (!JS8EngineService.engineRunning) append("\n\n").append(ctx.getString(R.string.reports_send_waits))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(draft.displayTitle())
            .setMessage(msg)
            .setPositiveButton(R.string.reports_send) { _, _ ->
                val tx = TransmitViewModel.of(ctx)
                if (draft.to.isBlank()) tx.queueManualMessage(draft.onAir) else tx.queueThreadMessage(draft.to.uppercase(), draft.onAir)
                ReportStore.of(ctx).put(draft.copy(lastSentMs = System.currentTimeMillis(), lastResult = "queued"))
                f.view?.let { Snackbar.make(it, ctx.getString(R.string.reports_queued, to), Snackbar.LENGTH_SHORT).show() }
                onQueued()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
