package com.js8call.example.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What the model is asked, built from heard traffic. Pure Kotlin, unit-tested. */
object AiPrompt {

    const val SYSTEM =
        "You summarise amateur radio JS8 traffic for a radio operator in the field. " +
        "Use only the log you are given; never invent stations, places or events. " +
        "Write 3 to 6 short bullet points, most important first: emergencies and needs, " +
        "messages to the operator, reports and bulletins, then notable activity. " +
        "Name the callsigns involved in each point. If nothing notable happened, say so in one line."

    data class Msg(val timeMs: Long, val from: String?, val to: String?, val text: String)

    /** The digest's own summary, then the raw messages (newest kept when it is too long). */
    fun user(digestText: String, messages: List<Msg>, maxChars: Int = 6000): String {
        val fmt = SimpleDateFormat("HHmm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val lines = messages.sortedBy { it.timeMs }.map { m ->
            "${fmt.format(Date(m.timeMs))} ${m.from ?: "?"}" + (m.to?.let { " to $it" } ?: "") + ": " + m.text.replace(Regex("\\s+"), " ").trim()
        }
        val head = "AUTOMATIC DIGEST:\n" + digestText.trim() + "\n\nMESSAGES HEARD:\n"
        val budget = (maxChars - head.length).coerceAtLeast(500)
        val kept = ArrayList<String>()
        var used = 0
        for (l in lines.asReversed()) {
            if (used + l.length + 1 > budget) break
            kept += l
            used += l.length + 1
        }
        val dropped = lines.size - kept.size
        return head + (if (dropped > 0) "($dropped older messages left out)\n" else "") + kept.asReversed().joinToString("\n")
    }

    /** Fixed example traffic so speed tests are comparable between devices and days. */
    val SAMPLE: Pair<String, List<Msg>> by lazy {
        val t0 = 1_795_000_000_000L
        val min = 60_000L
        val msgs = listOf(
            Msg(t0, "K4ABC", "@GHOSTNET", "#CHECKIN S:GOOD L:EM73 N:ON GENERATOR POWER"),
            Msg(t0 + 3 * min, "W5XYZ", "@GHOSTNET", "#CHECKIN S:NEED L:EM40 N:LOW ON WATER FOR 3 PEOPLE"),
            Msg(t0 + 6 * min, "N0RQ", "@GHOSTNET", "BRIDGE ON HWY 12 AT MILE 40 IS OUT. USE COUNTY RD 9 DETOUR"),
            Msg(t0 + 9 * min, "KD9MMM", "N0ME", "ARE YOU ABLE TO RELAY TO W5XYZ? I CANNOT HEAR THEM"),
            Msg(t0 + 12 * min, "W5XYZ", "@GHOSTNET", "#SITREP S:AMBER L:EM40 R:WATER MEDS N:ROADS FLOODED SOUTH OF TOWN"),
            Msg(t0 + 15 * min, "AB1CD", "@ALLCALL", "CQ CQ"),
            Msg(t0 + 18 * min, "K4ABC", "W5XYZ", "CAN BRING 20 GAL WATER TOMORROW 0900 LOCAL"),
            Msg(t0 + 21 * min, "N0RQ", "@GHOSTNET", "#WX C:HEAVY RAIN TEMP:48F W:NE 20G35 MPH P:998 MB FALLING"),
            Msg(t0 + 24 * min, "KC2EFG", "@GHOSTNET", "#CHECKIN S:EMERG L:FN31 N:MEDICAL NEED INSULIN, PHONE AND POWER OUT"),
            Msg(t0 + 27 * min, "KD9MMM", "@GHOSTNET", "SOLAR FLUX 140 K INDEX 4. 40M FADING AFTER DARK"),
            Msg(t0 + 30 * min, "W5XYZ", "K4ABC", "THANK YOU. WILL MEET AT FIRE STATION 2"),
            Msg(t0 + 33 * min, "N0RQ", "KC2EFG", "COPY YOUR EMERGENCY. NOTIFYING COUNTY EOC VIA PHONE PATCH"),
        )
        val digest = "1 emergency, 1 need. 5 stations active in the last hour.\n" +
            "Needs and emergencies: KC2EFG EMERG medical (FN31); W5XYZ NEED water (EM40).\n" +
            "For you: KD9MMM asks for a relay to W5XYZ.\n" +
            "Reports: WX from N0RQ heavy rain, wind NE 20G35, pressure falling."
        digest to msgs
    }
}
