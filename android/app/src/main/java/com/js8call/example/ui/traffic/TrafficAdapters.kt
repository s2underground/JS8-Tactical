package com.js8call.example.ui.traffic

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.js8call.example.R
import com.js8call.example.reports.Reports
import com.js8call.example.rollup.RollupEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val zulu = SimpleDateFormat("HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
internal fun zuluTime(ms: Long): String = zulu.format(Date(ms))

internal fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return ""
    val s = (now - ms) / 1000
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}

/** Groups, "All traffic" and stations in the left column. */
class StationAdapter(
    private val onLongClick: (TrafficViewModel.StationRow) -> Boolean = { false },
    private val onClick: (TrafficViewModel.StationRow) -> Unit,
) : ListAdapter<TrafficViewModel.StationRow, StationAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.station_dot)
        val label: TextView = view.findViewById(R.id.station_label)
        val detail: TextView = view.findViewById(R.id.station_detail)
        val last: TextView = view.findViewById(R.id.station_last)
        val unread: TextView = view.findViewById(R.id.station_unread)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val row = getItem(position)
        val ctx = h.itemView.context
        h.dot.backgroundTintList = ColorStateList.valueOf(row.color)
        h.dot.isVisible = !row.isGroup
        h.label.text = row.label
        h.detail.text = row.detail
        h.last.text = if (row.receiving) ctx.getString(R.string.traffic_receiving_short) else ago(row.lastHeardMs)
        h.last.setTextColor(
            ContextCompat.getColor(ctx, if (row.receiving || h.last.text == "now") R.color.tac_accent else R.color.tac_text_dim)
        )
        h.unread.isVisible = row.unread > 0 && !row.selected
        h.unread.text = row.unread.toString()
        h.itemView.isSelected = row.selected
        h.itemView.setBackgroundResource(if (row.selected) R.drawable.bg_station_selected else R.drawable.bg_station_row)
        h.itemView.setOnClickListener { onClick(row) }
        h.itemView.setOnLongClickListener { onLongClick(row) }
        h.itemView.contentDescription = listOf(row.label, row.detail, h.last.text).joinToString(", ")
    }

    private object Diff : DiffUtil.ItemCallback<TrafficViewModel.StationRow>() {
        override fun areItemsTheSame(a: TrafficViewModel.StationRow, b: TrafficViewModel.StationRow) = a.key == b.key
        override fun areContentsTheSame(a: TrafficViewModel.StationRow, b: TrafficViewModel.StationRow) = a == b
    }
}

