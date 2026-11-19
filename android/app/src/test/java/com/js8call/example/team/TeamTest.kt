package com.js8call.example.team

import com.js8call.example.prop.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamTest {

    @Test
    fun rosterRoundTripAndEdits() {
        val text = TeamRoster.encode(listOf(
            TeamMember("kx4abc", "Bob | medic", "Alpha", "em83"),
            TeamMember("W1AW"),
        ))
        val back = TeamRoster.decode(text)
        assertEquals("KX4ABC", back[0].call)
        assertEquals("Bob / medic", back[0].name)       // separator can't break the record
        assertEquals("EM83", back[0].grid)
        assertEquals(TeamMember("W1AW"), back[1])

        // Same base callsign replaces; editing can change the callsign.
        val edited = TeamRoster.upsert(back, TeamMember("KX4ABC/P", "Bob"), replacing = "KX4ABC")
        assertEquals(2, edited.size)
        assertEquals("KX4ABC/P", edited[0].call)
        assertEquals(1, TeamRoster.remove(edited, "kx4abc").size)
        assertTrue(TeamRoster.decode("\n\n").isEmpty())
    }

    @Test
    fun latestStatusWins() {
        val st = TeamBoard.latestStatuses(listOf(
            TeamBoard.Msg("W4QRP", "#CHECKIN S:NEED L:EM83 N:WATER", 1_000L),
            TeamBoard.Msg("W4QRP/P", "#CHECKIN S:GOOD L:EM83", 2_000L),
            TeamBoard.Msg("N0DEF", "#SITREP S:RED R:MEDICAL", 3_000L),
            TeamBoard.Msg("N0DEF", "HELLO", 4_000L),
        ))
        assertEquals(TeamBoard.Tone.GREEN, st["W4QRP"]!!.tone)
        assertEquals("SITREP RED", st["N0DEF"]!!.label)
        assertEquals("MEDICAL", st["N0DEF"]!!.detail)
    }

    @Test
    fun boardUsesHeardGridThenEnteredGrid() {
        val members = listOf(TeamMember("KX4ABC", grid = "FN31"), TeamMember("W1AW", grid = "FN31"), TeamMember("N0NE"))
        val heard = mapOf("KX4ABC" to TeamBoard.Heard(5_000L, -12, "EM73"))
        var asked = 0
        val rows = TeamBoard.build(members, heard, emptyMap()) { _, band ->
            asked++
            if (band == Band.B40) 0.8 else 0.2
        }
        assertEquals("from heard grid EM73", rows[0].positionNote)
        assertEquals("from entered grid FN31", rows[1].positionNote)
        assertEquals(Band.B40, rows[0].bestBand)
        assertEquals(TeamBoard.Light.GO, TeamBoard.light(rows[0].reach[Band.B40]))
        assertTrue(rows[2].reach.isEmpty())
        assertNull(rows[2].bestBand)
        assertEquals(2 * TeamBoard.BANDS.size, asked)

        // No own position: no reach at all.
        assertTrue(TeamBoard.build(members, heard, emptyMap(), null).all { it.reach.isEmpty() })
    }

    @Test
    fun textAndLights() {
        assertEquals("Not heard yet", TeamBoard.heardText(null, 0))
        assertEquals("Heard 12 min ago · -14 dB", TeamBoard.heardText(TeamBoard.Heard(0, -14, null), 12 * 60_000L))
        assertEquals(TeamBoard.Light.CAUTION, TeamBoard.light(0.4))
        assertEquals(TeamBoard.Light.POOR, TeamBoard.light(0.12))
        assertEquals(TeamBoard.Light.NONE, TeamBoard.light(0.01))
        val rows = TeamBoard.build(listOf(TeamMember("KX4ABC", "Bob", "Alpha")), emptyMap(), emptyMap(), null)
        val text = TeamBoard.toText(rows, 0)
        assertTrue(text, text.contains("ALPHA") && text.contains("KX4ABC (Bob): Not heard yet"))
    }
}
