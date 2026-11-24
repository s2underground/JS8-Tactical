package com.js8call.example.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R
import com.js8call.example.reports.ReportDrafts
import com.js8call.example.reports.ReportStore
import com.js8call.example.storeforward.Held
import com.js8call.example.storeforward.HeldStore
import com.js8call.example.ui.reports.ReportUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Inbox lists and dialogs for store-and-forward (see [Held]). */
object StoreForwardUi {

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun color(ctx: Context, id: Int) = ContextCompat.getColor(ctx, id)

    private val utc = SimpleDateFormat("HH:mm'Z' d MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun whenText(ms: Long): String = utc.format(Date(ms))

    private fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 10).toFloat(); setColor(color(ctx, R.color.tac_card)); setStroke(dp(ctx, 1), color(ctx, R.color.tac_border))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 10) }
    }

    private fun text(ctx: Context, s: CharSequence, size: Float, c: Int = R.color.tac_text, bold: Boolean = false, max: Int = 0) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color(ctx, c))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (max > 0) { maxLines = max; ellipsize = android.text.TextUtils.TruncateAt.END }
    }

    private fun button(ctx: Context, label: String, outlined: Boolean = true, onClick: () -> Unit) =
        MaterialButton(ctx, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
        }

    private fun row(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    // ------------------------------------------------------------------ For me: messages waiting elsewhere

    /** Cards for messages other stations hold for us, each with a button that asks for it. */
    fun renderWaiting(f: Fragment, box: LinearLayout) {
        val ctx = f.requireContext()
        val store = HeldStore.of(ctx)
        box.removeAllViews()
        val waiting = store.waiting().sortedByDescending { it.seenMs }
        val head = row(ctx).apply { setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 8), dp(ctx, 4)) }
        head.addView(text(ctx, ctx.getString(R.string.sf_waiting_title), 13f, R.color.tac_text_dim, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(button(ctx, ctx.getString(R.string.sf_ask), outlined = false) { askForMessages(f) })
        box.addView(head)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0) }
        if (waiting.isEmpty()) {
            list.addView(text(ctx, ctx.getString(R.string.sf_waiting_none), 12.5f, R.color.tac_text_quiet).apply { setPadding(0, 0, 0, dp(ctx, 8)) })
        }
        waiting.forEach { w ->
            val c = card(ctx)
            val r = row(ctx)
            val label = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            label.addView(text(ctx, ctx.getString(R.string.sf_waiting_line, w.station, w.id), 15f, bold = true))
            label.addView(text(ctx, if (w.askedMs != null) ctx.getString(R.string.sf_waiting_asked, whenText(w.askedMs))
                else ctx.getString(R.string.sf_waiting_seen, whenText(w.seenMs)), 12.5f, R.color.tac_text_dim))
            r.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(button(ctx, ctx.getString(if (w.askedMs != null) R.string.sf_get_again else R.string.sf_get), outlined = false) {
                TransmitViewModel.of(ctx).queueThreadMessage(w.station, "QUERY MSG ${w.id}")
                store.markAsked(w.station, w.id, System.currentTimeMillis())
                f.view?.let { Snackbar.make(it, ctx.getString(R.string.sf_get_queued, w.id, w.station), Snackbar.LENGTH_SHORT).show() }
            })
            r.addView(button(ctx, "✕", outlined = false) { store.dismissWaiting(w.station, w.id) }.apply {
                contentDescription = ctx.getString(R.string.sf_dismiss); minWidth = 0; minimumWidth = 0
            })
            c.addView(r)
            list.addView(c)
        }
        box.addView(list)
    }

    /** Ask a station or a group whether it holds anything for us (QUERY MSGS). */
    fun askForMessages(f: Fragment) {
        val ctx = f.requireContext()
        val groups = PreferenceManager.getDefaultSharedPreferences(ctx).getString("my_groups", "").orEmpty()
            .split(',', ' ', ';').map { it.trim().uppercase(Locale.ROOT) }.filter { it.startsWith("@") }
        val input = EditText(ctx).apply {
            hint = ctx.getString(R.string.sf_ask_hint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            isSingleLine = true
        }
        val chips = ChipGroup(ctx).apply {
            (groups + "@ALLCALL").distinct().forEach { g -> addView(Chip(ctx).apply { text = g; setOnClickListener { input.setText(g) } }) }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 22), dp(ctx, 8), dp(ctx, 22), 0)
            addView(text(ctx, ctx.getString(R.string.sf_ask_body), 13.5f, R.color.tac_text_dim))
            addView(input); addView(chips)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.sf_ask_title)
            .setView(box)
            .setPositiveButton(R.string.sf_ask_send) { _, _ ->
                val to = input.text.toString().trim().uppercase(Locale.ROOT)
                if (to.isBlank()) return@setPositiveButton
                val tx = TransmitViewModel.of(ctx)
                if (to.startsWith("@")) tx.queueManualMessage("$to QUERY MSGS") else tx.queueThreadMessage(to, "QUERY MSGS")
                f.view?.let { Snackbar.make(it, ctx.getString(R.string.sf_ask_queued, to), Snackbar.LENGTH_SHORT).show() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ Holding / Posted lists

    fun renderHeld(f: Fragment, box: LinearLayout, kind: Held.Kind) {
        val ctx = f.requireContext()
        val store = HeldStore.of(ctx)
        val now = System.currentTimeMillis()
        store.prune(now)
        box.removeAllViews()
        box.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 24))
        val autoreply = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("autoreply_enabled", false)
        box.addView(text(ctx, ctx.getString(if (kind == Held.Kind.RELAY) R.string.sf_holding_help else R.string.sf_posted_help), 13f, R.color.tac_text_dim)
            .apply { setPadding(0, 0, 0, dp(ctx, 8)) })
        if (!autoreply || !store.serveEnabled) {
            box.addView(text(ctx, ctx.getString(R.string.sf_not_serving), 13f, R.color.tac_warn_text).apply { setPadding(0, 0, 0, dp(ctx, 10)) })
        }
        val items = store.all().filter { it.kind == kind }.sortedByDescending { it.receivedMs }
        if (items.isEmpty()) {
            box.addView(text(ctx, ctx.getString(if (kind == Held.Kind.RELAY) R.string.sf_holding_empty else R.string.sf_posted_empty), 14f, R.color.tac_text_quiet)
                .apply { setPadding(0, dp(ctx, 16), 0, 0) })
            return
        }
        items.forEach { h -> box.addView(heldCard(f, h, now)) }
    }

    private fun heldCard(f: Fragment, h: Held, now: Long): View {
        val ctx = f.requireContext()
        val store = HeldStore.of(ctx)
        val c = card(ctx)
        val gone = h.expired(now) || h.isGroup && h.kind == Held.Kind.RELAY && now - h.receivedMs > Held.GROUP_FLOOR_MS
        if (gone) c.alpha = 0.55f
        val title = if (h.kind == Held.Kind.RELAY) ctx.getString(R.string.sf_card_for_from, h.to, h.from) else ctx.getString(R.string.sf_card_posted_to, h.to)
        val r = row(ctx)
        r.addView(text(ctx, title, 15f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(text(ctx, "ID ${h.id}", 12f, R.color.tac_text_dim))
        c.addView(r)
        c.addView(text(ctx, h.text, 13.5f, max = 3).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 4)) })
        val status = buildList {
            add(ctx.getString(R.string.sf_card_received, whenText(h.receivedMs)))
            when {
                gone -> add(ctx.getString(R.string.sf_card_expired))
                h.expiresMs != null -> add(ctx.getString(R.string.sf_card_until, whenText(h.expiresMs)))
                h.isGroup && h.kind == Held.Kind.RELAY -> add(ctx.getString(R.string.sf_card_until, whenText(h.receivedMs + Held.GROUP_FLOOR_MS)))
            }
            add(when {
                !h.isGroup && h.delivered -> ctx.getString(R.string.sf_card_picked_up, h.lastSentMs?.let { whenText(it) } ?: "")
                h.isGroup && h.deliveredTo.isNotEmpty() -> ctx.getString(R.string.sf_card_sent_to, h.deliveredTo.size, h.deliveredTo.sorted().joinToString(" "))
                else -> ctx.getString(R.string.sf_card_not_yet)
            })
        }.joinToString(" · ")
        c.addView(text(ctx, status, 12f, if (!h.isGroup && h.delivered) R.color.tac_good_text else R.color.tac_text_dim))
        val actions = row(ctx).apply { gravity = Gravity.END }
        actions.addView(button(ctx, ctx.getString(R.string.sf_view), outlined = false) {
            MaterialAlertDialogBuilder(ctx).setTitle(title).setMessage(h.text).setPositiveButton(android.R.string.ok, null).show()
        })
        if (h.kind == Held.Kind.RELAY && !h.isGroup) {
            actions.addView(button(ctx, ctx.getString(R.string.sf_send_now), outlined = false) {
                TransmitViewModel.of(ctx).queueThreadMessage(h.to, "MSG ${h.text} FROM ${h.from}")
                store.markSent(h.id, h.to, System.currentTimeMillis())
                f.view?.let { Snackbar.make(it, ctx.getString(R.string.sf_send_queued, h.to), Snackbar.LENGTH_SHORT).show() }
            })
        }
        actions.addView(button(ctx, ctx.getString(R.string.sf_delete), outlined = false) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(ctx.getString(R.string.sf_delete_q, h.id))
                .setMessage(if (h.kind == Held.Kind.POSTED) R.string.sf_delete_posted_body else R.string.sf_delete_held_body)
                .setPositiveButton(R.string.sf_delete) { _, _ -> store.delete(h.id) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }.apply { setTextColor(0xFFFF6B6B.toInt()) })
        c.addView(actions)
        return c
    }

    // ------------------------------------------------------------------ Reports: post for pickup

    /** One line for a report editor: where (and until when) the report is posted, or null. */
    fun postedStatus(ctx: Context, reportId: String): String? {
        val h = HeldStore.of(ctx).postedFor(reportId) ?: return null
        val now = System.currentTimeMillis()
        if (h.expired(now)) return ctx.getString(R.string.sf_posted_status_expired, h.to)
        val until = h.expiresMs?.let { ctx.getString(R.string.sf_card_until, whenText(it)) } ?: ctx.getString(R.string.sf_post_until_removed)
        val sent = if (h.deliveredTo.isEmpty()) ctx.getString(R.string.sf_card_not_yet) else ctx.getString(R.string.sf_card_sent_to, h.deliveredTo.size, h.deliveredTo.sorted().joinToString(" "))
        return ctx.getString(R.string.sf_posted_status, h.to, h.id, until, sent)
    }

    private val EXPIRY = listOf(
        R.string.sf_post_6h to 6 * 3600_000L, R.string.sf_post_24h to 24 * 3600_000L,
        R.string.sf_post_48h to 48 * 3600_000L, R.string.sf_post_7d to 7 * 24 * 3600_000L, R.string.sf_post_until_removed to -1L,
    )

    /** Post (or re-post, or stop posting) a report so stations can fetch it with QUERY MSGS / QUERY MSG. */
    fun postDialog(f: Fragment, draft: ReportDrafts.Draft, onDone: () -> Unit) {
        val ctx = f.requireContext()
        val store = HeldStore.of(ctx)
        if (draft.isEmpty) { f.view?.let { Snackbar.make(it, R.string.sf_post_empty, Snackbar.LENGTH_SHORT).show() }; return }
        if (!store.postNoticeShown) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.sf_notice_title)
                .setMessage(R.string.sf_notice_body)
                .setPositiveButton(R.string.sf_notice_ok) { _, _ -> store.postNoticeShown = true; postDialog(f, draft, onDone) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        val existing = store.postedFor(draft.id)
        val groups = PreferenceManager.getDefaultSharedPreferences(ctx).getString("my_groups", "").orEmpty()
            .split(',', ' ', ';').map { it.trim().uppercase(Locale.ROOT) }.filter { it.startsWith("@") }
        var audience = existing?.to ?: draft.to.trim().uppercase(Locale.ROOT).ifBlank { groups.firstOrNull() ?: "@ALLCALL" }
        var expiry = existing?.let { e -> e.expiresMs?.let { it - e.receivedMs } ?: -1L } ?: 24 * 3600_000L
        var speed = existing?.submode ?: draft.speed ?: ReportUi.SPEEDS.first().submode

        val input = EditText(ctx).apply {
            setText(audience); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; isSingleLine = true
            hint = ctx.getString(R.string.sf_post_to_hint)
        }
        val audienceChips = ChipGroup(ctx).apply {
            (groups + "@ALLCALL").distinct().forEach { g -> addView(Chip(ctx).apply { text = g; setOnClickListener { input.setText(g) } }) }
        }
        val expiryChips = ChipGroup(ctx).apply {
            isSingleSelection = true; isSelectionRequired = true
            EXPIRY.forEach { (label, ms) ->
                addView(Chip(ctx).apply {
                    text = ctx.getString(label); isCheckable = true; id = View.generateViewId(); isChecked = ms == expiry
                    setOnClickListener { expiry = ms }
                })
            }
        }
        fun section(s: Int) = text(ctx, ctx.getString(s), 13f, R.color.tac_text_dim, bold = true).apply { setPadding(0, dp(ctx, 14), 0, dp(ctx, 2)) }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 22), dp(ctx, 8), dp(ctx, 22), 0)
            addView(text(ctx, ctx.getString(R.string.sf_post_body), 13.5f, R.color.tac_text_dim))
            addView(section(R.string.sf_post_to)); addView(input); addView(audienceChips)
            addView(section(R.string.sf_post_for)); addView(expiryChips)
            addView(section(R.string.reports_speed_label)); addView(ReportUi.speedChips(ctx, speed, allowTraffic = false) { v -> speed = v ?: speed })
            addView(text(ctx, ctx.getString(R.string.sf_post_preview, draft.onAir), 12.5f, R.color.tac_text_quiet, max = 4).apply { setPadding(0, dp(ctx, 12), 0, 0) })
        }
        val b = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.sf_post_title else R.string.sf_post_update_title)
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(if (existing == null) R.string.sf_post_go else R.string.sf_post_update) { _, _ ->
                audience = input.text.toString().trim().uppercase(Locale.ROOT).ifBlank { audience }
                val me = PreferenceManager.getDefaultSharedPreferences(ctx).getString("callsign", "").orEmpty().trim().uppercase(Locale.ROOT)
                val now = System.currentTimeMillis()
                existing?.let { store.delete(it.id) }
                val h = store.add(Held(
                    id = 0, to = if (audience.startsWith("@")) audience else Held.base(audience), from = me.ifBlank { "ME" },
                    text = draft.onAir, receivedMs = now, kind = Held.Kind.POSTED,
                    expiresMs = if (expiry < 0) null else now + expiry, submode = speed, reportId = draft.id,
                ))
                ReportStore.of(ctx).get(draft.id)?.let { ReportStore.of(ctx).put(it.copy(speed = speed)) }
                val autoreply = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("autoreply_enabled", false)
                f.view?.let {
                    Snackbar.make(it, ctx.getString(if (autoreply) R.string.sf_posted_ok else R.string.sf_posted_ok_no_autoreply, h.to, h.id),
                        if (autoreply) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG).show()
                }
                onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) b.setNeutralButton(R.string.sf_post_stop) { _, _ -> store.delete(existing.id); onDone() }
        b.show()
    }
}
