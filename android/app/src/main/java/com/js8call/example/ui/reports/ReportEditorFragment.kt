package com.js8call.example.ui.reports

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

        // Title
        col.addView(edit(draft.title, getString(R.string.reports_title_hint), 20f, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
            setTypeface(typeface, Typeface.BOLD)
            watch { draft = draft.copy(title = it); changed(previewToo = false) }
        })

        // To
        col.addView(label(getString(R.string.reports_to)))
        toInput = edit(draft.to, getString(R.string.reports_to_hint), 16f, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            typeface = Typeface.MONOSPACE
            watch { draft = draft.copy(to = it.trim().uppercase()); changed() }
        }
        col.addView(toInput)
        val toChips = ChipGroup(ctx).apply { isSingleLine = true }
        val suggestions = (listOf("") + listOfNotNull(TrafficViewModel.of(ctx).sendTarget()) +
            store.list().map { it.to } + listOf("@ALLCALL")).map { it.uppercase() }.distinct().take(7)
        suggestions.forEach { t ->
            toChips.addView(Chip(ctx).apply {
                text = t.ifBlank { getString(R.string.reports_everyone) }
                setOnClickListener { toInput.setText(t) }
            })
        }
        col.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(toChips) })

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

        // Auto-send
        col.addView(label(getString(R.string.reports_auto)))
        scheduleBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(scheduleBox)
        lastSent = TextView(ctx).apply { textSize = 12.5f; setTextColor(color(R.color.tac_text_quiet)); setPadding(0, dp(8), 0, 0) }
        col.addView(lastSent)

        // Actions
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, 0) }
        actions.addView(MaterialButton(ctx).apply {
            text = getString(R.string.reports_send_now)
            setOnClickListener {
                save()
                ReportUi.confirmSend(this@ReportEditorFragment, draft) { store.get(draft.id)?.let { draft = it; renderStatus() } }
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
                NavHostFragment.findNavController(this@ReportEditorFragment).navigate(R.id.navigation_report_edit, Bundle().apply { putString(ARG_ID, copy.id) })
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        actions.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.reports_delete)
            setTextColor(0xFFFF6B6B.toInt())
            setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(getString(R.string.reports_delete_confirm, draft.displayTitle()))
                    .setPositiveButton(R.string.reports_delete) { _, _ ->
                        handler.removeCallbacks(saveSoon)
                        store.delete(draft.id)
                        deleted = true
                        NavHostFragment.findNavController(this@ReportEditorFragment).popBackStack()
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

    private var deleted = false

    override fun onPause() {
        super.onPause()
        if (!deleted) save()
    }

    private fun save() {
        handler.removeCallbacks(saveSoon)
        val stored = store.get(draft.id)
        // Keep what the auto-sender wrote (schedule moved on, last sent) unless we changed it here.
        val merged = if (stored != null && stored.lastSentMs != draft.lastSentMs && (stored.lastSentMs ?: 0) > (draft.lastSentMs ?: 0))
            draft.copy(lastSentMs = stored.lastSentMs, lastResult = stored.lastResult,
                schedule = if (draft.schedule == null) null else stored.schedule)
        else draft
        if (stored == merged) return
        draft = merged.copy(updatedMs = System.currentTimeMillis())
        store.put(draft)
    }

    private fun changed(previewToo: Boolean = true) {
        if (previewToo) renderPreview()
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
                if (field.kind == Reports.Kind.LOCATION || field.kind == Reports.Kind.TIME) typeface = Typeface.MONOSPACE
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
                else -> {}
            }
            if (quick.childCount > 0) content.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(quick) })
        }
    }

    private fun renderPreview() {
        val text = draft.onAir
        val to = draft.to.uppercase()
        preview.text = when {
            text.isBlank() -> getString(R.string.reports_blank)
            to.isBlank() -> text
            else -> "$to $text"
        }
        airtime.text = ReportUi.airtime(requireContext(), draft)?.label() ?: ""
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
        if (s == null) return
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
    }
}
