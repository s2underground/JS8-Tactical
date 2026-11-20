package com.js8call.example.reports

import com.js8call.example.reports.ReportDrafts.Draft
import com.js8call.example.reports.ReportDrafts.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDraftsTest {
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    @Test
    fun storageRoundTripsAwkwardText() {
        val drafts = listOf(
            Draft("a", "Morning\tSITREP", "SITREP", "@TEAM", "", linkedMapOf("S" to "GREEN", "N" to "back\\slash\nnew line"),
                t0, t0 + 1, Schedule(t0 + 60 * min, 240), t0 - 5, "auto"),
            Draft("b", "", ReportDrafts.FREE_TEXT, "", "Line one\n\nLine \\n two\twith tab", emptyMap(), t0, t0),
        )
        val back = ReportDrafts.decode(ReportDrafts.encode(drafts))
        assertEquals(drafts, back)
    }

    @Test
    fun decodeSkipsJunk() {
        assertTrue(ReportDrafts.decode("nonsense\n\nv1\t\t").isEmpty())
        assertTrue(ReportDrafts.decode(null).isEmpty())
    }

    @Test
    fun onAirTextIsOneUpperCaseLine() {
        val free = Draft("x", body = "Bridge out at mile 12.\n\nUse  the ford — 2 km north.")
        assertEquals("BRIDGE OUT AT MILE 12. USE THE FORD 2 KM NORTH.", free.onAir)
        val form = Draft("y", kind = "SITREP", values = mapOf("S" to "amber", "R" to "water"))
        assertEquals("#SITREP S:AMBER R:WATER", form.onAir)
        val wx = Draft("z", kind = "WX", values = mapOf("C" to "rain", "TEMP" to "41F", "W" to "NW 15"))
        assertEquals("#WX C:RAIN TEMP:41F W:NW 15", wx.onAir)
        val parsed = Reports.parse(wx.onAir)!!
        assertEquals("41F", parsed.values["TEMP"])
        assertEquals("NW 15", parsed.values["W"])
        assertEquals("RAIN", parsed.values["C"])
    }

    @Test
    fun dueSendsOnTimeSkipsLateAndMovesRepeatsOn() {
        val onTime = Draft("on", body = "A", schedule = Schedule(t0 - 1 * min))
        val late = Draft("late", body = "B", schedule = Schedule(t0 - 45 * min, 60))
        val future = Draft("fut", body = "C", schedule = Schedule(t0 + 10 * min))
        val repeat = Draft("rep", body = "D", schedule = Schedule(t0 - 2 * min, 240))
        val none = Draft("none", body = "E")
        val due = ReportDrafts.due(listOf(onTime, late, future, repeat, none), t0)
        assertEquals(listOf("on", "rep"), due.send.map { it.id })
        assertEquals(listOf("late"), due.missed.map { it.id })
        val after = due.updated.associateBy { it.id }
        assertNull(after.getValue("on").schedule)
        assertEquals("auto", after.getValue("on").lastResult)
        assertEquals(t0 - 45 * min + 60 * min, after.getValue("late").schedule!!.atMs)
        assertEquals("missed", after.getValue("late").lastResult)
        assertEquals(t0 + 10 * min, after.getValue("fut").schedule!!.atMs)
        assertEquals(t0 - 2 * min + 240 * min, after.getValue("rep").schedule!!.atMs)
        assertEquals(none, after.getValue("none"))
        // Running again at once sends nothing more.
        val again = ReportDrafts.due(due.updated, t0 + 1000)
        assertTrue(again.send.isEmpty() && again.missed.isEmpty())
    }

    @Test
    fun repeatsSkipWholeMissedPeriods() {
        val s = Schedule(t0, 60)
        assertEquals(t0 + 5 * 60 * min, ReportDrafts.nextSchedule(s, t0 + 4 * 60 * min + 30 * min)!!.atMs)
        assertNull(ReportDrafts.nextSchedule(Schedule(t0, 0), t0))
    }

    @Test
    fun emptyScheduledReportIsNotSent() {
        val due = ReportDrafts.due(listOf(Draft("e", body = "   ", schedule = Schedule(t0))), t0)
        assertTrue(due.send.isEmpty())
    }
}
