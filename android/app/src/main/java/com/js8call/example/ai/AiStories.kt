package com.js8call.example.ai

import com.js8call.example.digest.DigestBuilder

/**
 * Groups bulletins into stories for the news roll-up, and counts sources without any AI:
 * the same text from several stations is one source passed along (relays/repeats); different
 * wording about the same thing from different stations is independent reporting.
 * Pure Kotlin, unit-tested.
 */
object AiStories {

    data class Story(
        /** Newest wording, framing removed: what the one-line summary is made from. */
        val body: String,
        /** Raw message body of [body] (the AI summary cache key). */
        val rawBody: String,
        /** Stations that sent their own wording of this story. */
        val independent: List<String>,
        /** Stations that passed on someone else's exact text. */
        val relayedBy: List<String>,
        val firstMs: Long,
        val lastMs: Long,
        /** Every transmission behind this story, oldest first: the citations. */
        val msgs: List<DigestBuilder.Msg> = emptyList(),
        /** No complete copy was heard (gaps shown as […]); the AI doesn't summarise it. */
        val partial: Boolean = msgs.isNotEmpty() && msgs.none { it.complete },
    ) {
        val sources get() = independent.size
    }

    private val stop = setOf(
        "THAT", "THIS", "WITH", "FROM", "HAVE", "WILL", "BEEN", "WERE", "THEY", "THEIR", "THERE", "WHICH", "ABOUT",
        "AFTER", "BEFORE", "INTO", "OVER", "UNDER", "ALSO", "ONLY", "SOME", "MORE", "MOST", "THAN", "THEN", "WHEN",
        "WHAT", "WHERE", "REMAINS", "REPORTEDLY", "REPORTED", "REPORTS", "REPORT", "TODAY", "GHOSTNET", "ALLCALL",
        "QST", "MSG", "RELAY", "PLEASE", "PSE", "AGAIN", "STILL", "BEING", "SAID", "SAYS", "ACCORDING",
    )

    /** Words that identify the topic: 4+ letters, not callsigns or filler. */
    fun keywords(text: String): Set<String> =
        text.uppercase().split(Regex("[^A-Z0-9]+"))
            .filter { it.length >= 4 && it !in stop && !(it.any(Char::isDigit) && it.any(Char::isLetter)) }
            .toSet()

    /** Sent to a group (@GHOSTNET, @ALLCALL) or to no one in particular, not to a single callsign. */
    fun isBroadcast(m: DigestBuilder.Msg): Boolean = m.recipient.isNullOrBlank() || m.recipient.startsWith("@")

