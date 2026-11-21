package com.js8call.example.ui.nets

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.js8call.example.R
import com.js8call.example.nets.NetSchedule
import com.js8call.example.nets.NetSchedule.Home
import com.js8call.example.nets.NetSchedule.Net
import com.js8call.example.nets.NetStore
import com.js8call.example.service.JS8EngineService
import com.js8call.example.ui.RadioControls
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Nets: the Net Switcher's schedule. Each net has a dial frequency, UTC days and times, and
 * optionally its own speed and heartbeat; "between nets" says where to go in the gaps. With the
 * switcher on, the receiver service changes the radio over CAT when a net starts or ends, never
 * cutting off a transmission, and steps back if the operator changes frequency by hand.
 */
class NetsFragment : Fragment() {

    private lateinit var store: NetStore
    private lateinit var body: LinearLayout
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { if (view != null) { render(); ticker.postDelayed(this, 30_000L) } }
    }
    private val wide get() = resources.configuration.screenWidthDp >= 720

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        store = NetStore.of(ctx)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        return ScrollView(ctx).apply {
            setBackgroundColor(color(R.color.tac_ground))
            isFillViewport = true
            addView(body)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store.changes.observe(viewLifecycleOwner) { render() }
    }

    override fun onResume() { super.onResume(); ticker.post(tick) }
    override fun onPause() { ticker.removeCallbacks(tick); super.onPause() }

    // ------------------------------------------------------------------ page

    private fun render() {
        if (view == null) return
        val ctx = requireContext()
        body.removeAllViews()
        val nets = store.nets()
        val home = store.home()
        val now = System.currentTimeMillis()

        // Header: title, the switch, add.
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(8)) }
        header.addView(TextView(ctx).apply {
            text = getString(R.string.nets_title); textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.tac_text))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(ctx).apply { text = getString(R.string.nets_switcher); textSize = 14f; setTextColor(color(R.color.tac_text_dim)); setPadding(0, 0, dp(8), 0) })
        val switcherState = onOffLabel(store.enabled)
        header.addView(switcherState)
        header.addView(MaterialSwitch(ctx).apply {
            isChecked = store.enabled
            setOnCheckedChangeListener { _, on ->
                if (on && nets.isEmpty()) {
                    isChecked = false
                    Snackbar.make(requireView(), R.string.nets_add_first, Snackbar.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                store.enabled = on
                setOnOff(switcherState, on)
                store.log(System.currentTimeMillis(), if (on) "Net Switcher on" else "Net Switcher off")
                render()
            }
        })
        header.addView(MaterialButton(ctx).apply {
            text = getString(R.string.nets_add)
            setOnClickListener { editNet(null) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        body.addView(header)

        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (wide) {
            body.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(16) })
                addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
            })
        } else {
            body.addView(left); body.addView(right)
        }

        left.addView(statusCard(nets, home, now))
        left.addView(upcomingCard(nets, home, now))
        left.addView(logCard())

        right.addView(label(getString(R.string.nets_list)))
        if (nets.isEmpty()) right.addView(quiet(getString(R.string.nets_empty)))
        val overlaps = NetSchedule.overlaps(nets, now)
        nets.sortedWith(compareBy({ !it.enabled }, { it.startMin })).forEach { right.addView(netCard(it, overlaps)) }
        right.addView(label(getString(R.string.nets_between)))
        right.addView(homeCard(home))
        right.addView(label(getString(R.string.nets_hold)))
        right.addView(holdChips())
        right.addView(quiet(getString(R.string.nets_hold_note)))
    }

    private fun statusCard(nets: List<Net>, home: Home, now: Long): View {
        val ctx = requireContext()
        val c = card()
        fun line(t: CharSequence, size: Float = 14f, col: Int = R.color.tac_text, bold: Boolean = false) =
            c.addView(TextView(ctx).apply { text = t; textSize = size; setTextColor(color(col)); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(2), 0, dp(2)) })
        if (!store.enabled) {
            line(getString(R.string.nets_off), 16f, R.color.tac_text_dim, true)
            line(getString(R.string.nets_off_note), 13f, R.color.tac_text_quiet)
            return c
        }
        val t = NetSchedule.target(nets, home, now)
        val next = NetSchedule.nextChange(nets, home, now)
        val override = store.overrideUntilMs
        line(if (t.net != null) getString(R.string.nets_now_on, t.net.name, t.net.dialHz.let { NetSchedule.mhz(it) })
             else getString(R.string.nets_now_between, home.dialHz?.let { NetSchedule.mhz(it) + " MHz" } ?: getString(R.string.nets_stay)), 16f, R.color.tac_text, true)
        if (next != null) line(getString(R.string.nets_next, whatOf(next.second), zulu(next.first), local(next.first), inText(next.first - now)), 13f, R.color.tac_text_dim)
        store.waiting?.let { line(getString(R.string.nets_waiting, it), 13f, R.color.tac_warn_text) }
        if (now < override) {
            line(getString(R.string.nets_paused, zulu(override)), 13f, R.color.tac_warn_text)
            c.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.nets_resume)
                setOnClickListener {
                    store.overrideUntilMs = 0L
                    store.appliedKey = null
                    store.log(System.currentTimeMillis(), "Back on schedule (resumed by hand)")
                }
            })
        }
        if (!JS8EngineService.engineRunning) line(getString(R.string.nets_receiver_off), 13f, R.color.tac_warn_text)
        if (!RadioControls.canTuneRadio(ctx)) line(getString(R.string.nets_no_cat), 13f, R.color.tac_text_quiet)
        return c
    }

    private fun whatOf(t: NetSchedule.Target): String =
        t.net?.let { "${it.name} · ${NetSchedule.mhz(it.dialHz)}" } ?: getString(R.string.nets_between_short)

    private fun upcomingCard(nets: List<Net>, home: Home, now: Long): View {
        val ctx = requireContext()
        val c = card()
        c.addView(label(getString(R.string.nets_upcoming), top = 0))
        val up = NetSchedule.upcoming(nets, home, now, max = 8)
        if (up.isEmpty()) c.addView(quiet(getString(R.string.nets_upcoming_none)))
        val day = SimpleDateFormat("EEE", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        up.forEach { (at, t) ->
            c.addView(TextView(ctx).apply {
                text = "${day.format(Date(at))} ${zulu(at)}  (${local(at)})   →  ${whatOf(t)}" +
                    listOfNotNull(t.speed?.let { speedName(it) }, t.heartbeatMin?.let { hbText(it) }).joinToString("") { " · $it" }
                textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(color(if (t.net != null) R.color.tac_text else R.color.tac_text_dim))
                setPadding(0, dp(3), 0, dp(3))
            })
        }
        return c
    }

    private fun logCard(): View {
        val ctx = requireContext()
        val c = card()
        c.addView(label(getString(R.string.nets_log), top = 0))
        val log = store.readLog().takeLast(12).reversed()
        if (log.isEmpty()) c.addView(quiet(getString(R.string.nets_log_none)))
        val fmt = SimpleDateFormat("dd MMM HHmm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        log.forEach { l ->
            c.addView(TextView(ctx).apply {
                text = "${fmt.format(Date(l.ms))}  ${l.text}"; textSize = 12.5f; setTextColor(color(R.color.tac_text_dim)); setPadding(0, dp(2), 0, dp(2))
            })
        }
        return c
    }

    private fun netCard(n: Net, overlaps: List<Pair<Net, Net>>): View {
        val ctx = requireContext()
        val c = card()
        c.isClickable = true
        c.setOnClickListener { editNet(n) }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(ctx).apply {
            text = n.name; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(if (n.enabled) R.color.tac_text else R.color.tac_text_quiet))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val state = onOffLabel(n.enabled)
        row.addView(state)
        row.addView(MaterialSwitch(ctx).apply {
            isChecked = n.enabled
            contentDescription = getString(R.string.nets_toggle_cd, n.name)
            setOnCheckedChangeListener { _, on -> store.put(n.copy(enabled = on)); setOnOff(state, on) }
        })
        row.addView(android.widget.ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_delete)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.tac_text_dim))
            background = null
            contentDescription = getString(R.string.nets_delete_cd, n.name)
            setPadding(dp(10), dp(8), dp(4), dp(8))
            setOnClickListener { confirmDelete(n) }
        })
        c.addView(row)
        if (!n.enabled) c.alpha = 0.6f
        c.addView(tapToEdit())
        val end = (n.startMin + n.lengthMin) % 1440
        c.addView(TextView(ctx).apply {
            text = "${NetSchedule.mhz(n.dialHz)} MHz · ${NetSchedule.daysText(n.days)} · ${NetSchedule.hhmm(n.startMin)}–${NetSchedule.hhmm(end)}Z" +
                "  (${localHhmm(n.startMin)}–${localHhmm(end)} local)"
            textSize = 13.5f; setTextColor(color(R.color.tac_text_dim))
        })
        c.addView(TextView(ctx).apply {
            text = listOf(n.speed?.let { getString(R.string.nets_speed_x, speedName(it)) } ?: getString(R.string.nets_speed_keep),
                n.heartbeatMin?.let { hbText(it) } ?: getString(R.string.nets_hb_keep)).joinToString(" · ")
            textSize = 12.5f; setTextColor(color(R.color.tac_text_quiet))
        })
        overlaps.filter { it.first.id == n.id || it.second.id == n.id }.forEach { (a, b) ->
            val other = if (a.id == n.id) b else a
            c.addView(TextView(ctx).apply {
                text = getString(R.string.nets_overlap, other.name); textSize = 12.5f; setTextColor(color(R.color.tac_warn_text))
            })
        }
        if (n.notes.isNotBlank()) c.addView(quiet(n.notes))
        return c
    }

    private fun homeCard(home: Home): View {
        val ctx = requireContext()
        val c = card()
        c.isClickable = true
        c.setOnClickListener { editHome(home) }
        c.addView(TextView(ctx).apply {
            text = home.dialHz?.let { getString(R.string.nets_home_go, NetSchedule.mhz(it)) } ?: getString(R.string.nets_home_stay)
            textSize = 15f; setTextColor(color(R.color.tac_text))
        })
        c.addView(tapToEdit())
        c.addView(TextView(ctx).apply {
            text = listOf(home.speed?.let { getString(R.string.nets_speed_x, speedName(it)) } ?: getString(R.string.nets_speed_keep),
                home.heartbeatMin?.let { hbText(it) } ?: getString(R.string.nets_hb_keep)).joinToString(" · ")
            textSize = 12.5f; setTextColor(color(R.color.tac_text_quiet))
        })
        return c
    }

    private fun holdChips(): View {
        val ctx = requireContext()
        val g = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        listOf(0, 1, 2, 5, 10).forEach { m ->
            g.addView(Chip(ctx).apply {
                text = if (m == 0) getString(R.string.nets_hold_off) else "$m min"
                isCheckable = true; id = View.generateViewId(); isChecked = store.holdAfterBandChangeMin == m
                choiceStyle(this)
                setOnClickListener { store.holdAfterBandChangeMin = m }
            })
        }
        return g
    }

    // ------------------------------------------------------------------ editors

    private fun editNet(existing: Net?) {
        val ctx = requireContext()
        var n = existing ?: Net(java.util.UUID.randomUUID().toString(), "", 7_107_000L, NetSchedule.EVERY_DAY, 0, 60)
        val box = form()
        val name = field(box, getString(R.string.nets_f_name), n.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val freq = field(box, getString(R.string.nets_f_freq), if (existing != null) NetSchedule.mhz(n.dialHz) else "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        box.addView(presetChips { hz -> freq.setText(NetSchedule.mhz(hz)) })

        box.addView(label(getString(R.string.nets_f_days)))
        val dayGroup = ChipGroup(ctx)
        val dayChips = NetSchedule.DAY_BITS.mapIndexed { i, bit ->
            Chip(ctx).apply { text = NetSchedule.DAY_NAMES[i]; isCheckable = true; isChecked = n.days and bit != 0; id = View.generateViewId(); choiceStyle(this) }
                .also { dayGroup.addView(it) }
        }
        box.addView(dayGroup)
        box.addView(ChipGroup(ctx).apply {
            listOf(getString(R.string.nets_every_day) to NetSchedule.EVERY_DAY, getString(R.string.nets_weekdays) to 31, getString(R.string.nets_weekends) to 96).forEach { (t, mask) ->
                addView(Chip(ctx).apply { text = t; actionStyle(this); setOnClickListener { dayChips.forEachIndexed { i, ch -> ch.isChecked = mask and NetSchedule.DAY_BITS[i] != 0 } } })
            }
        })

        box.addView(label(getString(R.string.nets_f_times)))
        var start = n.startMin
        var end = n.endMin
        val times = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val startBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val endBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val timesNote = quiet("")
        fun renderTimes() {
            startBtn.text = getString(R.string.nets_from, NetSchedule.hhmm(start))
            endBtn.text = getString(R.string.nets_to, NetSchedule.hhmm(end))
            timesNote.text = getString(R.string.nets_times_note, localHhmm(start), localHhmm(end)) +
                if (end <= start) "  " + getString(R.string.nets_past_midnight) else ""
        }
        fun pick(title: Int, min: Int, set: (Int) -> Unit) {
            MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H).setHour(min / 60).setMinute(min % 60)
                .setTitleText(title).setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD).build().apply {
                    addOnPositiveButtonClickListener { set(hour * 60 + minute); renderTimes() }
                }.show(childFragmentManager, "time")
        }
        startBtn.setOnClickListener { pick(R.string.nets_pick_start, start) { start = it } }
        endBtn.setOnClickListener { pick(R.string.nets_pick_end, end) { end = it } }
        times.addView(startBtn); times.addView(TextView(ctx).apply { text = "  –  " }); times.addView(endBtn)
        box.addView(times)
        box.addView(timesNote)
        renderTimes()

        box.addView(label(getString(R.string.nets_f_speed)))
        var speed = n.speed
        box.addView(choiceChips(listOf<Pair<String, Int?>>(getString(R.string.nets_keep) to null) + SPEEDS.map { speedName(it) to it }, speed) { speed = it })
        box.addView(label(getString(R.string.nets_f_hb)))
        var hb = n.heartbeatMin
        box.addView(choiceChips(listOf<Pair<String, Int?>>(getString(R.string.nets_keep) to null) + HB.map { hbShort(it) to it }, hb) { hb = it })
        val notes = field(box, getString(R.string.nets_f_notes), n.notes, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.nets_new else R.string.nets_edit)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.nets_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (existing != null) setNeutralButton(R.string.nets_delete) { _, _ -> confirmDelete(existing) } }
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val hz = parseMhz(freq.text.toString())
            if (hz == null) { freq.error = getString(R.string.nets_freq_error); return@setOnClickListener }
            var days = 0
            dayChips.forEachIndexed { i, ch -> if (ch.isChecked) days = days or NetSchedule.DAY_BITS[i] }
            n = n.copy(
                name = name.text.toString().trim().ifBlank { getString(R.string.nets_default_name, NetSchedule.mhz(hz)) },
                dialHz = hz, days = days, startMin = start, endMin = end, speed = speed, heartbeatMin = hb,
                notes = notes.text.toString().trim(),
            )
            store.put(n)
            dialog.dismiss()
            if (days == 0) Snackbar.make(requireView(), R.string.nets_no_days, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun editHome(home: Home) {
        val ctx = requireContext()
        val box = form()
        box.addView(quiet(getString(R.string.nets_home_note)))
        val freq = field(box, getString(R.string.nets_f_freq_home), home.dialHz?.let { NetSchedule.mhz(it) }.orEmpty(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        box.addView(presetChips { hz -> freq.setText(NetSchedule.mhz(hz)) })
        box.addView(label(getString(R.string.nets_f_speed)))
        var speed = home.speed
        box.addView(choiceChips(listOf<Pair<String, Int?>>(getString(R.string.nets_keep) to null) + SPEEDS.map { speedName(it) to it }, speed) { speed = it })
        box.addView(label(getString(R.string.nets_f_hb)))
        var hb = home.heartbeatMin
        box.addView(choiceChips(listOf<Pair<String, Int?>>(getString(R.string.nets_keep) to null) + HB.map { hbShort(it) to it }, hb) { hb = it })
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.nets_between)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.nets_save) { _, _ ->
                store.setHome(Home(parseMhz(freq.text.toString()), speed, hb))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(n: Net) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.nets_delete_q, n.name))
            .setPositiveButton(R.string.nets_delete) { _, _ -> store.delete(n.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ bits

    private fun presetChips(onPick: (Long) -> Unit): View {
        val ctx = requireContext()
        val g = ChipGroup(ctx).apply { isSingleLine = true }
        RadioControls.bands(ctx).first.forEach { b ->
            g.addView(Chip(ctx).apply { text = RadioControls.shortLabel(b.label); actionStyle(this); setOnClickListener { onPick(b.hz) } })
        }
        return HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(g) }
    }

    private fun choiceChips(options: List<Pair<String, Int?>>, selected: Int?, onPick: (Int?) -> Unit): View {
        val ctx = requireContext()
        val g = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        options.forEach { (t, v) ->
            g.addView(Chip(ctx).apply {
                text = t; isCheckable = true; id = View.generateViewId(); isChecked = v == selected
                choiceStyle(this)
                setOnClickListener { onPick(v) }
            })
        }
        return g
    }

    /**
     * A chip that is either picked or not: picked is solid amber with dark text and a tick,
     * not picked is a plain dark outline, so the state reads at a glance in any light.
     */
    private fun choiceStyle(chip: Chip) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        fun csl(on: Int, off: Int) = android.content.res.ColorStateList(states, intArrayOf(on, off))
        chip.chipBackgroundColor = csl(color(R.color.tac_accent), color(R.color.tac_card))
        chip.chipStrokeColor = csl(color(R.color.tac_accent), color(R.color.tac_border))
        chip.chipStrokeWidth = dp(1).toFloat()
        chip.setTextColor(csl(color(R.color.tac_ground), color(R.color.tac_text_dim)))
        chip.isCheckedIconVisible = true
        chip.checkedIconTint = android.content.res.ColorStateList.valueOf(color(R.color.tac_ground))
        chip.rippleColor = android.content.res.ColorStateList.valueOf(color(R.color.tac_accent_tint))
        fun bold() { chip.setTypeface(null, if (chip.isChecked) Typeface.BOLD else Typeface.NORMAL) }
        bold()
        chip.setOnCheckedChangeListener { _, _ -> bold() }
    }

    /** A shortcut button (fills in a value, has no on/off state): outlined, amber text. */
    private fun actionStyle(chip: Chip) {
        chip.isCheckable = false
        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(color(R.color.tac_border))
        chip.chipStrokeWidth = dp(1).toFloat()
        chip.setTextColor(color(R.color.tac_accent))
        chip.rippleColor = android.content.res.ColorStateList.valueOf(color(R.color.tac_accent_tint))
    }

    private fun onOffLabel(on: Boolean) = TextView(requireContext()).apply {
        textSize = 13f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, dp(6), 0)
        setOnOff(this, on)
    }

    private fun setOnOff(v: TextView, on: Boolean) {
        v.text = getString(if (on) R.string.nets_state_on else R.string.nets_state_off)
        v.setTextColor(color(if (on) R.color.tac_good_text else R.color.tac_text_quiet))
    }

    private fun tapToEdit() = TextView(requireContext()).apply {
        text = getString(R.string.nets_tap_to_edit); textSize = 12f
        setTextColor(color(R.color.tac_accent)); setPadding(0, 0, 0, dp(2))
    }

    private fun parseMhz(s: String): Long? =
        s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.1 && it < 3000 }?.let { Math.round(it * 1_000_000.0) }

    private fun form() = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }

    private fun field(parent: LinearLayout, hint: String, value: String, type: Int): EditText {
        val ctx = requireContext()
        val l = com.google.android.material.textfield.TextInputLayout(ctx).apply { this.hint = hint; setPadding(0, dp(4), 0, 0) }
        val e = com.google.android.material.textfield.TextInputEditText(l.context).apply { setText(value); inputType = type }
        l.addView(e)
        parent.addView(l)
        return e
    }

    private fun card(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color(R.color.tac_panel)); setStroke(dp(1), color(R.color.tac_divider)) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    }

    private fun label(s: String, top: Int = 12) = TextView(requireContext()).apply {
        text = s.uppercase(); textSize = 12f; letterSpacing = 0.06f
        setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.tac_text_dim)); setPadding(0, dp(top), 0, dp(6))
    }

    private fun quiet(s: String) = TextView(requireContext()).apply { text = s; textSize = 12.5f; setTextColor(color(R.color.tac_text_quiet)); setPadding(0, dp(2), 0, dp(4)) }

    private fun zulu(ms: Long) = SimpleDateFormat("HHmm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    private fun local(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    /** A UTC time of day as local time today. */
    private fun localHhmm(utcMin: Int): String {
        val midnight = System.currentTimeMillis().let { it - Math.floorMod(it, 86_400_000L) }
        return local(midnight + utcMin * 60_000L)
    }

    private fun inText(ms: Long): String {
        val m = (ms / 60_000L).coerceAtLeast(0)
        return when { m < 60 -> "in $m min"; m < 48 * 60 -> "in ${m / 60} h ${m % 60} min"; else -> "in ${m / 1440} days" }
    }

    private fun speedName(sm: Int) = getString(when (sm) {
        JS8EngineService.SUBMODE_SLOW -> R.string.tx_speed_slow
        JS8EngineService.SUBMODE_FAST -> R.string.tx_speed_fast
        JS8EngineService.SUBMODE_TURBO -> R.string.tx_speed_turbo
        else -> R.string.tx_speed_normal
    })

    private fun hbText(min: Int) = if (min <= 0) getString(R.string.nets_hb_off) else getString(R.string.nets_hb_every, min)
    private fun hbShort(min: Int) = if (min <= 0) getString(R.string.nets_hb_off_short) else "$min min"

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val SPEEDS = listOf(JS8EngineService.SUBMODE_SLOW, JS8EngineService.SUBMODE_NORMAL, JS8EngineService.SUBMODE_FAST, JS8EngineService.SUBMODE_TURBO)
        private val HB = listOf(0, 3, 10, 15, 30, 60)
    }
}
