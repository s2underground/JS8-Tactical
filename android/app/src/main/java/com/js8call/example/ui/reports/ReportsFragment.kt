package com.js8call.example.ui.reports

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.js8call.example.R
import com.js8call.example.reports.ReportDrafts
import com.js8call.example.reports.ReportStore
import com.js8call.example.service.JS8EngineService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Reports tab. On a tablet (or any wide screen) it is laid out like a mail client:
 * the list of saved reports on the left, the selected one open for writing on the right.
 * On a narrow screen the list fills the screen and a report opens on its own page.
 */
class ReportsFragment : Fragment() {

    private lateinit var store: ReportStore
    private lateinit var list: LinearLayout
    private var detail: FrameLayout? = null
    private var filter = ""
    private var selectedId: String? = null
    private val wide get() = resources.configuration.screenWidthDp >= 720

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        store = ReportStore.of(ctx)
        selectedId = savedInstanceState?.getString(STATE_SELECTED)
            ?: PreferenceManager.getDefaultSharedPreferences(ctx).getString(PREF_SELECTED, null)

        val pane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(if (wide) R.color.tac_panel else R.color.tac_ground))
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.tac_panel))
            setPadding(dp(16), dp(8), dp(12), dp(4))
        }
        header.addView(TextView(ctx).apply {
            text = getString(R.string.nav_reports)
            setTextColor(color(R.color.tac_text))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(MaterialButton(ctx).apply {
            text = getString(R.string.reports_new)
            setOnClickListener { open(store.create().id) }
        })
        pane.addView(header)
        pane.addView(EditText(ctx).apply {
            hint = getString(R.string.reports_search)
            textSize = 14f
            isSingleLine = true
            setTextColor(color(R.color.tac_text))
            setHintTextColor(color(R.color.tac_text_quiet))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filter = s?.toString().orEmpty().trim(); render(store.list()) }
            })
        })
        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (wide) 8 else 12), dp(4), dp(if (wide) 8 else 12), dp(24))
        }
        pane.addView(ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (!wide) return pane

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(color(R.color.tac_ground))
        }
        root.addView(pane, LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(ctx).apply { setBackgroundColor(color(R.color.tac_divider)) }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        detail = FrameLayout(ctx).apply { id = R.id.reports_detail }
        root.addView(detail, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store.all.observe(viewLifecycleOwner) { render(it) }
        if (wide) {
            val id = selectedId?.takeIf { store.get(it) != null }
            if (childFragmentManager.findFragmentById(R.id.reports_detail) == null || id == null) showDetail(id)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED, selectedId)
    }

    override fun onResume() {
        super.onResume()
        // Throw away reports that were opened and never written in (not the one open now).
        store.list().filter { isBlank(it) && it.id != selectedId }.forEach { store.delete(it.id) }
    }

    private fun isBlank(d: ReportDrafts.Draft) = d.isEmpty && d.title.isBlank() && d.schedule == null && d.lastSentMs == null

    /** Open a report: in the right-hand pane when wide, else on its own page. */
    fun open(id: String) {
        if (!wide) {
            NavHostFragment.findNavController(this).navigate(R.id.navigation_report_edit, Bundle().apply { putString(ReportEditorFragment.ARG_ID, id) })
            return
        }
        if (id == selectedId && childFragmentManager.findFragmentById(R.id.reports_detail) is ReportEditorFragment) return
        // A blank report left behind by switching away is discarded.
        selectedId?.let { old -> store.get(old)?.takeIf { isBlank(it) && old != id }?.let { store.delete(old) } }
        showDetail(id)
    }

    /** Called by the embedded editor after its report was deleted. */
    fun onDeleted(id: String) {
        if (selectedId == id) showDetail(null)
    }

    private fun showDetail(id: String?) {
        selectedId = id
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString(PREF_SELECTED, id).apply()
        val f: Fragment = if (id != null) ReportEditorFragment().apply {
            arguments = Bundle().apply { putString(ReportEditorFragment.ARG_ID, id); putBoolean(ReportEditorFragment.ARG_EMBEDDED, true) }
        } else EmptyDetail()
        childFragmentManager.beginTransaction().replace(R.id.reports_detail, f).commitAllowingStateLoss()
        render(store.list())
    }

    private fun render(all: List<ReportDrafts.Draft>) {
        if (view == null) return
        val ctx = requireContext()
        list.removeAllViews()
        val q = filter.lowercase()
        val drafts = all.filter { !isBlank(it) || it.id == selectedId }
            .filter { q.isEmpty() || listOf(it.displayTitle(), it.to, it.body, it.kind, it.onAir).any { s -> s.lowercase().contains(q) } }
            .sortedWith(compareBy<ReportDrafts.Draft> { it.schedule?.atMs ?: Long.MAX_VALUE }.thenByDescending { it.updatedMs })
        if (drafts.isEmpty()) {
            list.addView(text(getString(if (q.isEmpty()) R.string.reports_empty else R.string.reports_no_match), 14f, color(R.color.tac_text_dim)).apply {
                setPadding(dp(8), dp(16), dp(8), 0)
            })
            return
        }
        drafts.forEach { d -> list.addView(if (wide) row(d) else card(d)) }
    }

    /** Inbox row (wide screens): compact, the open one highlighted. */
    private fun row(d: ReportDrafts.Draft): View {
        val ctx = requireContext()
        val selected = d.id == selectedId
        val now = System.currentTimeMillis()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (selected) 0xFF1B2633.toInt() else color(R.color.tac_panel))
                if (selected) setStroke(dp(1), 0xFF4FC3F7.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
            isClickable = true
            isFocusable = true
            setOnClickListener { open(d.id) }
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(text(d.to.ifBlank { getString(R.string.reports_everyone) }.uppercase(), 13f, color(R.color.tac_text_dim), bold = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(text(whenShort(d.updatedMs, now), 11.5f, color(R.color.tac_text_quiet)))
            addView(top)
            val mid = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            mid.addView(badge(d))
            mid.addView(text(d.displayTitle(), 15f, bold = true).apply {
                setPadding(dp(8), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mid)
            addView(text(d.onAir.ifBlank { getString(R.string.reports_blank) }, 12.5f, color(R.color.tac_text_dim)).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            d.schedule?.let { s ->
                val overdue = s.atMs <= now && !JS8EngineService.engineRunning
                addView(text("⏱ " + ReportUi.scheduleLine(s).removePrefix("Auto-send "), 11.5f, if (overdue) 0xFFF2A33A.toInt() else 0xFF4FC3F7.toInt(), bold = true).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
            }
            if (d.lastResult == "missed") addView(text(ReportUi.lastLine(d).orEmpty(), 11.5f, 0xFFF2A33A.toInt()))
        }
    }

    /** Card (narrow screens): with its own Send button. */
    private fun card(d: ReportDrafts.Draft): View {
        val ctx = requireContext()
        val now = System.currentTimeMillis()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color(R.color.tac_panel))
                setStroke(dp(1), if (d.schedule != null) 0xFF4FC3F7.toInt() else color(R.color.tac_divider))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            isClickable = true
            isFocusable = true
            setOnClickListener { open(d.id) }
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(badge(d))
        top.addView(text(d.displayTitle(), 16.5f, bold = true).apply {
            setPadding(dp(10), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)
        card.addView(text(getString(R.string.reports_to_line, d.to.ifBlank { getString(R.string.reports_everyone) }.uppercase()) +
            (ReportUi.cachedAirtime(ctx, d)?.let { "  ·  " + it.label() } ?: ""), 12.5f, color(R.color.tac_text_dim)))
        card.addView(text(d.onAir.ifBlank { getString(R.string.reports_blank) }, 13.5f).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.MONOSPACE
        })
        d.schedule?.let { s ->
            val overdue = s.atMs <= now && !JS8EngineService.engineRunning
            card.addView(text(ReportUi.scheduleLine(s) + if (overdue) getString(R.string.reports_waiting_receiver) else "",
                12.5f, if (overdue) 0xFFF2A33A.toInt() else 0xFF4FC3F7.toInt(), bold = true))
        }
        ReportUi.lastLine(d)?.let { card.addView(text(it, 12f, if (d.lastResult == "missed") 0xFFF2A33A.toInt() else color(R.color.tac_text_quiet))) }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0) }
        row.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.reports_edit)
            setOnClickListener { open(d.id) }
        })
        row.addView(MaterialButton(ctx).apply {
            text = getString(R.string.reports_send)
            minWidth = dp(120)
            setOnClickListener { ReportUi.confirmSend(this@ReportsFragment, store.get(d.id) ?: d) }
        })
        card.addView(row)
        return card
    }

    private fun badge(d: ReportDrafts.Draft) = text(if (d.kind == ReportDrafts.FREE_TEXT) "TEXT" else d.kind, 10.5f, color(R.color.tac_text), bold = true).apply {
        setPadding(dp(6), dp(1), dp(6), dp(1))
        background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(color(R.color.tac_divider)) }
    }

    private fun whenShort(ms: Long, now: Long): String {
        if (ms <= 0) return ""
        val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(ms)) == it.format(Date(now)) }
        return SimpleDateFormat(if (sameDay) "h:mm a" else "MMM d", Locale.getDefault()).format(Date(ms))
    }

    private fun text(s: CharSequence, size: Float, c: Int = color(R.color.tac_text), bold: Boolean = false) = TextView(requireContext()).apply {
        text = s
        textSize = size
        setTextColor(c)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, 0)
    }

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Right-hand pane when nothing is open. */
    class EmptyDetail : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            TextView(requireContext()).apply {
                text = getString(R.string.reports_pick)
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.tac_text_quiet))
                setBackgroundColor(ContextCompat.getColor(context, R.color.tac_ground))
            }
    }

    companion object {
        private const val STATE_SELECTED = "selected"
        private const val PREF_SELECTED = "reports_selected"
    }
}
