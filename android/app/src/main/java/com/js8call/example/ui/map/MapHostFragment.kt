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
            panel.addView(group, 0)
        }
    }
}
