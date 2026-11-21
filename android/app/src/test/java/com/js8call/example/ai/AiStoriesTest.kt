package com.js8call.example.ai

import com.js8call.example.digest.DigestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStoriesTest {
    private var id = 0L
    private fun msg(from: String, body: String, t: Long) = DigestBuilder.Msg(id++, from, "@GHOSTNET", body, t, true, 0, null)

    private val cableA = "@GHOSTNET MSG ,V54,QST SUBSEA // PAKISTAN CABLE FAULT PAKISTAN TELECOM REGULATOR CONFIRMED A FAULT IN THE SEA-ME-WE 5 UNDERSEA CABLE NEAR KARACHI"
    private val cableB = "SEA-ME-WE 5 UNDERSEA CABLE DAMAGED NEAR KARACHI, PAKISTAN. TRAFFIC REROUTED. REGULATOR INVESTIGATING THE FAULT"
    private val vara = "VARA HF MODEM DEVELOPER HAS RELEASED ANOTHER UPDATE THAT RESTORES REVERSE COMPATIBILITY WITH PREVIOUS VERSIONS"

    @Test
    fun sameTextIsOneSourceRelayed_differentWordingIsIndependent() {
        val stories = AiStories.build(listOf(
            msg("K9DGY", cableA, 1000),
            msg("W4ABC", "W4ABC: $cableA", 2000),   // relayed copy
            msg("N0XG", vara, 1500),
            msg("KD2XYZ", cableB, 3000),            // independent wording
        ))
        assertEquals(2, stories.size)
        val cable = stories.first()
        assertEquals(listOf("K9DGY", "KD2XYZ"), cable.independent)
        assertEquals(listOf("W4ABC"), cable.relayedBy)
        assertEquals("2 independent sources: K9DGY, KD2XYZ · relayed by W4ABC", AiStories.sourceLine(cable))
        assertEquals("1 source: N0XG", AiStories.sourceLine(stories[1]))
    }

    @Test
    fun shortAndReportMessagesAreNotStories() {
        val stories = AiStories.build(listOf(msg("K1A", "CQ CQ", 1), msg("K1B", "#CHECKIN S:GOOD L:EM73 N:ALL FINE HERE WITH PLENTY OF WATER AND POWER FOR NOW THANKS", 2)))
        assertTrue(stories.isEmpty())
    }

    @Test
    fun sentenceCaseKeepsCallsignsAndAcronyms() {
        assertEquals("VARA HF modem update released. Contact K4ABC or the EOC.",
            AiStories.sentenceCase("VARA HF MODEM UPDATE RELEASED. CONTACT K4ABC OR THE EOC."))
    }
}
