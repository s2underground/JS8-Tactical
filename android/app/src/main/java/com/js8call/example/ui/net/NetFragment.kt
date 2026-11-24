package com.js8call.example.ui.net

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R
import com.js8call.example.net.NetRoster
import com.js8call.example.team.TeamRoster
import com.js8call.example.ui.TransmitViewModel
import com.js8call.example.ui.traffic.TrafficViewModel

/**
 * Net roster for one group: who checked in (with status, grid, signal, precedence) and,
 * given an expected list, who hasn't. Works on a free-for-all net with no net control;
 * the net-control buttons (call the net, ACK, roll call) are optional and only queue
 * ordinary JS8 messages, always shown before they go.
 */
class NetFragment : Fragment() {

    private lateinit var traffic: TrafficViewModel
    private lateinit var transmit: TransmitViewModel
    private lateinit var body: LinearLayout
    private lateinit var group: String
    private var last: NetRoster.Roster? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        group = (arguments?.getString(ARG_GROUP) ?: "@ALLCALL").uppercase()
        traffic = TrafficViewModel.of(ctx)
        transmit = TransmitViewModel.of(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.tac_ground))
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.tac_panel))
            setPadding(dp(16), dp(6), dp(8), dp(6))
        }
        header.addView(TextView(ctx).apply {
            text = getString(R.string.net_title, group)
            setTextColor(color(R.color.tac_text))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        val chips = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true; isSingleLine = true }
        val since = sinceMs()
        val now = System.currentTimeMillis()
        listOf(1 to "1 h", 3 to "3 h", 12 to "12 h", 24 to "24 h").forEach { (h, label) ->
            chips.addView(Chip(ctx).apply {
                text = label
                isCheckable = true
                isChecked = kotlin.math.abs(now - since - h * HOUR) < 60_000L
                id = View.generateViewId()
                setOnClickListener { setSince(System.currentTimeMillis() - h * HOUR) }
            })
        }
        chips.addView(Chip(ctx).apply {
            text = getString(R.string.net_start_now)
            isCheckable = true
            id = View.generateViewId()
            setOnClickListener { setSince(System.currentTimeMillis()) }
        })
        header.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        header.addView(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.net_share)
            setOnClickListener { share() }
        })
        root.addView(header)

        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(24))
        }
        root.addView(ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        traffic.stations.observe(viewLifecycleOwner) { refresh() }
        traffic.thread.observe(viewLifecycleOwner) { refresh() }
    }

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(requireContext())
    private fun sinceMs(): Long = prefs().getLong(PREF_SINCE + group, System.currentTimeMillis() - 3 * HOUR)
    private fun setSince(ms: Long) { prefs().edit().putLong(PREF_SINCE + group, ms).apply(); last = null; refresh() }
    private fun expected(): List<String> = prefs().getString(PREF_EXPECTED + group, "").orEmpty()
        .split(',', ' ', ';', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    private fun refresh() {
        if (view == null) return
        val roster = try {
            NetRoster.build(traffic.netInput(group, sinceMs(), expected()))
        } catch (e: Exception) {
            android.util.Log.e("NetFragment", "roster failed", e)
            return
        }
        if (roster.copy(nowMs = 0) == last?.copy(nowMs = 0)) return
        last = roster
        render(roster)
    }

    private fun render(r: NetRoster.Roster) {
        val ctx = requireContext()
        body.removeAllViews()

        // Summary and net-control actions.
        body.addView(card().apply {
            addView(text(getString(R.string.net_summary, r.checkedIn.size, NetRoster.hhmm(r.sinceMs)) +
                if (r.missing.isNotEmpty()) getString(R.string.net_summary_missing, r.missing.size) else "", 18f, bold = true))
            addView(text(getString(R.string.net_explain), 12.5f, color(R.color.tac_text_quiet)))
            val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            actions.addView(button(getString(R.string.net_call_net)) {
                confirmSend(group, getString(R.string.net_call_text))
            })
            if (r.missing.isNotEmpty()) actions.addView(button(getString(R.string.net_roll_call)) {
                confirmSend(group, NetRoster.rollCallText(r.missing))
            })
            actions.addView(button(getString(R.string.net_expected)) { editExpected() })
            body.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        })

        body.addView(sectionTitle(getString(R.string.net_checked_in, r.checkedIn.size)))
        if (r.checkedIn.isEmpty()) body.addView(text(getString(R.string.net_none_yet), 14f, color(R.color.tac_text_dim)))
        r.checkedIn.forEach { c ->
            body.addView(card(statusColor(c.status)).apply {
                val title = buildString {
                    append(c.call)
                    c.precedence?.takeIf { it != NetRoster.Precedence.ROUTINE }?.let { append("  ·  $it") }
                    c.statusText?.let { append("  ·  $it") }
                }
                addView(text(title, 16f, if (c.status == NetRoster.Status.NONE) color(R.color.tac_text) else statusColor(c.status), bold = true))
                addView(text(listOfNotNull(
                    "in ${NetRoster.hhmm(c.firstMs)}Z",
                    c.grid,
                    c.snr?.let { "$it dB" },
                    if (c.messages > 1) "${c.messages} msgs" else null,
                    if (c.answered) "answered" else null,
                ).joinToString(" · "), 12.5f, color(R.color.tac_text_dim)))
                addView(text(c.lastText.take(160), 14f))
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
                row.addView(button(getString(R.string.net_ack)) { confirmSend(c.call, "ACK") })
                row.addView(button(getString(R.string.net_open)) { openStation(c.call) })
                addView(row)
            })
        }

        if (r.missing.isNotEmpty()) {
            body.addView(sectionTitle(getString(R.string.net_not_yet, r.missing.size)))
            r.missing.forEach { m ->
                body.addView(card().apply {
                    addView(text(m.call, 16f, bold = true))
                    addView(text(m.lastHeardMs?.let { getString(R.string.net_on_air, NetRoster.hhmm(it)) } ?: getString(R.string.net_not_heard),
                        12.5f, color(R.color.tac_text_dim)))
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
                    row.addView(button(getString(R.string.net_ping)) { confirmSend(m.call, "SNR?") })
                    row.addView(button(getString(R.string.net_open)) { openStation(m.call) })
                    addView(row)
                })
            }
        }
    }

    /** Every net-control send is shown, editable, before it is queued. */
    private fun confirmSend(to: String, text: String) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.net_send_to, to))
            .setView(LinearLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
            .setPositiveButton(R.string.net_queue) { _, _ ->
                val t = input.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    transmit.queueThreadMessage(to, t)
                    view?.let { Snackbar.make(it, getString(R.string.net_queued, to), Snackbar.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editExpected() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(expected().joinToString(" "))
            hint = "N0AAA K1BBB W4CCC"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val team = TeamRoster.decode(prefs().getString(TeamRoster.KEY, null)).map { it.call.uppercase() }
        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.net_expected_title)
            .setMessage(R.string.net_expected_text)
            .setView(LinearLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs().edit().putString(PREF_EXPECTED + group, input.text?.toString().orEmpty().uppercase()).apply()
                last = null
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (team.isNotEmpty()) builder.setNeutralButton(R.string.net_use_team) { _, _ ->
            prefs().edit().putString(PREF_EXPECTED + group, team.joinToString(" ")).apply()
            last = null
            refresh()
        }
        builder.show()
    }

    private fun openStation(call: String) {
        traffic.select(call)
        findNavController().navigate(R.id.navigation_traffic)
    }

    private fun share() {
        val r = last ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, r.toText())
        }, getString(R.string.net_share)))
    }

    // ------------------------------------------------------------- views

    private fun statusColor(s: NetRoster.Status) = when (s) {
        NetRoster.Status.EMERGENCY -> 0xFFFF6B6B.toInt()
        NetRoster.Status.NEED -> 0xFFF2A33A.toInt()
        NetRoster.Status.GOOD -> 0xFF5CC98B.toInt()
        NetRoster.Status.NONE -> color(R.color.tac_divider)
    }

    private fun card(stroke: Int = color(R.color.tac_divider)) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(color(R.color.tac_panel))
            setStroke(dp(1), stroke)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun sectionTitle(s: String) = text(s.uppercase(), 12.5f, color(R.color.tac_text_quiet), bold = true).apply {
        setPadding(dp(4), dp(10), 0, dp(6))
    }

    private fun text(s: CharSequence, size: Float, c: Int = color(R.color.tac_text), bold: Boolean = false) = TextView(requireContext()).apply {
        text = s
        textSize = size
        setTextColor(c)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, 0)
    }

    private fun button(label: String, onClick: () -> Unit) =
        MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            setTextColor(color(R.color.tac_text))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        }

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_GROUP = "group"
        private const val PREF_SINCE = "net_since_"
        private const val PREF_EXPECTED = "net_expected_"
        private const val HOUR = 3_600_000L
    }
}
