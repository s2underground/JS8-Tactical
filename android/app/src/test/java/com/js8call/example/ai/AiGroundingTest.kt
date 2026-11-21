package com.js8call.example.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiGroundingTest {
    private fun flagged(text: String, vararg src: String) =
        AiGrounding.unsupported(text, src.toList()).map { text.substring(it.first, it.last + 1) }

    @Test fun inventedNameIsFlagged() {
        assertEquals(listOf("MTI"), flagged("VARA links are down, affecting MTI.", "VARA HF OUTAGE ON 40M. ACKS TURNED OFF. AFFECTING MT"))
    }

    @Test fun namesAndFiguresInTheMessagesPass() {
        assertTrue(flagged("A cable fault near Pakistan slowed traffic by ~50% [1]. Rerouting is 11 km long.",
            "SUBSEA // PAKISTAN CABLE FAULT. ~50% OF AFFECTED TRAFFIC REROUTED. DETOUR 11KM").isEmpty())
    }

    @Test fun wrongFigureIsFlagged() {
        assertEquals(listOf("5"), flagged("The detour is 5 km.", "DETOUR 11KM"))
    }

    @Test fun ordinaryWordsAndSentenceStartsAreNotChecked() {
        assertTrue(flagged("Stations say the outage continues. Operators should listen.", "GRID DOWN IN EN52").isEmpty())
    }

    @Test fun callsignsAndGrids() {
        assertEquals(listOf("KF4XYZ"), flagged("Reported by KF4XYZ in EN52.", "GRID DOWN IN EN52", "KF4ABC"))
        assertTrue(flagged("Reported by KF4ABC's station.", "KF4ABC").isEmpty())
    }

    @Test fun citationsAreNotFigures() {
        assertTrue(flagged("Power is out [3]. Water is fine. [1, 2]", "POWER OUT").isEmpty())
    }

    @Test fun sentencesKeepTheirCitations() {
        val s = AiGrounding.sentences("Power is out [3]. Two stations confirm it. [1, 2] Trailing bit")
        assertEquals(listOf("Power is out [3].", "Two stations confirm it. [1, 2]", "Trailing bit"), s.map { it.text })
        assertEquals(listOf(listOf(3), listOf(1, 2), emptyList()), s.map { it.cites })
        assertEquals("Power is out .", s[0].claim)
    }

    @Test fun verdicts() {
        assertEquals(true, AiPrompt.verdict(" YES"))
        assertEquals(true, AiPrompt.verdict("Yes."))
        assertEquals(false, AiPrompt.verdict("No"))
        assertEquals(false, AiPrompt.verdict("**NO**"))
        assertEquals(null, AiPrompt.verdict("The summary"))
    }
}
