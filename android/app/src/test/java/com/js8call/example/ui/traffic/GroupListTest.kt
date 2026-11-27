package com.js8call.example.ui.traffic

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupListTest {
    private val heard = listOf("@GHOSTNET", "@HB", "@NCNET", "@TEXAS")

    @Test fun offShowsOnlyWatchedGroups() {
        assertEquals(listOf("@GHOSTNET"), GroupList.visible(listOf("@GHOSTNET"), heard, emptySet(), autoShow = false, selected = null))
    }

    @Test fun onAddsHeardGroupsButNeverHb() {
        assertEquals(
            listOf("@GHOSTNET", "@NCNET", "@TEXAS"),
            GroupList.visible(listOf("@GHOSTNET"), heard, emptySet(), autoShow = true, selected = null),
        )
    }

    @Test fun aHiddenGroupStaysHiddenWhileTrafficKeepsComing() {
        // Build 344: "Stop watching" @NCNET did nothing; the next message to it brought it back.
        assertEquals(
            listOf("@GHOSTNET", "@TEXAS"),
            GroupList.visible(listOf("@GHOSTNET"), heard, setOf("@NCNET"), autoShow = true, selected = null),
        )
    }

    @Test fun theGroupBeingViewedKeepsItsRow() {
        assertEquals(listOf("@TEXAS"), GroupList.visible(emptyList(), heard, emptySet(), autoShow = false, selected = "@TEXAS"))
    }

    @Test fun hideAndUnhide() {
        val h = GroupList.hide("@NCNET", "@texas")
        assertEquals(listOf("@NCNET", "@TEXAS"), GroupList.parse(h))
        assertEquals(listOf("@TEXAS"), GroupList.parse(GroupList.unhide(h, "@NCNET")))
        assertEquals("@NCNET", GroupList.hide("@NCNET", "@NCNET"))
    }
}
