package com.js8call.example.ui.library

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.core.os.bundleOf
import com.google.android.material.navigation.NavigationBarView
import com.js8call.example.R

/** Buttons in guides that open part of the app: `[[app:target|Label]]`. */
object AppLinks {
    private val TARGETS = mapOf(
        "traffic" to R.id.navigation_traffic,
        "team" to R.id.navigation_team,
        "map" to R.id.navigation_map,
        "prop" to R.id.navigation_propagation,
        "radio" to R.id.navigation_monitor,
        "digest" to R.id.navigation_digest,
        "settings" to R.id.navigation_settings,
        "library" to R.id.navigation_library,
    )

    fun known(target: String) = target in TARGETS

    /** Switch tab if it is on the bar/rail, else navigate to it. */
    fun open(f: Fragment, target: String) {
        val dest = TARGETS[target] ?: return
        val bar = f.requireActivity().findViewById<NavigationBarView>(R.id.bottom_navigation)
        if (bar?.menu?.findItem(dest) != null) bar.selectedItemId = dest
        else NavHostFragment.findNavController(f).navigate(dest)
    }

    fun openGuide(f: Fragment, guideId: String) {
        NavHostFragment.findNavController(f).navigate(R.id.navigation_guide, bundleOf(GuideFragment.ARG_ID to guideId))
    }
}
