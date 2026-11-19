package com.js8call.example.ui.library

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.js8call.example.R
import com.js8call.example.library.GuideText
import com.js8call.example.library.GuideText.Block
import com.js8call.example.library.Library
import com.js8call.example.ui.traffic.TrafficViewModel

/** Reads one Library guide: text, call-outs, diagrams, calculators and buttons into the app. */
class GuideFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val id = arguments?.getString(ARG_ID).orEmpty()
        val guide = Library.get(ctx, id)
        val band = try { ViewModelProvider(requireActivity())[TrafficViewModel::class.java].currentBand() } catch (_: Exception) { null }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.tac_ground))
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.tac_panel))
            setPadding(dp(4), dp(4), dp(16), dp(4))
        }
        header.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "‹ Library"
            setOnClickListener { findNavController().popBackStack() }
        })
        header.addView(TextView(ctx).apply {
            text = guide?.meta?.section.orEmpty()
            setTextColor(color(R.color.tac_text_dim))
            textSize = 13f
        })
        root.addView(header)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(40))
        }
        // Readable line length on tablets.
        val scroll = ScrollView(ctx).apply {
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (guide == null) {
            body.addView(para("This guide isn't in this version of the app."))
            return root
        }
        body.addView(TextView(ctx).apply {
            text = guide.meta.title
            setTextColor(color(R.color.tac_text))
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (guide.meta.summary.isNotBlank()) body.addView(para(guide.meta.summary, dim = true))
        guide.blocks.forEach { b -> body.addView(render(b, band)) }
        return root
    }

    private fun render(b: Block, band: com.js8call.example.prop.Band?): View {
        val ctx = requireContext()
        return when (b) {
            is Block.Heading -> TextView(ctx).apply {
                text = b.text
                setTextColor(if (b.level == 1) color(R.color.tac_accent) else color(R.color.tac_text))
                textSize = if (b.level == 1) 20f else 17f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(if (b.level == 1) 22 else 16), 0, dp(4))
            }
            is Block.Paragraph -> para(b.text)
            is Block.Bullets -> list(b.items, numbered = false)
            is Block.Steps -> list(b.items, numbered = true)
            is Block.Callout -> callout(b)
            is Block.Diagram -> LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(6))
                addView(DiagramView(ctx, b.id))
                if (b.caption.isNotBlank()) addView(para(b.caption, dim = true, size = 13f))
            }
            is Block.Calc -> spaced(CalcViews.create(ctx, b.id, band))
            is Block.AppLink -> button("→ ${b.label}") { if (AppLinks.known(b.target)) AppLinks.open(this, b.target) }
            is Block.GuideLink -> button("📖 ${b.label}") { AppLinks.openGuide(this, b.guideId) }
        }
    }

    private fun styled(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        GuideText.inline(text).forEach { (part, bold) ->
            val start = sb.length
            sb.append(part)
            if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun para(text: String, dim: Boolean = false, size: Float = 16f) = TextView(requireContext()).apply {
        this.text = styled(text)
        setTextColor(if (dim) color(R.color.tac_text_dim) else color(R.color.tac_text))
        textSize = size
        setLineSpacing(0f, 1.25f)
        setPadding(0, dp(6), 0, dp(6))
        setTextIsSelectable(true)
    }

    private fun list(items: List<String>, numbered: Boolean) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        items.forEachIndexed { i, item ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply {
                    text = if (numbered) "${i + 1}." else "•"
                    setTextColor(color(R.color.tac_accent))
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    minWidth = dp(26)
                })
                addView(TextView(context).apply {
                    text = styled(item)
                    setTextColor(color(R.color.tac_text))
                    textSize = 16f
                    setLineSpacing(0f, 1.25f)
                    setTextIsSelectable(true)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setPadding(0, dp(3), 0, dp(3))
            })
        }
    }

    private fun callout(b: Block.Callout): View {
        val (label, fg, bg, border) = when (b.kind) {
            GuideText.Kind.WARNING -> CalloutStyle("WARNING", 0xFFFF7B7B.toInt(), 0x29FF5A5A, 0xFF7A2A2A.toInt())
            GuideText.Kind.TIP -> CalloutStyle("TIP", color(R.color.tac_good_text), color(R.color.tac_good_tint), 0xFF24523C.toInt())
            GuideText.Kind.NOTE -> CalloutStyle("NOTE", color(R.color.tac_info_text), color(R.color.tac_info_tint), 0xFF23465A.toInt())
        }
        return spaced(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(bg); setStroke(dp(1), border) }
            addView(TextView(context).apply {
                text = label
                setTextColor(fg)
                textSize = 12f
                letterSpacing = 0.08f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = styled(b.text)
                setTextColor(color(R.color.tac_text))
                textSize = 15f
                setLineSpacing(0f, 1.2f)
                setTextIsSelectable(true)
            })
        })
    }

    private fun button(label: String, onClick: () -> Unit) = spaced(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    })

    private fun spaced(v: View): View {
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        }
        return v
    }

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private data class CalloutStyle(val label: String, val fg: Int, val bg: Int, val border: Int)

    companion object {
        const val ARG_ID = "guide"
    }
}
