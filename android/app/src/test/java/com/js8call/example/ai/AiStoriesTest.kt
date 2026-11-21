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
    fun repliesToOneStationAreNotStories() {
        val reply = DigestBuilder.Msg(id++, "W9ZOM", "K9DGY",
            "AWESOME UPDATE. DO U THINK THIS LONG TERM ORCHESTRATED ATTACK IS SUPPOSED TO RESULT IN BIG GRID DOWN EVENT?", 2000, true, 0, null)
        val stories = AiStories.build(listOf(msg("K9DGY", cableA, 1000), reply))
        assertEquals(listOf("K9DGY"), stories.map { it.independent.single() })
        assertTrue(DigestBuilder.isBulletin(reply))   // still listed under Bulletins and long messages
    }

    @Test
    fun previewEndsAtASentenceOrWord() {
        val text = "AI infrastructure risk [FM18] GAO found all 4 major AI platforms produced feasible attack plans in under 12 HRS after safeguard bypasses. Two generated US substation targeting plans: one drone-based, one firearms-based."
        assertEquals(text.substringBefore(" Two") + " …", AiStories.preview(text, 160))
        val noStop = "ONE TWO THREE FOUR FIVE SIX SEVEN EIGHT NINE TEN ELEVEN TWELVE"
        assertEquals("ONE TWO THREE FOUR FIVE…", AiStories.preview(noStop, 27))
        assertEquals("short", AiStories.preview("short", 27))
    }

    @Test
    fun sentenceCaseKeepsCallsignsAndAcronyms() {
        assertEquals("VARA HF modem update released. Contact K4ABC or the EOC.",
            AiStories.sentenceCase("VARA HF MODEM UPDATE RELEASED. CONTACT K4ABC OR THE EOC."))
    }
}

class PartialBulletinTest {
    private fun msg(id: Long, sender: String, body: String, complete: Boolean, missing: Int) =
        com.js8call.example.digest.DigestBuilder.Msg(id, sender, "@GHOSTNET", body, 1_000L * id, complete, missing, -12)

    private val text = "QST AVIATION // TERROR ALERT FBI/DHS/TSA ISSUED A NOV 19 ADVISORY TO U.S. AIRLINES, AIRPORTS AND CARGO OPERATORS […] AFFILIATES PRAISED SEPTEMBER S THWARTED FLYDUBAI COCKPIT HIJACKING"

    @org.junit.Test fun aFewMissingFramesStillMakeABulletin() {
        org.junit.Assert.assertTrue(com.js8call.example.digest.DigestBuilder.isBulletin(msg(1, "K9DGY", text, false, 1)))
        org.junit.Assert.assertFalse(com.js8call.example.digest.DigestBuilder.isBulletin(msg(1, "K9DGY", text, false, 4)))
        org.junit.Assert.assertEquals("incomplete: 1 frame missing", com.js8call.example.digest.DigestBuilder.incompleteText(msg(1, "K9DGY", text, false, 1)))
    }

    @org.junit.Test fun storyUsesACompleteCopyWhenThereIsOne() {
        val partialOnly = AiStories.build(listOf(msg(1, "K9DGY", text, false, 1)))
        org.junit.Assert.assertTrue(partialOnly.single().partial)
        val full = text.replace(" […]", " WARNING THAT")
        val both = AiStories.build(listOf(msg(1, "K9DGY", text, false, 1), msg(2, "N0DDK", full, true, 0)))
        org.junit.Assert.assertFalse(both.first().partial)
    }
}
