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
        "You condense amateur radio bulletins (sent in capitals) into one short plain-English sentence in your own words, " +
        "written in normal sentence case, never all capitals. Never copy the bulletin; never use lists or formatting."

    /** Radio framing removed: sender prefix, @GROUP, MSG, serial numbers like ,V54, and QST. */
    fun bulletinBody(body: String): String =
        dropUnfinishedTail(
            body.trim().replace(Regex("\\s+"), " ")
                .replace(Regex("^[A-Z0-9/]+:\\s*"), "")
                .replace(Regex("^(@\\S+\\s+)?(MSG\\s+)?(,?[A-Z]\\d+,?)?\\s*(QST\\s+)?"), "")
                // JS8 end-of-message junk after a garbled last frame ("{^%} Z5B").
                .replace(Regex("\\s*\\{\\^%\\}.*$"), "")
                .trim()
        ).take(1500)

    /**
     * A message cut short by a missed frame ends mid-word ("… AFFECTING MT"). When the text has
     * at least one finished sentence, a short unfinished tail after it is dropped so the model
     * does not guess at it. Messages with no punctuation at all are left alone.
     */
    fun dropUnfinishedTail(text: String): String {
        val t = text.trimEnd()
        if (t.isEmpty() || t.last() in ".!?") return t
        val end = t.indexOfLast { it in ".!?" }
        if (end < 20) return t
        val tail = t.substring(end + 1).trim()
        return if (tail.split(' ').size <= 6 && tail.length <= 40) t.substring(0, end + 1) else t
    }

    /**
     * Model output cut off at the token limit ("… acknowledgments are turned") is trimmed back
     * to its last finished sentence, keeping a citation that follows it ("…rerouted. [2]").
     */
    fun completeSentences(text: String): String {
        val t = text.trim()
        if (t.isEmpty() || Regex("[.!?](\\s*\\[[\\d,\\s]+])*$").containsMatchIn(t)) return t
        val m = Regex("[.!?](\\s*\\[[\\d,\\s]+])*(?=\\s)").findAll(t).lastOrNull()
        return if (m != null && m.range.last >= 20) t.substring(0, m.range.last + 1) else "$t…"
    }

    /** The bulletin first, the instruction last (small models follow the last thing they read). */
    fun bulletinUser(body: String): String =
        "Bulletin:\n" + bulletinBody(body) + "\n\n" +
            "In one sentence of at most 25 words, in your own words, say what this bulletin reports: " +
            "what happened, where, key numbers, and any action to take."

    /** Stable key for a bulletin's text (repeats and relays of the same text share one summary). */
    fun key(body: String): String {
        val norm = "v4:" + body.uppercase().replace(Regex("[^A-Z0-9]"), "")
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

    // ---- News roll-up lead: facts prepared by the app (needs, stories with source counts), prose by the model.

    const val LEAD_SYSTEM =
        "You write the lead of a short radio news roll-up for an operator in the field. Plain English, normal sentence case " +
        "(never all capitals), in your own words, no lists, no headings, no formatting. Use only the facts given; never invent anything."

    /**
     * The roll-up's first line, written by the app (always right): what needs action, how much news, how busy.
     * e.g. "Nothing needs action. 2 news stories, 1 confirmed by more than one station. 35 stations heard, 21 new."
     */
    fun situation(needs: Int, waiting: Int, storySources: List<Int>, activity: String?): String = buildString {
        append(when {
            needs == 0 && waiting == 0 -> "Nothing needs action."
            else -> listOfNotNull(
                if (needs > 0) "$needs need${if (needs == 1) "" else "s"} or emergenc${if (needs == 1) "y" else "ies"} open" else null,
                if (waiting > 0) "$waiting message${if (waiting == 1) "" else "s"} waiting for you" else null,
            ).joinToString(", ").replaceFirstChar { it.uppercase() } + ": see below."
        })
        val confirmed = storySources.count { it > 1 }
        append(' ').append(when (storySources.size) {
            0 -> "No news bulletins heard."
            1 -> "1 news story, " + if (confirmed == 1) "confirmed by ${storySources[0]} stations." else "a single report."
            else -> "${storySources.size} news stories" + if (confirmed > 0) ", $confirmed confirmed by more than one station." else ", each a single report."
        })
        activity?.takeIf { it.isNotBlank() }?.let { append(' ').append(it.trim().trimEnd('.')).append('.') }
    }

    /** For two or more stories: the model joins them into the main news in 1-2 sentences, citing [n]. */
    /** The model's own fact check: a yes/no question is far easier for a small model than writing. */
    const val CHECK_SYSTEM = "You check summaries of radio messages for mistakes. Reply with one word: YES if the message " +
        "says what the summary says, or NO if the summary adds, changes or gets wrong anything (a name, place, number or meaning)."

    fun checkUser(message: String, claim: String): String =
        "MESSAGE:\n${message.take(1500)}\n\nSUMMARY: $claim\n\nDoes the message say what the summary says? Answer YES or NO."

    /** true = supported, false = not, null = the model gave no clear answer. */
    fun verdict(raw: String): Boolean? {
        val w = raw.trim().uppercase().trimStart('*', '"', '\'', ' ').take(3)
        return when { w.startsWith("YES") -> true; w.startsWith("NO") -> false; else -> null }
    }

    fun newsUser(stories: List<Pair<String, Int>>): String = buildString {
        appendLine("NEWS STORIES (numbered, with how many stations reported each independently):")
        stories.take(6).forEachIndexed { i, (text, n) ->
            appendLine("[${i + 1}] ${text.take(300)} (${if (n == 1) "single report" else "$n independent reports"})")
        }
        appendLine()
        append("In 1 or 2 sentences of your own, sum up the main news across these stories, most important first. ")
        append("Say when a story was confirmed by more than one station. Put the story's number in square brackets after it, like [2].")
    }

    /**
     * Quantities in [output] (a number with a unit, or a percentage) that don't appear in [input]:
     * a small model misreading "11 KM" as "5 km" must not reach the operator.
     */
    fun unverifiedNumbers(output: String, input: String): List<String> {
        val units = linkedMapOf(
            "KILOMETERS" to "KM", "KILOMETRES" to "KM", "KILOMETER" to "KM", "KILOMETRE" to "KM", "KMS" to "KM", "KM" to "KM",
            "MILES" to "MI", "MILE" to "MI", "MI" to "MI", "FEET" to "FT", "FOOT" to "FT", "FT" to "FT",
            "MPH" to "MPH", "KNOTS" to "KT", "KNOT" to "KT", "KTS" to "KT", "KT" to "KT", "GALLONS" to "GAL", "GALLON" to "GAL", "GAL" to "GAL",
            "PERCENT" to "%", "%" to "%", "HOURS" to "H", "HOUR" to "H", "HRS" to "H", "HR" to "H", "MINUTES" to "MIN", "MINS" to "MIN", "MIN" to "MIN",
            "DAYS" to "D", "DAY" to "D", "MB" to "MB", "HPA" to "MB", "MHZ" to "MHZ", "KHZ" to "KHZ", "WATTS" to "W", "WATT" to "W",
            "PEOPLE" to "PEOPLE", "PERSONS" to "PEOPLE", "DB" to "DB",
        )
        val alt = units.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val re = Regex("(\\d+(?:[.,]\\d+)?)\\s*-?\\s*($alt)(?![A-Z])")
        fun quantities(t: String) = re.findAll(t.uppercase().replace("~", "")).map { m ->
            m.value to (m.groupValues[1].replace(',', '.') + units.getValue(m.groupValues[2]))
        }.toList()
        val known = quantities(input).map { it.second }.toSet()
        return quantities(output).filter { it.second !in known }.map { it.first.trim() }
    }

    /** First [n] sentences of [text], at most [max] characters. */
    fun firstSentences(text: String, n: Int = 2, max: Int = 260): String {
        val parts = Regex("(?<=[.!?])\\s+").split(text.trim())
        return parts.take(n).joinToString(" ").let { if (it.length > max) it.take(max).substringBeforeLast(' ') + "…" else it }
    }

    /** The lead must not copy the prepared facts' framing; restating a story line is fine. */
    fun isLeadEcho(output: String, input: String): Boolean {
        val headers = listOf("NEEDS AND EMERGENCIES:", "WAITING FOR THE OPERATOR", "NEWS STORIES", "ACTIVITY:", "SINGLE REPORT)", "INDEPENDENT REPORTS)")
        if (headers.any { output.uppercase().contains(it) }) return true
        val factsOnly = input.lines().filterNot { it.startsWith("- ") || Regex("^\\[\\d+]").containsMatchIn(it) }.joinToString("\n")
        return isEcho(output, factsOnly)
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
        // An unfinished citation at the end ("… emergencies. [") is cut off.
        t = t.replace(Regex("\\s*\\[[^\\]]*$"), "")
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
