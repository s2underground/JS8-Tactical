package com.js8call.example.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.js8call.example.R
import com.js8call.example.interference.Interference
import com.js8call.example.interference.InterferenceDetector.Assessment
import com.js8call.example.interference.InterferenceDetector.Kind
import com.js8call.example.interference.InterferenceDetector.Level
import com.js8call.example.reports.Reports
import com.js8call.example.service.TxOffsets
import java.util.Locale

/** How the interference watch shows up on screen: a status pill, a warning over the compose bar, a details sheet. */
object InterferenceUi {

    private fun db(v: Float) = String.format(Locale.US, "%+.0f", v)

    /** Short description of what was measured ("noise +14 dB, usual stations silent"). */
    fun short(ctx: Context, a: Assessment): String = buildList {
        if (Kind.NOISE in a.kinds) a.noiseAboveDb?.let { add(ctx.getString(R.string.interference_short_noise, db(it))) }
        if (Kind.CARRIER in a.kinds) add(ctx.getString(R.string.interference_short_carrier, a.carriersHz.joinToString("/")))
        if (Kind.SWEEP in a.kinds) add(ctx.getString(R.string.interference_short_sweep))
        if (Kind.PULSE in a.kinds) add(ctx.getString(R.string.interference_short_pulse))
        if (a.decodesQuiet) add(ctx.getString(R.string.interference_short_quiet))
        else a.snrDropDb?.let { add(ctx.getString(R.string.interference_short_weaker, String.format(Locale.US, "%.0f", it))) }
    }.joinToString(", ")

    /** The full explanation for the details sheet. */
    fun details(ctx: Context, a: Assessment): String = buildString {
        append(ctx.getString(R.string.interference_on_dial, Reports.khz(a.dialHz)))
        append("\n\n")
        a.noiseAboveDb?.takeIf { Kind.NOISE in a.kinds }?.let { append("• ").append(ctx.getString(R.string.interference_detail_noise, db(it))).append('\n') }
        if (Kind.CARRIER in a.kinds) append("• ").append(ctx.getString(R.string.interference_detail_carrier, a.carriersHz.joinToString(", "))).append('\n')
        if (Kind.SWEEP in a.kinds) append("• ").append(ctx.getString(R.string.interference_detail_sweep)).append('\n')
        if (Kind.PULSE in a.kinds) append("• ").append(ctx.getString(R.string.interference_detail_pulse)).append('\n')
        if (a.decodesQuiet) append("• ").append(ctx.getString(R.string.interference_detail_quiet)).append('\n')
        a.snrDropDb?.let { append("• ").append(ctx.getString(R.string.interference_detail_weaker, String.format(Locale.US, "%.0f", it))).append('\n') }
        if (a.humHz.isNotEmpty()) append("• ").append(ctx.getString(R.string.interference_detail_hum, a.humHz.joinToString(", "))).append('\n')
        if (a.residentCarriersHz.isNotEmpty()) append("• ").append(ctx.getString(R.string.interference_detail_resident, a.residentCarriersHz.joinToString(", "))).append('\n')
        a.sinceMs?.let { append('\n').append(ctx.getString(R.string.interference_since, Reports.dtg(it))).append('\n') }
        if (a.level == Level.POSSIBLE) {
            val note = if (Kind.NOISE in a.kinds) R.string.interference_possible_note else R.string.interference_possible_note_carrier
            append('\n').append(ctx.getString(note)).append('\n')
        }
        if (!a.baselineReady) append('\n').append(ctx.getString(R.string.interference_learning)).append('\n')
        append('\n').append(ctx.getString(R.string.interference_your_call))
    }

    /** A #JAM report filled in from what was measured (the operator reviews it before sending). */
    fun jamPrefill(a: Assessment): Map<String, String> = buildMap {
        put("F", Reports.khz(a.dialHz))
        val kind = listOf(Kind.CARRIER, Kind.SWEEP, Kind.PULSE, Kind.NOISE).firstOrNull { it in a.kinds }
        kind?.let { put("Y", it.name) }
        a.noiseAboveDb?.takeIf { Kind.NOISE in a.kinds }?.let { put("S", String.format(Locale.US, "%.0fDB OVER NORMAL", it)) }
        put("E", if (a.decodesQuiet) "DENIED" else "DEGRADED")
        put("T", Reports.dtg())
        if (Kind.CARRIER in a.kinds) put("N", "CARRIER " + a.carriersHz.joinToString(" ") { "${it}HZ" })
    }

    /** Should the compose bar warn before sending: traffic is hurt here, or a carrier sits on our offset. */
    fun warning(ctx: Context, a: Assessment?): String? {
        if (a == null || a.level == Level.NONE || !Interference.enabled(ctx) || !Interference.warnBeforeTx(ctx)) return null
        val tx = TxOffsets.txOffset(ctx)
        return when {
            a.affectsTraffic -> ctx.getString(R.string.interference_warn_traffic, short(ctx, a))
            a.carrierNear(tx) -> ctx.getString(R.string.interference_warn_offset, tx.toInt())
            else -> null
        }
    }

    fun showDetails(f: Fragment, a: Assessment, onReport: (() -> Unit)?) {
        val ctx = f.requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(
                when {
                    a.level == Level.LIKELY -> R.string.interference_title
                    Kind.NOISE in a.kinds -> R.string.interference_title_possible
                    else -> R.string.interference_title_possible_carrier
                }
            )
            .setMessage(details(ctx, a))
            .apply { if (onReport != null) setPositiveButton(R.string.interference_report) { _, _ -> onReport() } }
            .setNegativeButton(R.string.interference_close, null)
            .show()
    }

    /** The warning line above a compose bar. Tapping it opens the details. */
    fun bindBanner(f: Fragment, root: View, onReport: (() -> Unit)? = null) {
        val banner = root.findViewById<TextView>(R.id.interference_banner) ?: return
        Interference.state.observe(f.viewLifecycleOwner) { a ->
            val text = f.context?.let { warning(it, a) }
            banner.isVisible = text != null
            banner.text = text
            banner.setOnClickListener { if (a != null) showDetails(f, a, onReport) }
        }
    }

    /** The status pill on Traffic: hidden while things are normal. */
    fun bindPill(f: Fragment, pill: TextView, onReport: (() -> Unit)?) {
        Interference.state.observe(f.viewLifecycleOwner) { a ->
            val ctx = f.context ?: return@observe
            val show = a != null && a.level != Level.NONE && Interference.enabled(ctx)
            pill.isVisible = show
            if (!show) return@observe
            val likely = a.level == Level.LIKELY
            pill.text = ctx.getString(
                when {
                    likely -> R.string.interference_pill
                    Kind.NOISE in a.kinds -> R.string.interference_pill_possible
                    else -> R.string.interference_pill_possible_carrier
                }
            )
            pill.setBackgroundResource(if (likely) R.drawable.bg_pill_warn else R.drawable.bg_pill_off)
            pill.setTextColor(ContextCompat.getColor(ctx, if (likely) R.color.tac_ground else R.color.tac_text_dim))
            pill.setOnClickListener { showDetails(f, a, onReport) }
        }
    }
}
