package com.js8call.example.ui.reports

import androidx.core.view.isNotEmpty
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.js8call.example.R
import com.js8call.example.reports.ReportDrafts
import com.js8call.example.reports.ReportStore
import com.js8call.example.reports.Reports
import com.js8call.example.ui.traffic.TrafficViewModel
import java.util.Calendar

/**
 * One saved report: a free-text page or a report form, who it goes to, exactly what goes
 * on air with its airtime, and an optional automatic send time. Saves as you type.
 */
class ReportEditorFragment : Fragment() {

    private lateinit var store: ReportStore
    private lateinit var draft: ReportDrafts.Draft
    private lateinit var content: LinearLayout
    private lateinit var preview: TextView
    private lateinit var airtime: TextView
    private lateinit var speedBox: LinearLayout
    private lateinit var scheduleBox: LinearLayout
    private lateinit var lastSent: TextView
    private lateinit var toInput: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val saveSoon = Runnable { save() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        store = ReportStore.of(ctx)
        val draftId = arguments?.getString(ARG_ID)
        draft = draftId?.let { store.get(it) } ?: store.create()

        val root = ScrollView(ctx).apply { setBackgroundColor(color(R.color.tac_ground)); isFillViewport = true }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        root.addView(col)

        // Armed banner: a report set to go out on its own says so at the top, where it can't be missed.
        armedTop = TextView(ctx).apply {
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        col.addView(armedTop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        // Title
        col.addView(edit(draft.title, getString(R.string.reports_title_hint), 20f, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
            setTypeface(typeface, Typeface.BOLD)
            watch { draft = draft.copy(title = it); changed(previewToo = false) }
        })

        // To
        col.addView(label(getString(R.string.reports_to)))
        toInput = edit(draft.to, getString(R.string.reports_to_hint), 16f, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            typeface = Typeface.MONOSPACE
            watch { draft = draft.copy(to = it.trim().uppercase()); changed(); if (::asMessageNote.isInitialized) renderAsMessageNote() }
        }
        col.addView(toInput)
        val toChips = ChipGroup(ctx).apply { isSingleLine = true }
        // Everyone, who Traffic is talking to, the groups saved on Traffic, recent recipients, @ALLCALL.
        val suggestions = (listOf("") + listOfNotNull(TrafficViewModel.of(ctx).sendTarget()) + ReportUi.savedGroups(ctx) +
            store.list().map { it.to } + listOf("@ALLCALL")).map { it.uppercase() }.distinct().take(14)
        suggestions.forEach { t ->
            toChips.addView(Chip(ctx).apply {
                text = t.ifBlank { getString(R.string.reports_everyone) }
                setOnClickListener { toInput.setText(t) }
            })
        }
        col.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(toChips) })

        // Send as a JS8 message, so it lands in the recipient's inbox
        asMessageSwitch = MaterialSwitch(ctx).apply {
            text = getString(R.string.reports_as_message)
            isChecked = draft.asMessage
            setTextColor(color(R.color.tac_text))
            setOnCheckedChangeListener { _, on -> draft = draft.copy(asMessage = on); renderAsMessageNote(); changed() }
        }
        col.addView(asMessageSwitch)
        asMessageNote = TextView(ctx).apply { textSize = 12f; setTextColor(color(R.color.tac_text_quiet)) }
        col.addView(asMessageNote)
        renderAsMessageNote()

        // Kind
        col.addView(label(getString(R.string.reports_kind)))
        val kinds = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true; isSingleLine = true }
        (listOf(ReportDrafts.FREE_TEXT to getString(R.string.reports_free_text)) + Reports.ALL.map { it.tag to it.tag }).forEach { (tag, name) ->
            kinds.addView(Chip(ctx).apply {
                text = name
                isCheckable = true
                id = View.generateViewId()
                isChecked = draft.kind == tag
                setOnClickListener {
                    if (draft.kind != tag) { draft = draft.copy(kind = tag); buildContent(); changed() }
                }
            })
        }
        col.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(kinds) })

        content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        col.addView(content)

        // What goes on air
        col.addView(label(getString(R.string.reports_on_air)))
        val air = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(color(R.color.tac_panel)); setStroke(dp(1), color(R.color.tac_divider)) }
        }
        preview = TextView(ctx).apply { typeface = Typeface.MONOSPACE; textSize = 14f; setTextColor(color(R.color.tac_text)); setTextIsSelectable(true) }
        airtime = TextView(ctx).apply { textSize = 12.5f; setTextColor(color(R.color.tac_text_dim)); setPadding(0, dp(6), 0, 0) }
        air.addView(preview)
        air.addView(airtime)
        col.addView(air)

        // Speed for this report (and its automatic sends)
        col.addView(label(getString(R.string.reports_speed_label)))
        speedBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(speedBox)
        renderSpeed()

        // Auto-send
        col.addView(label(getString(R.string.reports_auto)))
        scheduleBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(scheduleBox)
        lastSent = TextView(ctx).apply { textSize = 12.5f; setTextColor(color(R.color.tac_text_quiet)); setPadding(0, dp(8), 0, 0) }
        col.addView(lastSent)

        // Store and forward: offer this report to stations that ask (QUERY MSGS / QUERY MSG)
        col.addView(label(getString(R.string.sf_post_section)))
        postStatus = TextView(ctx).apply { textSize = 12.5f; setTextColor(color(R.color.tac_text_dim)); setPadding(0, 0, 0, dp(4)) }
        col.addView(postStatus)
        postButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false
            setOnClickListener {
                save()
                com.js8call.example.ui.StoreForwardUi.postDialog(this@ReportEditorFragment, draft) { renderPosted() }
            }
        }
        col.addView(postButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        renderPosted()

        // Actions
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, 0) }
        actions.addView(MaterialButton(ctx).apply {
            text = getString(R.string.reports_send_now)
            setOnClickListener {
                save()
                ReportUi.confirmSend(this@ReportEditorFragment, draft) { store.get(draft.id)?.let { draft = it; renderStatus(); renderSpeed(); renderPreview() } }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.reports_duplicate)
            setOnClickListener {
                save()
                val now = System.currentTimeMillis()
                val copy = draft.copy(id = java.util.UUID.randomUUID().toString(), title = getString(R.string.reports_copy_of, draft.displayTitle()),
                    createdMs = now, updatedMs = now, schedule = null, lastSentMs = null, lastResult = null)
                store.put(copy)
                val host = parentFragment as? ReportsFragment
                if (embedded && host != null) host.open(copy.id)
                else NavHostFragment.findNavController(this@ReportEditorFragment).navigate(R.id.navigation_report_edit, Bundle().apply { putString(ARG_ID, copy.id) })
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        actions.addView(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.reports_delete)
            setTextColor(0xFFFF6B6B.toInt())
            setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(getString(R.string.reports_delete_confirm, draft.displayTitle()))
                    .setPositiveButton(R.string.reports_delete) { _, _ ->
                        handler.removeCallbacks(saveSoon)
                        store.delete(draft.id)
                        deleted = true
                        val host = parentFragment as? ReportsFragment
                        if (embedded && host != null) host.onDeleted(draft.id)
                        else NavHostFragment.findNavController(this@ReportEditorFragment).popBackStack()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        })
        col.addView(actions)

        buildContent()
        renderPreview()
        renderSchedule()
        renderStatus()
        return root
    }

    private lateinit var armedTop: TextView
    private lateinit var asMessageSwitch: MaterialSwitch
    private lateinit var asMessageNote: TextView

    private fun renderAsMessageNote() {
        asMessageNote.text = getString(
            when {
                !draft.asMessage -> R.string.reports_as_message_off
                draft.to.isBlank() -> R.string.reports_as_message_needs_to
                else -> R.string.reports_as_message_on
            }
        )
        asMessageNote.setTextColor(color(if (draft.asMessage && draft.to.isBlank()) R.color.tac_accent else R.color.tac_text_quiet))
    }

    private var deleted = false
    private lateinit var postStatus: TextView
    private lateinit var postButton: MaterialButton

    private fun renderPosted() {
        if (!::postStatus.isInitialized || context == null) return
        val status = com.js8call.example.ui.StoreForwardUi.postedStatus(requireContext(), draft.id)
        postStatus.text = status ?: getString(R.string.sf_post_none)
        postButton.text = getString(if (status == null) R.string.sf_post_button else R.string.sf_post_change)
    }
    private val embedded get() = arguments?.getBoolean(ARG_EMBEDDED, false) == true

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        if (!deleted) save()
    }

    private fun save() {
        handler.removeCallbacks(saveSoon)
        // Deleted elsewhere (e.g. a blank report discarded from the list): don't bring it back.
        val stored = store.get(draft.id) ?: return
        // Keep what the auto-sender wrote (schedule moved on, last sent) unless we changed it here.
        val merged = if (stored.lastSentMs != draft.lastSentMs && (stored.lastSentMs ?: 0) > (draft.lastSentMs ?: 0))
            draft.copy(lastSentMs = stored.lastSentMs, lastResult = stored.lastResult,
                schedule = if (draft.schedule == null) null else stored.schedule)
        else draft
        if (stored == merged) return
        draft = merged.copy(updatedMs = System.currentTimeMillis())
        store.put(draft)
    }

    private val previewSoon = Runnable { if (view != null) renderPreview() }

    private fun changed(previewToo: Boolean = true) {
        // The preview repeats the whole report: redrawn once typing pauses, not on every key.
        if (previewToo) { handler.removeCallbacks(previewSoon); handler.postDelayed(previewSoon, 250) }
        handler.removeCallbacks(saveSoon)
        handler.postDelayed(saveSoon, 800)
    }

    // ---------------------------------------------------------------- content

    private fun buildContent() {
        val ctx = requireContext()
        content.removeAllViews()
        val type = draft.type
        if (type == null) {
            content.addView(edit(draft.body, getString(R.string.reports_body_hint), 16f,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
                minLines = 12
                gravity = Gravity.TOP or Gravity.START
                isSingleLine = false
                setHorizontallyScrolling(false)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(color(R.color.tac_panel)); setStroke(dp(1), color(R.color.tac_divider)) }
                watch { draft = draft.copy(body = it); changed() }
            })
            content.addView(TextView(ctx).apply {
                text = getString(R.string.reports_body_note)
                textSize = 12f
                setTextColor(color(R.color.tac_text_quiet))
                setPadding(0, dp(4), 0, 0)
            })
            return
        }
        content.addView(TextView(ctx).apply {
            text = type.title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.tac_text))
        })
        type.fields.forEach { field ->
            content.addView(label(field.label))
            val input = edit(draft.values[field.key].orEmpty(), field.hint.ifBlank { field.options.joinToString(", ") }, 16f,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS).apply {
                if (field.kind == Reports.Kind.LOCATION || field.kind == Reports.Kind.TIME || field.kind == Reports.Kind.FREQUENCY) typeface = Typeface.MONOSPACE
                watch { v -> draft = draft.copy(values = LinkedHashMap(draft.values).apply { put(field.key, v) }); changed() }
            }
            content.addView(input)
            val quick = ChipGroup(ctx).apply { isSingleLine = true }
            when (field.kind) {
                Reports.Kind.CHOICE -> field.options.forEach { o -> quick.addView(Chip(ctx).apply { text = o; setOnClickListener { input.setText(o) } }) }
                Reports.Kind.LOCATION -> {
                    val grid = PreferenceManager.getDefaultSharedPreferences(ctx).getString("grid", "").orEmpty().uppercase()
                    if (grid.isNotBlank()) quick.addView(Chip(ctx).apply { text = getString(R.string.reports_my_grid, grid); setOnClickListener { input.setText(grid) } })
                }
                Reports.Kind.TIME -> quick.addView(Chip(ctx).apply { text = getString(R.string.reports_now); setOnClickListener { input.setText(Reports.dtg()) } })
                Reports.Kind.FREQUENCY -> ReportUi.dialKhz(ctx)?.let { khz ->
                    quick.addView(Chip(ctx).apply { text = getString(R.string.reports_this_dial, khz); setOnClickListener { input.setText(khz) } })
                }
                else -> {}
            }
            if (quick.isNotEmpty()) content.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(quick) })
        }
    }

    private fun renderPreview() {
        val text = draft.sendText
        val to = draft.to.uppercase()
        preview.text = when {
            text.isBlank() -> getString(R.string.reports_blank)
            to.isBlank() -> text
            else -> "$to $text"
        }
        val ctx = requireContext()
        if (ReportUi.isCounted(ctx, draft)) { airtime.text = ReportUi.cachedAirtime(ctx, draft)?.label() ?: ""; return }
        airtime.text = getString(R.string.reports_counting)
        // Counted once typing pauses, off the main thread; only the latest text's answer is shown.
        handler.removeCallbacks(countSoon)
        handler.postDelayed(countSoon, 700)
    }

    private val countSoon = Runnable {
        if (view == null) return@Runnable
        val asked = draft
        ReportUi.airtimeAsync(requireContext(), asked) { air ->
            if (view != null && draft.sendText == asked.sendText && draft.to == asked.to) airtime.text = air?.label() ?: ""
        }
    }

    private fun renderSpeed() {
        val ctx = requireContext()
        speedBox.removeAllViews()
        speedBox.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(ReportUi.speedChips(ctx, draft.speed, allowTraffic = true) { v ->
                if (v != draft.speed) { draft = draft.copy(speed = v); renderSpeed(); changed() }
            }.apply { isSingleLine = true })
        })
        speedBox.addView(TextView(ctx).apply {
            text = ReportUi.speedNote(ctx, ReportUi.speedOf(ctx, draft))
            textSize = 12f
            setTextColor(color(R.color.tac_text_quiet))
            setPadding(0, dp(2), 0, 0)
        })
    }

    // ---------------------------------------------------------------- schedule

    private fun renderSchedule() {
        val ctx = requireContext()
        scheduleBox.removeAllViews()
        val s = draft.schedule
        val sw = MaterialSwitch(ctx).apply {
            text = getString(R.string.reports_auto_switch)
            isChecked = s != null
            setTextColor(color(R.color.tac_text))
            setOnCheckedChangeListener { _, on ->
                draft = draft.copy(schedule = if (on) ReportDrafts.Schedule(nextRoundHour(), 0) else null)
                changed(previewToo = false)
                renderSchedule()
            }
        }
        scheduleBox.addView(sw)
        renderArmed()
        if (s == null) return
        scheduleBox.addView(armedBox(ctx, s))
        scheduleBox.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = ReportUi.whenLabel(s.atMs)
            setTextColor(color(R.color.tac_text))
            setOnClickListener { pickTime(s) }
        })
        val repeats = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true; isSingleLine = true }
        listOf(0, 60, 120, 240, 360, 720, 1440).forEach { min ->
            repeats.addView(Chip(ctx).apply {
                text = if (min == 0) getString(R.string.reports_once) else ReportUi.repeatLabel(min)
                isCheckable = true
                id = View.generateViewId()
                isChecked = s.repeatMin == min
                setOnClickListener {
                    draft = draft.copy(schedule = draft.schedule?.copy(repeatMin = min))
                    changed(previewToo = false)
                }
            })
        }
        scheduleBox.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(repeats) })
        scheduleBox.addView(TextView(ctx).apply {
            text = getString(R.string.reports_auto_note)
            textSize = 12f
            setTextColor(color(R.color.tac_text_quiet))
            setPadding(0, dp(4), 0, 0)
        })
    }

    /** The banner at the top of the editor: shown only while the report is set to send on its own. */
    private fun renderArmed() {
        val s = draft.schedule
        armedTop.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s == null) return
        val (text, warn) = ReportUi.armedLine(requireContext(), s, System.currentTimeMillis())
        armedTop.text = text
        armedTop.setTextColor(if (warn) 0xFFF2A33A.toInt() else 0xFF4FC3F7.toInt())
        armedTop.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (warn) 0x22F2A33A else 0x224FC3F7)
            setStroke(dp(1), if (warn) 0xFFF2A33A.toInt() else 0xFF4FC3F7.toInt())
        }
    }

    private fun armedBox(ctx: android.content.Context, s: ReportDrafts.Schedule) = TextView(ctx).apply {
        val (text, warn) = ReportUi.armedLine(ctx, s, System.currentTimeMillis())
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (warn) 0xFFF2A33A.toInt() else 0xFF4FC3F7.toInt())
        setPadding(0, dp(6), 0, dp(2))
    }

    /** The countdown stays current while the editor is open. */
    private val tick = object : Runnable {
        override fun run() {
            if (view == null) return
            if (draft.schedule != null) renderSchedule()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 30_000L)
    }

    private fun pickTime(s: ReportDrafts.Schedule) {
        val ctx = requireContext()
        val cal = Calendar.getInstance().apply { timeInMillis = s.atMs }
        DatePickerDialog(ctx, { _, y, m, d ->
            cal.set(y, m, d)
            TimePickerDialog(ctx, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                draft = draft.copy(schedule = (draft.schedule ?: s).copy(atMs = cal.timeInMillis))
                changed(previewToo = false)
                renderSchedule()
                if (cal.timeInMillis < System.currentTimeMillis()) view?.let {
                    com.google.android.material.snackbar.Snackbar.make(it, R.string.reports_time_past, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                }
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(ctx)).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun nextRoundHour(): Long {
        val c = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun renderStatus() {
        lastSent.text = ReportUi.lastLine(draft).orEmpty()
        lastSent.visibility = if (lastSent.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- views

    private fun label(s: String) = TextView(requireContext()).apply {
        text = s.uppercase()
        textSize = 11.5f
        setTextColor(color(R.color.tac_text_dim))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun edit(value: String, hintText: String, size: Float, type: Int) = EditText(requireContext()).apply {
        setText(value)
        hint = hintText
        textSize = size
        inputType = type
        setTextColor(color(R.color.tac_text))
        setHintTextColor(color(R.color.tac_text_quiet))
    }

    private fun EditText.watch(onChange: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
    })

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_ID = "id"
        /** Shown in the Reports tab's right-hand pane rather than on its own page. */
        const val ARG_EMBEDDED = "embedded"
    }
}
