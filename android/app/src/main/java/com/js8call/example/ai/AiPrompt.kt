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

    // ---- One bulletin -> one line. Short in, short out: a few seconds each.

    const val BULLETIN_SYSTEM =
        "You condense amateur radio bulletins into one short plain sentence in your own words. " +
        "Never copy the bulletin; never use lists or formatting."

    /** Radio framing removed: sender prefix, @GROUP, MSG, serial numbers like ,V54, and QST. */
    fun bulletinBody(body: String): String =
        body.trim().replace(Regex("\\s+"), " ")
            .replace(Regex("^[A-Z0-9/]+:\\s*"), "")
            .replace(Regex("^(@\\S+\\s+)?(MSG\\s+)?(,?V\\d+,?)?\\s*(QST\\s+)?"), "")
            .take(1500)

    /** The bulletin first, the instruction last (small models follow the last thing they read). */
    fun bulletinUser(body: String): String =
        "Bulletin:\n" + bulletinBody(body) + "\n\n" +
            "In one sentence of at most 25 words, in your own words, say what this bulletin reports: " +
            "what happened, where, key numbers, and any action to take."

    /** Stable key for a bulletin's text (repeats and relays of the same text share one summary). */
    fun key(body: String): String {
        val norm = "v2:" + body.uppercase().replace(Regex("[^A-Z0-9]"), "")
        val md = java.security.MessageDigest.getInstance("SHA-1").digest(norm.toByteArray())
        return md.take(10).joinToString("") { "%02x".format(it) }
    }

    // ---- Overview of the whole digest, built from the rule-based digest (not the raw log).

    const val OVERVIEW_SYSTEM =
        "You brief a radio operator in the field in plain sentences, in your own words. " +
        "Use only the digest you are given; never invent anything; never copy it out; no lists, headings or formatting."

    /** Digest text with each bulletin replaced by its one-line summary when there is one; instruction last. */
    fun overviewUser(digestText: String, bulletinSummaries: List<String>, maxChars: Int = 3500): String {
        val lines = digestText.lines().map { it.take(160) }
        val b = if (bulletinSummaries.isEmpty()) "" else "\n\nBULLETIN SUMMARIES:\n" + bulletinSummaries.joinToString("\n") { "- $it" }
        val ask = "\n\nNow write 2 to 4 short sentences for the operator: first anything that needs action " +
            "(emergencies, needs, messages waiting for them), or say nothing needs action; then the general picture " +
            "(the main news, how busy the band was)."
        return "DIGEST:\n" + lines.joinToString("\n").take(maxChars - b.length.coerceAtMost(maxChars / 2)) + b + ask
    }

    /**
     * True when the model copied its input instead of summarising: most of its 5-word runs
     * appear word for word in the input. Small models do this; such output is not shown.
     */
    fun isEcho(output: String, input: String): Boolean {
        fun words(t: String) = t.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
        val o = words(output)
        if (o.size < 6) return false
        val inGrams = words(input).windowed(5).map { it.joinToString(" ") }.toHashSet()
        val outGrams = o.windowed(5).map { it.joinToString(" ") }
        return outGrams.count { it in inGrams }.toDouble() / outGrams.size > 0.6
    }

    /** Model output as plain text: no markdown marks, no leading bullet, single spaces. */
    fun clean(text: String, oneLine: Boolean = false): String {
        var t = text.replace(Regex("[*_`#]+"), "").replace(Regex("(?m)^[ \\t]*[-•][ \\t]*"), "").trim()
        if (oneLine) t = t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return t.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

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
