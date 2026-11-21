package com.js8call.example.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptTest {
    @Test
    fun keepsNewestMessagesWithinBudget() {
        val msgs = (0 until 200).map { AiPrompt.Msg(1_795_000_000_000L + it * 60_000L, "K$it", "@GHOSTNET", "MESSAGE NUMBER $it WITH SOME TEXT") }
        val p = AiPrompt.user("Digest line", msgs, maxChars = 2000)
        assertTrue(p.length <= 2100)
        assertTrue(p.contains("K199"))
        assertFalse(p.contains("K0:"))
        assertTrue(p.contains("older messages left out"))
        assertTrue(p.startsWith("AUTOMATIC DIGEST:\nDigest line"))
        // Oldest kept first, newest last.
        assertTrue(p.indexOf("K198") < p.indexOf("K199"))
    }

    @Test
    fun sampleFitsComfortably() {
        val (digest, msgs) = AiPrompt.SAMPLE
        val p = AiPrompt.user(digest, msgs)
        assertTrue(p.contains("KC2EFG") && p.contains("to @GHOSTNET"))
        assertFalse(p.contains("left out"))
    }
}

class AiPromptCleanTest {
    @Test
    fun cleanStripsMarkdownAndKeepsFirstLine() {
        val raw = "**Cable fault** off Karachi; ~50% rerouted.\n\n- extra line"
        org.junit.Assert.assertEquals("Cable fault off Karachi; ~50% rerouted.", AiPrompt.clean(raw, oneLine = true))
        org.junit.Assert.assertEquals("A\n\nB", AiPrompt.clean("- **A**\n\n\n\n- B"))
    }

    @Test
    fun bulletinFramingRemovedAndKeysIgnorePunctuation() {
        val body = "K9DGY: @GHOSTNET MSG ,V54,QST SUBSEA // PAKISTAN CABLE FAULT"
        org.junit.Assert.assertEquals("SUBSEA // PAKISTAN CABLE FAULT", AiPrompt.bulletinBody(body))
        org.junit.Assert.assertTrue(AiPrompt.bulletinUser(body).startsWith("Bulletin:\nSUBSEA // PAKISTAN CABLE FAULT\n\nIn one sentence"))
        org.junit.Assert.assertEquals(AiPrompt.key(body), AiPrompt.key("k9dgy:  @ghostnet  msg, v54, qst subsea//pakistan cable fault"))
        org.junit.Assert.assertNotEquals(AiPrompt.key(body), AiPrompt.key("OTHER TEXT"))
    }

    @Test
    fun echoDetection() {
        val input = "K9DGY: @GHOSTNET MSG ,V54,QST SUBSEA // PAKISTAN CABLE FAULT [ML34] PAKISTAN S TELECOM REGULATOR CONFIRMED A FAULT IN THE SEA-ME-WE 5 UNDERSEA CABLE ~11KM OFFSHORE NEAR KARACHI."
        org.junit.Assert.assertTrue(AiPrompt.isEcho("K9DGY: @GHOSTNET MSG ,V54,QST SUBSEA // PAKISTAN CABLE FAULT [ML34] PAKISTAN S TELECOM REGULATOR CONFIRMED A FAULT", input))
        org.junit.Assert.assertFalse(AiPrompt.isEcho("Pakistan's regulator confirmed a fault on the SEA-ME-WE 5 cable off Karachi; about half the traffic was rerouted.", input))
        org.junit.Assert.assertFalse(AiPrompt.isEcho("Short.", input))
    }

    @Test
    fun overviewAppendsBulletinSummaries() {
        val u = AiPrompt.overviewUser("JS8 DIGEST\nLINE", listOf("Cable fault off Karachi."))
        org.junit.Assert.assertTrue(u.startsWith("DIGEST:\nJS8 DIGEST\nLINE"))
        org.junit.Assert.assertTrue(u.contains("BULLETIN SUMMARIES:\n- Cable fault off Karachi.\n\nNow write"))
    }
}

