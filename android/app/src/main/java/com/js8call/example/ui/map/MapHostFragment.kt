package com.js8call.example.ui.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.js8call.example.R
import com.js8call.example.ui.prop.PropagationFragment

/**
 * The Map tab: two views of the same map, Stations (who is where, paths, reports) and
 * Propagation (predicted coverage and solar data). A toggle at the top of each view's panel
 * switches between them; the choice is remembered.
 */
class MapHostFragment : Fragment() {

    /** Fixed id: a restored child is put back into a container with the same id (a new id each time left the tab blank). */
    private val containerId = R.id.map_host_container

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = containerId }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (childFragmentManager.findFragmentById(containerId) == null) show(mode(), animate = false)
    }

    private fun mode(): String = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(PREF_MODE, STATIONS) ?: STATIONS

    fun show(mode: String, animate: Boolean = true) {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString(PREF_MODE, mode).apply()
        val f: Fragment = if (mode == PROP) PropagationFragment() else MapFragment()
        childFragmentManager.beginTransaction().apply {
            if (animate) setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            replace(containerId, f)
        }.commit()
    }

    companion object {
        const val PREF_MODE = "map_mode"
        /** Map control panel folded down to just the Stations / Propagation toggle. */
        const val PREF_COLLAPSED = "map_panel_collapsed"
        const val STATIONS = "stations"
        const val PROP = "prop"

        /**
         * Put the Stations / Propagation toggle at the top of [panel] (a view's control panel).
         * Does nothing if [child] isn't hosted on the Map tab.
         */
        fun addToggle(child: Fragment, panel: LinearLayout, current: String) {
            val host = child.parentFragment as? MapHostFragment ?: return
            val ctx = child.requireContext()
            val dp = ctx.resources.displayMetrics.density
            val group = MaterialButtonToggleGroup(ctx).apply {
                isSingleSelection = true
                isSelectionRequired = true
                setPadding(0, 0, 0, (4 * dp).toInt())
            }
            fun button(label: Int, mode: String) = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                setText(label)
                minHeight = (36 * dp).toInt()
                insetTop = 0; insetBottom = 0
                group.addView(this)
                if (mode == current) group.check(id)
                setOnClickListener { if (mode != current) host.show(mode) }
            }
            button(R.string.map_mode_stations, STATIONS)
            button(R.string.map_mode_prop, PROP)

            // The rest of the panel goes in a body that the minimise button folds away, leaving
            // just the toggle row over the map. Views keep their ids, so the fragments still find them.
            val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            while (panel.childCount > 0) {
                val v = panel.getChildAt(0)
                val lp = v.layoutParams
                panel.removeViewAt(0)
                body.addView(v, lp)
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val fold = MaterialButton(ctx, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                setIconResource(R.drawable.ic_arrow_drop_down)
                iconSize = (28 * dp).toInt()
                minWidth = (40 * dp).toInt(); minHeight = (36 * dp).toInt()
                insetTop = 0; insetBottom = 0
                iconTint = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(ctx, R.color.tac_text))
            }
            fun apply(collapsed: Boolean) {
                body.visibility = if (collapsed) View.GONE else View.VISIBLE
                fold.rotation = if (collapsed) 0f else 180f   // down = open me, up = fold away
                fold.contentDescription = ctx.getString(if (collapsed) R.string.map_panel_expand else R.string.map_panel_collapse)
                group.setPadding(0, 0, 0, if (collapsed) 0 else (4 * dp).toInt())
            }
            fold.setOnClickListener {
                val collapsed = !prefs.getBoolean(PREF_COLLAPSED, false)
                prefs.edit().putBoolean(PREF_COLLAPSED, collapsed).apply()
                apply(collapsed)
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(group)
                addView(fold, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (4 * dp).toInt() })
            }
            panel.addView(row)
            panel.addView(body)
            apply(prefs.getBoolean(PREF_COLLAPSED, false))
        }
    }
}