/** Rolled-up message cards for the selected station, group or all traffic. */
class ThreadAdapter(
    private val showSender: () -> Boolean,
    private val onRequestResend: (sender: String) -> Unit,
    private val onShowRaw: (TrafficViewModel.ThreadItem.Incoming) -> Unit,
) : ListAdapter<TrafficViewModel.ThreadItem, RecyclerView.ViewHolder>(Diff) {

    class InHolder(view: View) : RecyclerView.ViewHolder(view) {
        val from: TextView = view.findViewById(R.id.card_from)
        val to: TextView = view.findViewById(R.id.card_to)
        val copies: TextView = view.findViewById(R.id.card_copies)
        val status: TextView = view.findViewById(R.id.card_status)
        val time: TextView = view.findViewById(R.id.card_time)
        val body: TextView = view.findViewById(R.id.card_body)
        val meta: TextView = view.findViewById(R.id.card_meta)
        val actions: View = view.findViewById(R.id.card_actions)
        val reportBadge: TextView = view.findViewById(R.id.card_report_badge)
        val reportFields: android.widget.GridLayout = view.findViewById(R.id.card_report_fields)
        val resend: MaterialButton = view.findViewById(R.id.card_resend)
    }

    class OutHolder(view: View) : RecyclerView.ViewHolder(view) {
        val to: TextView = view.findViewById(R.id.out_to)
        val time: TextView = view.findViewById(R.id.out_time)
        val body: TextView = view.findViewById(R.id.out_body)
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position) is TrafficViewModel.ThreadItem.Incoming) IN else OUT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == IN) InHolder(inflater.inflate(R.layout.item_thread_incoming, parent, false))
        else OutHolder(inflater.inflate(R.layout.item_thread_outgoing, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TrafficViewModel.ThreadItem.Incoming -> bindIn(holder as InHolder, item)
            is TrafficViewModel.ThreadItem.Outgoing -> bindOut(holder as OutHolder, item)
        }
    }

    private fun bindIn(h: InHolder, item: TrafficViewModel.ThreadItem.Incoming) {
        val m = item.message
        val ctx = h.itemView.context
        val sender = m.sender
        h.from.isVisible = showSender() || sender == null
        h.from.text = sender ?: ctx.getString(R.string.traffic_unknown_sender, m.laneHz.toInt())
        h.from.setTextColor(item.color)
        h.to.isVisible = m.recipient != null
        val forMe = TrafficViewModel.isMyCall(ctx, m.recipient)
        if (forMe) {
            // Addressed to this station: make it impossible to miss.
            h.to.text = ctx.getString(R.string.traffic_to_you)
            h.to.setTextColor(ContextCompat.getColor(ctx, R.color.tac_ground))
            h.to.setBackgroundResource(R.drawable.bg_pill_tx)
            val p = (6 * ctx.resources.displayMetrics.density).toInt()
            h.to.setPadding(p, p / 3, p, p / 3)
        } else {
            h.to.text = ctx.getString(R.string.traffic_to, m.recipient ?: "")
            h.to.setTextColor(ContextCompat.getColor(ctx, R.color.tac_accent))
            h.to.background = null
            h.to.setPadding(0, 0, 0, 0)
        }
        h.copies.isVisible = m.copies > 1
        h.copies.text = ctx.getString(R.string.traffic_copies, m.copies)

        when {
            m.isComplete -> {
                h.status.text = ctx.getString(R.string.traffic_complete)
                h.status.setTextColor(ContextCompat.getColor(ctx, R.color.tac_good_text))
                h.status.setBackgroundResource(R.drawable.bg_chip_good)
            }
            else -> {
                h.status.text = when {
                    m.missingFrames > 0 && !m.tailMissing && !m.headMissing ->
                        ctx.resources.getQuantityString(R.plurals.traffic_frames_missing, m.missingFrames, m.missingFrames)
                    m.headMissing -> ctx.getString(R.string.traffic_start_missing)
                    else -> ctx.getString(R.string.traffic_end_missing)
                }
                h.status.setTextColor(ContextCompat.getColor(ctx, R.color.tac_warn_text))
                h.status.setBackgroundResource(R.drawable.bg_chip_warn)
            }
        }
        h.time.text = zuluTime(m.lastHeardMs)
        h.body.text = bodySpan(ctx, m.segments)
        val report = Reports.parse(m.bodyText)
        h.reportBadge.isVisible = report != null
        h.reportBadge.text = report?.type?.tag
        // A complete report shows as fields; an incomplete one keeps its text so the gap is visible.
        val asFields = report != null && m.isComplete && report.values.isNotEmpty()
        h.reportFields.isVisible = asFields
        h.body.isVisible = !asFields
        if (asFields) bindReportFields(h.reportFields, report)
        val snr = m.bestSnr?.let { " · $it dB" } ?: ""
        h.meta.text = if (m.copies > 1)
            ctx.getString(R.string.traffic_meta_multi, zuluTime(m.firstHeardMs), zuluTime(m.lastHeardMs), m.laneHz.toInt(), snr)
        else ctx.getString(R.string.traffic_meta_single, m.laneHz.toInt(), snr)
        h.itemView.setBackgroundResource(if (m.isComplete) R.drawable.bg_card else R.drawable.bg_card_warn)
        h.actions.isVisible = !m.isComplete && sender != null
        h.resend.setOnClickListener { sender?.let(onRequestResend) }
        h.itemView.setOnLongClickListener { onShowRaw(item); true }
    }

    private fun bindOut(h: OutHolder, item: TrafficViewModel.ThreadItem.Outgoing) {
        val ctx = h.itemView.context
        h.to.text = if (item.to != null) ctx.getString(R.string.traffic_you_to, item.to) else ctx.getString(R.string.traffic_you)
        h.time.text = zuluTime(item.sentMs)
        h.body.text = item.text
    }

    private fun bindReportFields(grid: android.widget.GridLayout, report: Reports.Report) {
        val ctx = grid.context
        grid.removeAllViews()
        val dp = ctx.resources.displayMetrics.density
        report.type.fields.forEach { field ->
            val value = report.values[field.key] ?: return@forEach
            val cell = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, (4 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            }
            cell.addView(TextView(ctx).apply {
                text = field.label.uppercase()
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.tac_text_dim))
            })
            cell.addView(TextView(ctx).apply {
                text = value
                textSize = 15f
                setTextColor(ContextCompat.getColor(ctx, R.color.tac_text))
                if (field.kind == Reports.Kind.LOCATION || field.kind == Reports.Kind.TIME || field.kind == Reports.Kind.FREQUENCY) typeface = Typeface.MONOSPACE
            })
            grid.addView(cell, android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
            ).apply { width = 0 })
        }
    }

    private fun bodySpan(ctx: android.content.Context, segments: List<RollupEngine.Segment>): CharSequence {
        val sb = SpannableStringBuilder()
        val warn = ContextCompat.getColor(ctx, R.color.tac_warn_text)
        val warnBg = ContextCompat.getColor(ctx, R.color.tac_warn_tint)
        segments.forEachIndexed { i, seg ->
            when (seg) {
                is RollupEngine.Segment.Text -> sb.append(if (i == 0) seg.text.trimStart() else seg.text)
                is RollupEngine.Segment.Gap -> {
                    val label = when (val n = seg.count) {
                        null -> ctx.getString(R.string.traffic_gap_unknown)
                        1 -> ctx.getString(R.string.traffic_gap_one)
                        else -> ctx.getString(R.string.traffic_gap_many, n)
                    }
                    if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
                    val start = sb.length
                    sb.append(" ").append(label).append(" ")
                    sb.setSpan(ForegroundColorSpan(warn), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(warnBg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (i < segments.lastIndex) sb.append(' ')
                }
            }
        }
        return sb
    }

    private object Diff : DiffUtil.ItemCallback<TrafficViewModel.ThreadItem>() {
        override fun areItemsTheSame(a: TrafficViewModel.ThreadItem, b: TrafficViewModel.ThreadItem): Boolean = when {
            a is TrafficViewModel.ThreadItem.Incoming && b is TrafficViewModel.ThreadItem.Incoming -> a.message.id == b.message.id
            a is TrafficViewModel.ThreadItem.Outgoing && b is TrafficViewModel.ThreadItem.Outgoing -> a.sentMs == b.sentMs
            else -> false
        }
        // Rolled messages are mutable (copies merge in), so always rebind.
        override fun areContentsTheSame(a: TrafficViewModel.ThreadItem, b: TrafficViewModel.ThreadItem) = false
    }

    private companion object {
        const val IN = 1
        const val OUT = 2
    }
}