class AiPromptChecksTest {
    private val bulletin = "QST SUBSEA // PAKISTAN CABLE FAULT CONFIRMED A FAULT IN THE SEA-ME-WE 5 UNDERSEA CABLE ~11KM OFFSHORE NEAR KARACHI. ~50% OF AFFECTED TRAFFIC REROUTED."

    @Test
    fun wrongDistanceIsCaughtRightOnesPass() {
        org.junit.Assert.assertEquals(listOf("5 KM"), AiPrompt.unverifiedNumbers("A cable fault 5 km offshore near Karachi.", bulletin))
        org.junit.Assert.assertTrue(AiPrompt.unverifiedNumbers("SEA-ME-WE 5 cable fault about 11 km off Karachi; about 50% of traffic rerouted.", bulletin).isEmpty())
        org.junit.Assert.assertEquals(listOf("80%"), AiPrompt.unverifiedNumbers("80% rerouted", bulletin))
        org.junit.Assert.assertTrue(AiPrompt.unverifiedNumbers("11 kilometres offshore, half the traffic rerouted", bulletin).isEmpty())
    }

    @Test
    fun leadMayRestateStoriesButNotCopyTheNotes() {
        val input = AiPrompt.newsUser(listOf("A cable fault off Karachi was confirmed by the regulator and traffic was rerouted." to 1, "A VARA modem update restores compatibility." to 2))
        org.junit.Assert.assertFalse(AiPrompt.isLeadEcho("A cable fault off Karachi was confirmed by the regulator and traffic was rerouted, a single report [1]; two stations confirm a VARA update [2].", input))
        org.junit.Assert.assertTrue(AiPrompt.isLeadEcho("NEWS STORIES: [1] A cable fault (single report)", input))
    }

    @Test
    fun situationLineIsBuiltByTheApp() {
        org.junit.Assert.assertEquals("Nothing needs action. 1 news story, a single report. 35 stations heard, 21 new.",
            AiPrompt.situation(0, 0, listOf(1), "35 stations heard, 21 new"))
        org.junit.Assert.assertEquals("1 need or emergency open, 2 messages waiting for you: see below. 3 news stories, 1 confirmed by more than one station.",
            AiPrompt.situation(1, 2, listOf(1, 3, 1), null))
        org.junit.Assert.assertEquals("Nothing needs action. No news bulletins heard.", AiPrompt.situation(0, 0, emptyList(), ""))
    }

    @Test
    fun danglingCitationCut() {
        org.junit.Assert.assertEquals("No open needs or emergencies.", AiPrompt.clean("No open needs or emergencies. ["))
    }

    @Test
    fun firstSentencesCaps() {
        org.junit.Assert.assertEquals("One. Two.", AiPrompt.firstSentences("One. Two. Three."))
    }
}

class AiPromptTrimTest {
    @org.junit.Test fun stripsOtherFramingAndJunk() {
        org.junit.Assert.assertEquals("H5N1 DETECTED IN DAIRY HERDS IN 3 COUNTIES.",
            AiPrompt.bulletinBody("@ALLCALL MSG ,A57, H5N1 DETECTED IN DAIRY HERDS IN 3 COUNTIES. {^%} Z5B"))
    }

    @org.junit.Test fun dropsUnfinishedTail() {
        org.junit.Assert.assertEquals("VARA HF OUTAGE ON 40M. ACKS TURNED OFF.",
            AiPrompt.bulletinBody("VARA HF OUTAGE ON 40M. ACKS TURNED OFF. AFFECTING MT"))
        // No punctuation anywhere: left alone.
        org.junit.Assert.assertEquals("GRID DOWN IN EN52 AFFECTING MT",
            AiPrompt.bulletinBody("GRID DOWN IN EN52 AFFECTING MT"))
    }

    @org.junit.Test fun leadCutAtLastSentence() {
        org.junit.Assert.assertEquals("A cable fault slowed traffic [1]. VARA links are down. [2]",
            AiPrompt.completeSentences("A cable fault slowed traffic [1]. VARA links are down. [2] Acknowledgments are turned"))
        org.junit.Assert.assertEquals("All done.", AiPrompt.completeSentences("All done."))
        org.junit.Assert.assertEquals("Nothing finished here…", AiPrompt.completeSentences("Nothing finished here"))
    }
}
