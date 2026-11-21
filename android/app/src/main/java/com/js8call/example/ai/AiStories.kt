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

    fun build(messages: List<DigestBuilder.Msg>): List<Story> {
        data class Group(val key: String, val msgs: List<DigestBuilder.Msg>, val words: Set<String>)
        val bulletins = messages.filter { DigestBuilder.isBulletin(it) }.sortedBy { it.timeMs }
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
            val newest = all.last()
            Story(AiPrompt.bulletinBody(newest.body), newest.body.trim(), originals, relayers, all.first().timeMs, newest.timeMs)
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

    private val ACRONYMS = setOf("US", "UK", "EU", "UN", "HF", "VHF", "UHF", "EOC", "FEMA", "NOAA", "GPS", "NWS", "SFI", "QST", "VARA", "ARES", "RACES", "CERT", "AM", "PM")

    /** ALL-CAPS radio text in ordinary sentence case, callsigns kept (fallback when there is no AI line). */
    fun sentenceCase(text: String): String {
        val words = text.trim().replace(Regex("\\s+"), " ").split(' ')
        val sb = StringBuilder()
        var startOfSentence = true
        for (w in words) {
            val bare = w.trim { !it.isLetterOrDigit() }
            val keep = (bare.any(Char::isDigit) && bare.any(Char::isLetter)) || bare in ACRONYMS
            var out = if (keep) w else w.lowercase()
            if (startOfSentence && out.isNotEmpty()) out = out.replaceFirstChar { it.uppercase() }
            sb.append(out).append(' ')
            startOfSentence = w.endsWith('.') || w.endsWith('!') || w.endsWith('?') || w == "//"
        }
        return sb.toString().trim()
    }
}