    /**
     * A shortened story line that ends cleanly: at the last full sentence that fits, or failing
     * that at a word boundary, never mid-word.
     */
    fun preview(text: String, max: Int = 260): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        val sentenceEnd = Regex("""[.!?](?=\s)""").findAll(cut).lastOrNull()?.range?.last
        if (sentenceEnd != null && sentenceEnd >= max / 3) return cut.take(sentenceEnd + 1) + " …"
        val space = cut.lastIndexOf(' ')
        return (if (space >= max / 3) cut.take(space) else cut).trimEnd(',', ';', ':', ' ') + "…"
    }

    fun build(messages: List<DigestBuilder.Msg>): List<Story> {
        data class Group(val key: String, val msgs: List<DigestBuilder.Msg>, val words: Set<String>)
        // Broadcasts only: a reply addressed to one station is conversation, not news. It still
        // shows under Bulletins and long messages.
        val bulletins = messages.filter { DigestBuilder.isBulletin(it) && isBroadcast(it) }.sortedBy { it.timeMs }
        // 1. Same text (after framing is removed) = one source, the first sender; the rest relayed it.
        val groups = bulletins.groupBy { AiPrompt.key(AiPrompt.bulletinBody(it.body)) }
            .map { (k, ms) -> Group(k, ms, keywords(AiPrompt.bulletinBody(ms.last().body))) }
            .toMutableList()
        // 2. Different wording sharing enough topic words = the same story from another source.
        val clusters = ArrayList<MutableList<Group>>()
        for (g in groups) {
            val home = clusters.firstOrNull { c -> c.any { similar(it.words, g.words) } }
            if (home != null) home += g else clusters += mutableListOf(g)
        }
        return clusters.map { c ->
            val all = c.flatMap { it.msgs }.sortedBy { it.timeMs }
            val originals = c.mapNotNull { g -> g.msgs.first().sender?.uppercase() }.distinct()
            val relayers = c.flatMap { g -> g.msgs.drop(1).mapNotNull { it.sender?.uppercase() } }.distinct().filter { it !in originals }
            // The newest complete copy is the story's wording; a partial one only if that's all there is.
            val newest = all.lastOrNull { it.complete } ?: all.last()
            Story(AiPrompt.bulletinBody(newest.body), newest.body.trim(), originals, relayers, all.first().timeMs, newest.timeMs, all)
        }.sortedWith(compareByDescending<Story> { it.sources }.thenByDescending { it.lastMs })
    }

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val shared = a.intersect(b).size
        return shared >= 3 && shared.toDouble() / a.union(b).size >= 0.2
    }

    /** "1 source: K9DGY" / "2 independent sources: …" / "+ relayed by …". */
    fun sourceLine(s: Story): String = buildString {
        append(if (s.sources == 1) "1 source: " else "${s.sources} independent sources: ")
        append(s.independent.joinToString(", "))
        if (s.relayedBy.isNotEmpty()) append(" · relayed by ").append(s.relayedBy.joinToString(", "))
    }

    private val ACRONYMS = setOf("US", "UK", "EU", "UN", "HF", "VHF", "UHF", "EOC", "FEMA", "NOAA", "GPS", "NWS", "SFI", "QST", "VARA",
        "ARES", "RACES", "CERT", "AM", "PM", "USDA", "NATO", "CISA", "NASA", "ATAK", "ARRL", "ICAO", "OPEC", "ERCOT", "OSINT",
        "HUMINT", "SIGINT", "UTC", "JS8", "FT8", "LTE", "GSM", "SAR", "ISP", "CIO", "CEO", "IRGC", "UAV", "UAS", "IED")

    /** Common English words of up to three letters: lower case. Other short all-letter words are taken as acronyms (FBI, DHS, TSA). */
    private val COMMON_SHORT = setOf(
        "A", "AN", "AS", "AT", "BE", "BY", "DO", "GO", "HE", "IF", "IN", "IS", "IT", "ME", "MY", "NO", "OF", "ON", "OR", "SO",
        "TO", "UP", "WE", "OK", "ALL", "AND", "ANY", "ARE", "BUT", "CAN", "DAY", "DID", "FOR", "GET", "GOT", "HAD", "HAS", "HER",
        "HIM", "HIS", "HOW", "ITS", "LET", "MAY", "NEW", "NOT", "NOW", "OFF", "OLD", "ONE", "OUR", "OUT", "OWN", "PUT", "SAY",
        "SEE", "SET", "SHE", "SIX", "TEN", "THE", "TOO", "TWO", "USE", "WAS", "WAY", "WHO", "WHY", "YES", "YET", "YOU", "AGO",
        "AIR", "END", "FAR", "FEW", "LOW", "MAN", "MEN", "RUN", "SEA", "TOP", "WAR", "WET", "OIL", "GAS", "CAR", "BUS", "HIT",
        "KEY", "LED", "MET", "PER", "VIA", "AID", "ACT", "AGE", "BIG", "BAD", "BAN", "BID", "BOX", "CUT", "DUE", "EYE", "FED",
        "FLY", "GUN", "HOT", "ICE", "JOB", "LAW", "LAY", "LOT", "MAP", "MIX", "NET", "ODD", "PAY", "RAN", "RED", "ROW", "SAW",
        "SIT", "SON", "SUN", "TAX", "TIE", "TRY", "WIN", "WON", "ARM", "ASK", "BED", "BOY", "DRY", "EGG", "FAN", "FIT", "FIX",
        "FOG", "FUN", "GAP", "HAT", "HOG", "HUB", "INK", "JET", "KIT", "LAB", "LEG", "LIE", "MUD", "NOR", "OAK", "OWE", "PEN",
        "PET", "PIG", "POP", "POT", "RAW", "RIB", "ROD", "RUG", "SKY", "SPY", "TAP", "TIN", "TON", "VAN", "WEB", "WIT", "ZIP")

    private val TITLE_CASE = setOf("JAN", "FEB", "MAR", "APR", "JUN", "JUL", "AUG", "SEP", "SEPT", "OCT", "NOV", "DEC",
        "JANUARY", "FEBRUARY", "MARCH", "APRIL", "JUNE", "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER",
        "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")

    /** One piece of a word (split on '/'), cased: acronyms and codes kept, months title case, the rest lower case. */
    private fun casePart(part: String, inSlashGroup: Boolean): String {
        val bare = part.trim { !it.isLetterOrDigit() }
        if (bare.isEmpty()) return part
        val keep = (bare.any(Char::isDigit) && bare.any(Char::isLetter)) ||
            bare in ACRONYMS ||
            Regex("^([A-Z]\\.){2,}$").matches(part.trim { it == ',' || it == ';' || it == ':' || it == ')' || it == '(' }) ||
            (bare.all(Char::isLetter) && bare.length in 2..3 && bare !in COMMON_SHORT) ||
            (inSlashGroup && bare.all(Char::isLetter) && bare.length in 2..5)
        return when {
            bare in TITLE_CASE -> part.lowercase().replaceFirst(bare.lowercase(), bare.lowercase().replaceFirstChar { it.uppercase() })
            keep -> part
            else -> part.lowercase()
        }
    }

    /** ALL-CAPS radio text in ordinary sentence case, callsigns and acronyms kept (fallback when there is no AI line). */
    fun sentenceCase(text: String): String {
        val words = text.trim().replace(Regex("\\s+"), " ").split(' ')
        val sb = StringBuilder()
        var startOfSentence = true
        for (w in words) {
            val parts = w.split('/')
            var out = parts.joinToString("/") { casePart(it, parts.size > 1) }
            if (startOfSentence && out.isNotEmpty()) {
                val i = out.indexOfFirst { it.isLetter() }
                if (i >= 0) out = out.substring(0, i) + out[i].uppercaseChar() + out.substring(i + 1)
            }
            sb.append(out).append(' ')
            // "U.S." doesn't end a sentence.
            startOfSentence = (w.endsWith('.') && !Regex("^([A-Z]\\.){2,},?$").matches(w)) || w.endsWith('!') || w.endsWith('?') || w == "//"
        }
        return sb.toString().trim()
    }
}
