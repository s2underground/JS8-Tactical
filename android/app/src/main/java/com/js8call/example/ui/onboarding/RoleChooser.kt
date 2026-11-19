package com.js8call.example.ui.onboarding

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.js8call.example.R
import com.js8call.example.library.Library
import com.js8call.example.ui.library.GuideFragment

/**
 * First-run question: Listener, Operator or Net control. It only changes what
 * the app shows first (the matching "Start here" guide) and, for Listener,
 * hides the send controls on Traffic. Nothing is locked: change it any time
 * in Settings or the Library.
 */
object RoleChooser {
    const val KEY = "user_role"
    const val LISTENER = "listener"
    const val OPERATOR = "operator"
    const val NET_CONTROL = "netcontrol"

    fun role(ctx: android.content.Context): String? =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(KEY, null)

    fun isListener(ctx: android.content.Context) = role(ctx) == LISTENER

    /** On first launch only. */
    fun showIfFirstRun(activity: FragmentActivity) {
        if (role(activity) == null) show(activity)
    }

    fun show(fragment: Fragment) = show(fragment.requireActivity())

    fun show(activity: FragmentActivity) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val current = role(activity)
        lateinit var dialog: AlertDialog
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        box.addView(TextView(activity).apply {
            text = "This sets what the app shows you first. You can change it any time in Settings."
            setTextColor(ContextCompat.getColor(activity, R.color.tac_text_dim))
            textSize = 14f
            setPadding(0, 0, 0, dp(10))
        })
        listOf(
            Triple(LISTENER, "I want to listen", "I have a shortwave receiver (or a radio I won't transmit with). Show me how to hear and read JS8 traffic. Send controls are hidden."),
            Triple(OPERATOR, "I want to send and receive", "I have, or will have, a transceiver. Show me how to set up my station and antenna and make my first contact."),
            Triple(NET_CONTROL, "I run a net or a team", "I'm the radio operator for a group. Show me the Team tab, groups, reports and the Digest."),
        ).forEach { (key, title, detail) ->
            box.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(ContextCompat.getColor(activity, R.color.tac_card))
                    setStroke(dp(if (key == current) 2 else 1),
                        ContextCompat.getColor(activity, if (key == current) R.color.tac_accent else R.color.tac_border))
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                addView(TextView(activity).apply {
                    text = title
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(activity, R.color.tac_text))
                })
                addView(TextView(activity).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(activity, R.color.tac_text_dim))
                    setPadding(0, dp(3), 0, 0)
                })
                val tv = TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                foreground = ContextCompat.getDrawable(activity, tv.resourceId)
                isClickable = true
                setOnClickListener {
                    PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(KEY, key).apply()
                    dialog.dismiss()
                    openStartGuide(activity, key)
                }
            })
        }
        val skipLabel: CharSequence = if (current == null) "Skip for now" else activity.getString(android.R.string.cancel)
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (current == null) "Welcome to JS8-Tactical" else "I am a…")
            .setView(android.widget.ScrollView(activity).apply { addView(box) })
            .setNegativeButton(skipLabel) { _, _ ->
                // Skipping counts as Operator (everything shown), so the question isn't repeated.
                if (current == null) PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(KEY, OPERATOR).apply()
            }
            .setCancelable(current != null)
            .show()
    }

    private fun openStartGuide(activity: FragmentActivity, role: String) {
        try {
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
            host.navController.navigate(R.id.navigation_guide, bundleOf(GuideFragment.ARG_ID to Library.startGuideFor(role)))
        } catch (e: Exception) {
            android.util.Log.w("RoleChooser", "could not open start guide", e)
        }
    }
}
