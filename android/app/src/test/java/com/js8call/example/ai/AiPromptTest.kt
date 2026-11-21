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
