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
        val body = "@GHOSTNET MSG ,V54,QST SUBSEA // PAKISTAN CABLE FAULT"
        org.junit.Assert.assertEquals("Bulletin:\nQST SUBSEA // PAKISTAN CABLE FAULT", AiPrompt.bulletinUser(body))
        org.junit.Assert.assertEquals(AiPrompt.key(body), AiPrompt.key("@ghostnet  msg, v54, qst subsea//pakistan cable fault"))
        org.junit.Assert.assertNotEquals(AiPrompt.key(body), AiPrompt.key("OTHER TEXT"))
    }

    @Test
    fun overviewAppendsBulletinSummaries() {
        val u = AiPrompt.overviewUser("JS8 DIGEST\nLINE", listOf("Cable fault off Karachi."))
        org.junit.Assert.assertTrue(u.startsWith("JS8 DIGEST\nLINE"))
        org.junit.Assert.assertTrue(u.endsWith("BULLETIN SUMMARIES:\n- Cable fault off Karachi."))
    }
}
