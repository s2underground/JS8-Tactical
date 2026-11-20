package com.js8call.example.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Material's NavigationRailView takes at most 7 items and BottomNavigationView at most 5;
 * one more crashes the app on launch. Guard every menu variant.
 */
class NavMenuLimitsTest {
    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun items(variant: String): Int =
        Regex("<item\\b").findAll(File(res(), "$variant/bottom_nav_menu.xml").readText()).count()

    @Test
    fun bottomBarFitsFive() {
        assertTrue("phone bottom bar has ${items("menu")} items (max 5)", items("menu") <= 5)
    }

    @Test
    fun railsFitSeven() {
        res().listFiles { f -> f.isDirectory && f.name.startsWith("menu-") && File(f, "bottom_nav_menu.xml").exists() }!!
            .forEach { dir ->
                val n = items(dir.name)
                assertTrue("${dir.name} rail has $n items (max 7)", n <= 7)
            }
    }
}
