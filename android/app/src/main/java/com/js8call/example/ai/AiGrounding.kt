package com.js8call.example.ai

/**
 * Checks AI text against the transmissions it was written from, without the model: every name,
 * callsign, place, grid and figure the AI wrote must appear in those messages. Anything that
 * doesn't is marked on screen so the operator knows where to look. This catches invented details
 * ("MTI" from a cut-off "MT…"); it cannot catch a sentence whose words are all real but whose
 * meaning changed, which is what the citations and the model's own check are for.
 */
object AiGrounding {

    /** One sentence of AI text, where it sits, and the story numbers it cites ([2] → 2). */
    data class Sentence(val range: IntRange, val text: String, val cites: List<Int>) {
        /** The sentence without its citations, as it is checked. */
        val claim: String get() = text.replace(CITE, " ").replace(Regex("\\s+"), " ").trim()
    }

    private val CITE = Regex("\\[[\\d,\\s]+]")
    private val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}']*(?:\\.\\d+)?%?")
    private val NUMBER = Regex("\\d+(?:\\.\\d+)?")

    /** Words that are fine to write even when the messages don't contain them. */
    private val GENERIC = setOf("I", "JS8", "JS8CALL", "HF", "VHF", "UHF", "UTC", "AM", "PM", "AI", "OK")

    /** Units a figure may carry when the model writes "11km" or "11 km" for "11KM". */
    private val UNITS = setOf("KM", "M", "MI", "MILE", "MILES", "MPH", "KPH", "KMH", "KT", "KTS", "FT", "HZ", "KHZ", "MHZ",
        "W", "KW", "DB", "C", "F", "H", "HR", "HRS", "MIN", "MINS", "Z", "ST", "ND", "RD", "TH", "S", "MS", "PCT", "K")

    fun sentences(text: String): List<Sentence> {
        val out = ArrayList<Sentence>()
        // A sentence ends at . ! or ? (plus any citations right after) followed by a space or the end.
        val ends = Regex("[.!?](?:\\s*\\[[\\d,\\s]+])*(?=\\s|$)").findAll(text).map { it.range.last }.toMutableList()
        if (ends.isEmpty() || ends.last() < text.trimEnd().length - 1) ends += text.trimEnd().length - 1
        var start = 0
        for (e in ends) {
            if (e < start) continue
            while (start <= e && text[start].isWhitespace()) start++
            if (start > e) continue
            val s = text.substring(start, e + 1)
            val cites = CITE.findAll(s).flatMap { m -> NUMBER.findAll(m.value).map { it.value.toInt() } }.distinct().toList()
            out += Sentence(start..e, s, cites)
            start = e + 1
        }
        return out
    }

    /**
     * Ranges in [text] of names, callsigns, places and figures that none of [sources] contain.
     * Ordinary words (verbs, adjectives, the model's own phrasing) are not checked.
     */
    fun unsupported(text: String, sources: List<String>): List<IntRange> {
        val src = sources.joinToString(" ").uppercase()
        val words = HashSet<String>()
        Regex("[\\p{L}\\p{N}]+").findAll(src).forEach { words += it.value }
        val numbers = NUMBER.findAll(src).map { canonical(it.value) }.toHashSet()
        val citeRanges = CITE.findAll(text).map { it.range }.toList()
        val out = ArrayList<IntRange>()
        for (sent in sentences(text)) {
            var first = true
            TOKEN.findAll(sent.text).forEach { m ->
                val at = sent.range.first + m.range.first
                if (citeRanges.any { at in it }) return@forEach
                val tok = m.value
                val isFirst = first
                first = false
                if (!salient(tok, isFirst)) return@forEach
                if (!supported(tok, words, numbers)) out += at until at + tok.length
            }
        }
        return out
    }

    /** A name, callsign, grid, acronym or figure: something that could be invented. */
    private fun salient(tok: String, firstInSentence: Boolean): Boolean {
        if (tok.length < 2 && !tok[0].isDigit()) return false
        if (tok.uppercase().removeSuffix("'S") in GENERIC) return false
        if (tok.any { it.isDigit() }) return true
        if (tok.drop(1).any { it.isUpperCase() }) return true
        return tok[0].isUpperCase() && !firstInSentence
    }

    private fun supported(tok: String, words: Set<String>, numbers: Set<String>): Boolean {
        val up = tok.uppercase().removeSuffix("'S").removeSuffix("'").removeSuffix("%")
        if (up in words) return true
        val letters = up.filter { it.isLetter() }
        if (up.any { it.isDigit() }) {
            // A bare figure, or a figure with a unit: the number itself must be in the messages.
            val bareOrUnit = letters.isEmpty() || (up.first().isDigit() && up.trimStart { it.isDigit() || it == '.' } in UNITS)
            return bareOrUnit && NUMBER.findAll(up).all { canonical(it.value) in numbers }
        }
        // Plurals and possessives: "Stations" for "STATION", "Pakistan" for "PAKISTAN'S".
        return listOf(up.removeSuffix("S"), up.removeSuffix("ES"), up + "S", up + "ES").any { it.length >= 2 && it in words }
    }

    private fun canonical(n: String): String =
        n.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: n

    // ------------------------------------------------------------------ citations

    private val STOP = setOf("THAT", "WITH", "FROM", "THIS", "HAVE", "BEEN", "WERE", "THEY", "THEIR", "THERE", "WHICH", "WILL",
        "ABOUT", "AFTER", "INTO", "ALSO", "MORE", "SOME", "THAN", "THEN", "OVER", "WHEN", "WHAT", "SAID", "SAYS", "REPORT",
        "REPORTS", "REPORTED", "STATION", "STATIONS", "SINGLE", "INDEPENDENT", "SOURCE", "SOURCES", "NEWS", "STORY")

    /** Content words of [text] (four letters or more, plural folded), plus names and figures. */
    fun contentWords(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(text.uppercase()).map { it.value }
            .filter { (it.length >= 4 && it !in STOP) || it.any(Char::isDigit) }
            .map { if (it.length > 4 && it.endsWith("S") && !it.endsWith("SS")) it.dropLast(1) else it }
            .toSet()

    /** A citation the app changed: in sentence [sentence] (0-based), [from] became [to]. */
    data class Fix(val sentence: Int, val from: List<Int>, val to: Int)

    /**
     * Small models sometimes number a sentence with the wrong story. For each cited sentence, if
     * another story clearly matches its words better than the one cited (at least three shared
     * content words and twice as many), the citation is changed to that story. [stories]: each
     * story's source texts, 1-based in citation order. Returns the text and what was changed.
     */
    fun repairCitations(text: String, stories: List<List<String>>): Pair<String, List<Fix>> {
        if (stories.size < 2) return text to emptyList()
        val words = stories.map { contentWords(it.joinToString(" ")) }
        val fixes = ArrayList<Fix>()
        val out = StringBuilder(text)
        sentences(text).withIndex().reversed().forEach { (i, sen) ->
            if (sen.cites.isEmpty() || sen.cites.any { it !in 1..stories.size }) return@forEach
            val mine = contentWords(sen.claim)
            if (mine.size < 3) return@forEach
            val score = words.map { w -> mine.count { it in w } }
            val cited = sen.cites.maxOf { score[it - 1] }
            val best = score.indices.maxByOrNull { score[it] } ?: return@forEach
            if (best + 1 in sen.cites || score[best] < 3 || score[best] < 2 * cited) return@forEach
            // Replace the sentence's citation marks with the right one.
            val fixed = CITE.replace(sen.text, "").replace(Regex("\\s+([.!?])"), "$1").trimEnd()
            val withCite = if (fixed.isNotEmpty() && fixed.last() in ".!?") fixed.dropLast(1) + " [${best + 1}]" + fixed.last() else "$fixed [${best + 1}]"
            out.replace(sen.range.first, sen.range.last + 1, withCite)
            fixes += Fix(i, sen.cites, best + 1)
        }
        return out.toString() to fixes.reversed()
    }
}
