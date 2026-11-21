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

class CitationRepairTest {
    private val stories = listOf(
        listOf("SUBSEA // PAKISTAN CABLE FAULT NEAR KARACHI. ~50% OF AFFECTED TRAFFIC REROUTED"),
        listOf("VARA HF MODEM UPDATE RESTORES BACKWARD COMPATIBILITY WITH OLDER VERSIONS"),
        listOf("NET STARTING SOON. HB, AUTO REPLY, ACKS OFF. 35 PRE-NET STATIONS HEARD IN N TEXAS"),
        listOf("H5N1 // USDA SEQUENCED H5N1 IN CATTLE MOVEMENT LINKING 180 LIVESTOCK DETECTIONS; 21 DAIRY FACILITIES QUARANTINED SINCE NOV 17"),
    )

    @org.junit.Test fun wrongNumberIsCorrected() {
        val text = "A cable near Karachi failed [1]. USDA sequenced H5N1 in cattle, linking 180 livestock detections and 21 dairy facilities [3]."
        val (fixed, fixes) = AiGrounding.repairCitations(text, stories)
        org.junit.Assert.assertEquals("A cable near Karachi failed [1]. USDA sequenced H5N1 in cattle, linking 180 livestock detections and 21 dairy facilities [4].", fixed)
        org.junit.Assert.assertEquals(listOf(AiGrounding.Fix(1, listOf(3), 4)), fixes)
        // Once fixed, nothing in it is unsupported by its story.
        val sen = AiGrounding.sentences(fixed)[1]
        org.junit.Assert.assertTrue(AiGrounding.unsupported(sen.text, stories[3]).isEmpty())
    }

    @org.junit.Test fun rightNumbersAreLeftAlone() {
        val text = "A VARA modem update restores compatibility with older versions [2]. The net starts soon with 35 stations heard [3]."
        org.junit.Assert.assertEquals(text to emptyList<AiGrounding.Fix>(), AiGrounding.repairCitations(text, stories))
    }
}

class StoryTextTest {
    @org.junit.Test fun framingAfterALeadingGap() {
        org.junit.Assert.assertEquals("[…] GRID // AUSTRALIA [PF83] SEVERE STORM TOPPLED 2 TOWERS.",
            AiPrompt.bulletinBody("[…],Q57,QST GRID // AUSTRALIA [PF83] SEVERE STORM TOPPLED 2 TOWERS."))
    }

    @org.junit.Test fun acronymsAndMonthsSurviveSentenceCase() {
        org.junit.Assert.assertEquals(
            "Aviation // Terror alert FBI/DHS/TSA issued a Nov 19 advisory to U.S. airlines, airports and cargo operators.",
            AiStories.sentenceCase("AVIATION // TERROR ALERT FBI/DHS/TSA ISSUED A NOV 19 ADVISORY TO U.S. AIRLINES, AIRPORTS AND CARGO OPERATORS."))
        org.junit.Assert.assertEquals("USDA sequenced H5N1 in cattle, the net is on.",
            AiStories.sentenceCase("USDA SEQUENCED H5N1 IN CATTLE, THE NET IS ON."))
    }
}
