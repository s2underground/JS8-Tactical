package com.js8call.example.digest

import com.js8call.example.digest.DigestBuilder.Msg
import com.js8call.example.digest.DigestBuilder.Sent
import com.js8call.example.digest.DigestBuilder.Station
import com.js8call.example.digest.DigestBuilder.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestTest {

    private val now = 1794960000000L // 2026-11-18T00:00:00Z
    private val h = 3_600_000L
    private var nextId = 1L

    private fun msg(from: String?, to: String?, body: String, hoursAgo: Double, complete: Boolean = true) =
        Msg(nextId++, from, to, body, now - (hoursAgo * h).toLong(), complete, if (complete) 0 else 1, -10)

    private fun input(messages: List<Msg>, sent: List<Sent> = emptyList(), stations: List<Station> = emptyList()) =
        DigestBuilder.Input(
            nowMs = now, sinceMs = now - 24 * h, myCall = "KX4ME", myGrid = "EM73", myGroups = listOf("@TEAM"),
            messages = messages, sent = sent, stations = stations, listeningHours = 5.5,
            solar = "SFI 140, K 2 (NOAA, 1 h old)", dataStartMs = now - 30 * h,
        )

    @Test
    fun latestStatusPerStationDecidesOpenNeeds() {
        val d = DigestBuilder.build(input(listOf(
            msg("W1AAA", "@ALLCALL", "#CHECKIN S:NEED L:EM73 N:WATER", 5.0),
            msg("W1AAA", "@ALLCALL", "#CHECKIN S:GOOD L:EM73", 1.0),          // resolved later
            msg("W2BBB", "@ALLCALL", "#CHECKIN S:EMERG L:EM74 N:INJURED", 2.0),
            msg("W3CCC", "@ALLCALL", "#SITREP S:AMBER L:EM75 R:FUEL", 3.0),
        )))
        assertEquals(Tone.RED, d.tone)
        assertEquals("2 open needs or emergencies", d.headline)
        val alerts = d.sections[0].lines
        assertEquals(Tone.RED, alerts[0].tone)              // emergency first
        assertTrue(alerts[0].text, alerts[0].text.startsWith("W2BBB EMERG: INJURED"))
        assertEquals("W2BBB", alerts[0].call)
        assertTrue(alerts[1].text.startsWith("W3CCC AMBER: FUEL"))
        assertTrue(alerts.last().text.contains("Now reporting OK: W1AAA"))
    }

    @Test
    fun distressWordsInFreeTextAreFlagged() {
        val d = DigestBuilder.build(input(listOf(msg("W4DDD", "@ALLCALL", "MAYDAY VEHICLE ROLLOVER HWY 9", 0.5))))
        assertEquals(Tone.AMBER, d.tone)
        assertTrue(d.sections[0].lines.single().text.startsWith("W4DDD: MAYDAY"))
    }

    @Test
    fun ordinaryTextIsNotDistress() {
        val d = DigestBuilder.build(input(listOf(msg("W4DDD", "KX4ME", "CAN YOU HELP WITH MY ANTENNA", 0.5))))
        assertTrue(d.sections[0].lines.isEmpty())
        assertEquals(Tone.GREEN, d.tone)
    }

    @Test
    fun answersToMyQueriesNeedNoReply() {
        val d = DigestBuilder.build(input(listOf(
            msg("KD8SKZ", "KX4ME", "GRID EN90QK", 1.0),
            msg("KD8SKZ", "KX4ME", "HEARTBEAT SNR -19", 1.2),
            msg("W5EEE", "KX4ME", "GRID?", 0.5),                 // a question still waits for us
        )))
        val forYou = d.sections[1].lines
        assertEquals(1, forYou.size)
        assertTrue(forYou[0].text, forYou[0].text.contains("GRID?"))
    }

    @Test
    fun unansweredTrafficForMeAndMyGroups() {
        val a = msg("W5EEE", "KX4ME", "WHAT IS YOUR QTH", 3.0)
        val b = msg("W6FFF", "KX4ME", "PSE QSL", 2.0, complete = false)
        val c = msg("W7GGG", "@TEAM", "MEET AT 1800", 1.0)
        val other = msg("W8HHH", "W9III", "NOT FOR ME", 1.0)
        val d = DigestBuilder.build(input(listOf(a, b, c, other), sent = listOf(Sent("W5EEE", "EM73", now - 2 * h))))
        val lines = d.sections[1].lines
        assertEquals(listOf("W7GGG", "W6FFF", null), lines.map { it.call })   // W5EEE was answered
        assertTrue(lines[1].text.endsWith("[incomplete]"))
        assertTrue(lines.last().text.startsWith("1 message to you arrived with gaps"))
        assertEquals("No open needs; 2 unanswered for you", d.headline)
    }

    @Test
    fun windowAndStations() {
        val d = DigestBuilder.build(input(
            messages = listOf(msg("W1AAA", "@ALLCALL", "#SPOT S:3 A:CONVOY L:EM74", 1.0), msg("W1AAA", "KX4ME", "OLD", 30.0)),
            stations = listOf(
                Station("W1AAA", "FN31", now - 40 * h, now - h, -5),
                Station("VK2XX", "QF56", now - 2 * h, now - 2 * h, -20),
                Station("OLD1", "EM00", now - 50 * h, now - 30 * h, -10),   // outside the window
            ),
        ))
        val st = d.sections.first { it.title == "Stations" }.lines
        assertEquals("2 stations heard, 1 new", st[0].text)
        assertEquals("W1AAA", st[1].call)                        // most messages first
        assertTrue(st.last().text, st.last().text == "Farthest: VK2XX")
        val reports = d.sections.first { it.title == "Reports" }.lines
        assertEquals("1 SPOT", reports[0].text)
        assertTrue(reports[1].text.startsWith("SPOT from W1AAA: 3, CONVOY"))
        assertTrue(!d.windowText.contains("when the app started"))   // app ran all window
        val text = d.toText()
        assertTrue(text, text.startsWith("JS8 DIGEST:"))
        assertTrue(text, text.contains("SFI 140"))
    }

    @Test
    fun emptyDay() {
        val d = DigestBuilder.build(input(emptyList()).copy(dataStartMs = now - 2 * h))
        assertEquals("Nothing heard in this window", d.headline)
        assertTrue(d.windowText, d.windowText.contains("when the app started"))
        assertEquals(Tone.GREEN, d.tone)
    }

    private fun section(d: DigestBuilder.Digest, title: String) = d.sections.first { it.title == title }

    @Test
    fun smartSections() {
        val long = "QST BIO H5N1 CATTLE MOVEMENT USDA SEQUENCING LINKS WA DAIRY H5N1 TO LIKELY ID CATTLE MOVEMENT ROUTE UNCONFIRMED"
        val messages = listOf(
            msg("K9DGY", "@GHOSTNET", long, 2.0),
            msg("N0DDK", "@GHOSTNET", "#CHECKIN N0DDK EM83 GREEN", 1.5),
            msg("WK8D", "@GHOSTNET", "HELLO NET", 1.4),
            msg("N0DDK", "K9DGY", "ACK", 1.0),
            msg("K9DGY", "N0DDK", "RR TNX", 0.9),
            msg("K9DGY", "N0DDK", "73", 0.8),
            msg("WK8D", "@HB", "HEARTBEAT EM78", 0.5),
        )
        val activity = listOf(5.0, 4.5, 4.0, 3.5).map { DigestBuilder.Activity("AD8IM", now - (it * h).toLong()) } +
            listOf(0.3, 0.2, 0.1).map { DigestBuilder.Activity("KD8SKZ", now - (it * h).toLong()) }
        val heardMe = listOf(
            DigestBuilder.HeardMe("KD8SKZ", -3, now - h, "EN90"),
            DigestBuilder.HeardMe("N0DDK", -15, now - 2 * h, "EM83"),
            DigestBuilder.HeardMe("OLD1", -5, now - 30 * h, null),
        )
        val d = DigestBuilder.build(input(messages).copy(activity = activity, heardMe = heardMe))

        assertTrue(section(d, "Bulletins and long messages").lines.single().text.startsWith("K9DGY → @GHOSTNET: QST BIO"))
        val net = section(d, "Nets and groups").lines.single()
        assertEquals("@GHOSTNET: 3 stations, 3 messages, 1 check-in", net.text)
        assertEquals("K9DGY ↔ N0DDK", section(d, "Who's working whom").lines.single().text)
        assertEquals(listOf("AD8IM"), section(d, "Gone quiet").lines.map { it.text })
        val heard = section(d, "Who heard you").lines.map { it.text }
        assertEquals("2 stations reported hearing you", heard[0])
        assertEquals("Strongest: KD8SKZ", heard[1])
        assertTrue(heard.any { it.startsWith("Farthest:") })
    }
}
