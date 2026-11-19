package com.js8call.example.ui.library

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.js8call.example.R
import com.js8call.example.library.GuideText
import com.js8call.example.library.Library
import com.js8call.example.ui.onboarding.RoleChooser

/**
 * The Library: guides written for people who have never used a radio, the
 * antenna calculators, and (later) public-release reference manuals. All
 * offline. Search covers every guide's text.
 */
class LibraryFragment : Fragment() {

    private lateinit var list: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
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
            text = getString(R.string.nav_library)
            setTextColor(color(R.color.tac_text))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "I am a…"
            setOnClickListener { RoleChooser.show(this@LibraryFragment) }
        })
        root.addView(header)

        val search = EditText(ctx).apply {
            hint = "Search the Library (e.g. dipole, SWR, listen)"
            setSingleLine()
            textSize = 15f
            setTextColor(color(R.color.tac_text))
            setHintTextColor(color(R.color.tac_text_quiet))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(color(R.color.tac_card))
        }
        root.addView(search)

        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(32))
        }
        root.addView(ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = show(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        show("")
        return root
    }

    private fun show(query: String) {
        val ctx = requireContext()
        list.removeAllViews()
        val guides = Library.search(ctx, query)
        if (guides.isEmpty()) {
            list.addView(text("Nothing found for \"$query\".", 15f, color(R.color.tac_text_dim)).apply { setPadding(dp(4), dp(16), 0, 0) })
            return
        }
        val role = PreferenceManager.getDefaultSharedPreferences(ctx).getString(RoleChooser.KEY, null)
        val start = role?.let { Library.startGuideFor(it) }
        guides.groupBy { it.meta.section }.forEach { (section, items) ->
            list.addView(text(section.uppercase(), 12f, color(R.color.tac_text_dim), bold = true).apply {
                letterSpacing = 0.08f
                setPadding(dp(4), dp(14), 0, dp(6))
            })
            items.forEach { list.addView(card(it, highlight = it.meta.id == start)) }
        }
        if (query.isBlank()) {
            list.addView(text("REFERENCE MANUALS", 12f, color(R.color.tac_text_dim), bold = true).apply {
                letterSpacing = 0.08f
                setPadding(dp(4), dp(14), 0, dp(6))
            })
            list.addView(text("Public-release US Army radio and antenna manuals will be offered as an optional download in a later version.", 14f, color(R.color.tac_text_quiet)).apply {
                setPadding(dp(4), 0, dp(4), 0)
            })
        }
    }

    private fun card(g: GuideText.Guide, highlight: Boolean): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color(R.color.tac_card))
                setStroke(dp(if (highlight) 2 else 1), if (highlight) color(R.color.tac_accent) else color(R.color.tac_border))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            if (highlight) addView(text("START HERE", 11f, color(R.color.tac_accent), bold = true))
            addView(text(g.meta.title, 16f, color(R.color.tac_text), bold = true))
            if (g.meta.summary.isNotBlank()) addView(text(g.meta.summary, 13f, color(R.color.tac_text_dim)).apply { setPadding(0, dp(2), 0, 0) })
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            foreground = ContextCompat.getDrawable(ctx, tv.resourceId)
            setOnClickListener { AppLinks.openGuide(this@LibraryFragment, g.meta.id) }
        }
    }

    private fun text(s: String, size: Float, c: Int, bold: Boolean = false) = TextView(requireContext()).apply {
        text = s
        textSize = size
        setTextColor(c)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
