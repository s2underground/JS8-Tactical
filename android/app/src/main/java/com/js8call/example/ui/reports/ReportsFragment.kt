package com.js8call.example.ui.reports

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.button.MaterialButton
import com.js8call.example.R
import com.js8call.example.reports.ReportDrafts
import com.js8call.example.reports.ReportStore
import com.js8call.example.service.JS8EngineService

/** The Reports tab: saved reports, each one tap (plus a confirm) from the air. */
class ReportsFragment : Fragment() {

    private lateinit var store: ReportStore
    private lateinit var body: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        store = ReportStore.of(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.tac_ground))
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.tac_panel))
            setPadding(dp(16), dp(8), dp(12), dp(8))
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
        root.addView(header)
        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(24))
        }
        root.addView(ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store.all.observe(viewLifecycleOwner) { render(it) }
    }

    override fun onResume() {
        super.onResume()
        // Throw away reports that were opened and never written in.
        store.list().filter { it.isEmpty && it.title.isBlank() && it.schedule == null && it.lastSentMs == null }.forEach { store.delete(it.id) }
    }

    private fun open(id: String) {
        NavHostFragment.findNavController(this).navigate(R.id.navigation_report_edit, Bundle().apply { putString(ReportEditorFragment.ARG_ID, id) })
    }

    private fun render(all: List<ReportDrafts.Draft>) {
        val ctx = requireContext()
        body.removeAllViews()
        val drafts = all.filterNot { it.isEmpty && it.title.isBlank() && it.schedule == null && it.lastSentMs == null }
            .sortedWith(compareBy<ReportDrafts.Draft> { it.schedule?.atMs ?: Long.MAX_VALUE }.thenByDescending { it.updatedMs })
        if (drafts.isEmpty()) {
            body.addView(text(getString(R.string.reports_empty), 15f, color(R.color.tac_text_dim)).apply { setPadding(dp(8), dp(24), dp(8), 0) })
            return
        }
        val now = System.currentTimeMillis()
        drafts.forEach { d ->
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
            top.addView(text(if (d.kind == ReportDrafts.FREE_TEXT) "TEXT" else d.kind, 11f, color(R.color.tac_text), bold = true).apply {
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(color(R.color.tac_divider)) }
            })
            top.addView(text(d.displayTitle(), 16.5f, bold = true).apply {
                setPadding(dp(10), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(top)
            card.addView(text(getString(R.string.reports_to_line, d.to.ifBlank { getString(R.string.reports_everyone) }.uppercase()) +
                (ReportUi.airtime(ctx, d)?.let { "  ·  " + it.label() } ?: ""), 12.5f, color(R.color.tac_text_dim)))
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
            body.addView(card)
        }
    }

    private fun text(s: CharSequence, size: Float, c: Int = color(R.color.tac_text), bold: Boolean = false) = TextView(requireContext()).apply {
        text = s
        textSize = size
        setTextColor(c)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, 0)
    }

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
