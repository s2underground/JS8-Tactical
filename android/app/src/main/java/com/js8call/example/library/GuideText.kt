package com.js8call.example.library

/**
 * The Library's guide format: a small, strict subset of Markdown that the app
 * renders itself (no web view, no library), so guides are easy to write and
 * review as plain text in `assets/library/`.
 *
 *     ---
 *     title: Building a dipole
 *     summary: One line for the Library list
 *     section: Antennas
 *     order: 30
 *     ---
 *     # Heading            ## Subheading
 *     Paragraph text, **bold** words. Lines of a paragraph are joined.
 *     - bullet             1. numbered step
 *     > TIP: …   > NOTE: …   > WARNING: …     (call-out boxes)
 *     [[diagram:dipole-current|Caption]]       (drawn by the app, some animated)
 *     [[calc:dipole]]                          (calculator)
 *     [[app:settings|Open Settings]]           (button into the app)
 *     [[guide:antenna-feedline|Feedline and SWR]] (button to another guide)
 *
 * Pure Kotlin; tested.
 */
object GuideText {

    /** What guides may embed; the test checks every bundled guide against these. */
    val DIAGRAMS = setOf(
        "wave", "dipole-current", "nvis", "skywave", "dipole-pattern", "inverted-v", "efhw", "vertical", "choke", "throw-line",
        "passband", "waterfall", "timeslots", "frames", "line-of-sight",
    )
    val CALCS = setOf("dipole", "efhw", "vertical", "nvis", "wavelength", "feedline", "swr", "airtime", "rf")
    val APP_TARGETS = setOf("traffic", "team", "map", "prop", "radio", "digest", "settings", "library")

    data class Meta(val id: String, val title: String, val summary: String, val section: String, val order: Int)

    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Paragraph(val text: String) : Block()
        data class Bullets(val items: List<String>) : Block()
        data class Steps(val items: List<String>) : Block()
        data class Callout(val kind: Kind, val text: String) : Block()
        data class Diagram(val id: String, val caption: String) : Block()
        data class Calc(val id: String) : Block()
        data class AppLink(val target: String, val label: String) : Block()
        data class GuideLink(val guideId: String, val label: String) : Block()
    }

    enum class Kind { TIP, NOTE, WARNING }

    data class Guide(val meta: Meta, val blocks: List<Block>) {
        /** Lower-case plain text for search. */
        val searchText: String by lazy {
            (listOf(meta.title, meta.summary) + blocks.map {
                when (it) {
                    is Block.Heading -> it.text
                    is Block.Paragraph -> it.text
                    is Block.Bullets -> it.items.joinToString(" ")
                    is Block.Steps -> it.items.joinToString(" ")
                    is Block.Callout -> it.text
                    is Block.Diagram -> it.caption
                    else -> ""
                }
            }).joinToString(" ").replace("**", "").lowercase()
        }
    }

    private val embed = Regex("""^\[\[(diagram|calc|app|guide):([a-z0-9_-]+)(?:\|(.*))?]]$""")

    fun parse(id: String, text: String): Guide {
        val lines = text.replace("\r", "").lines()
        var i = 0
        val meta = HashMap<String, String>()
        if (lines.firstOrNull()?.trim() == "---") {
            i = 1
            while (i < lines.size && lines[i].trim() != "---") {
                val l = lines[i]
                val c = l.indexOf(':')
                if (c > 0) meta[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
                i++
            }
            i++
        }
        val blocks = ArrayList<Block>()
        val para = StringBuilder()
        var bullets: MutableList<String>? = null
        var steps: MutableList<String>? = null

        fun flush() {
            if (para.isNotEmpty()) { blocks += Block.Paragraph(para.toString().trim()); para.clear() }
            bullets?.let { blocks += Block.Bullets(it) }; bullets = null
            steps?.let { blocks += Block.Steps(it) }; steps = null
        }

        while (i < lines.size) {
            val raw = lines[i++]
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.startsWith("#") -> {
                    flush()
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 3)
                    blocks += Block.Heading(level, line.drop(level).trim())
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    if (bullets == null) flush()
                    bullets = (bullets ?: mutableListOf()).also { it += line.drop(2).trim() }
                }
                Regex("""^\d+[.)]\s+""").containsMatchIn(line) -> {
                    if (steps == null) flush()
                    steps = (steps ?: mutableListOf()).also { it += line.replace(Regex("""^\d+[.)]\s+"""), "") }
                }
                line.startsWith(">") -> {
                    flush()
                    var body = line.drop(1).trim()
                    val kind = Kind.entries.firstOrNull { body.uppercase().startsWith(it.name + ":") }
                    if (kind != null) body = body.drop(kind.name.length + 1).trim()
                    // Following "> " lines continue the call-out.
                    while (i < lines.size && lines[i].trim().startsWith(">")) body += " " + lines[i++].trim().drop(1).trim()
                    blocks += Block.Callout(kind ?: Kind.NOTE, body)
                }
                embed.matches(line) -> {
                    flush()
                    val (type, target, label) = embed.find(line)!!.destructured
                    blocks += when (type) {
                        "diagram" -> Block.Diagram(target, label)
                        "calc" -> Block.Calc(target)
                        "app" -> Block.AppLink(target, label.ifEmpty { target })
                        else -> Block.GuideLink(target, label.ifEmpty { target })
                    }
                }
                else -> {
                    // A continuation line of a list item, or paragraph text.
                    val b = bullets; val s = steps
                    if (raw.startsWith("  ") && b != null) b[b.size - 1] = b.last() + " " + line
                    else if (raw.startsWith("  ") && s != null) s[s.size - 1] = s.last() + " " + line
                    else {
                        if (b != null || s != null) flush()
                        if (para.isNotEmpty()) para.append(' ')
                        para.append(line)
                    }
                }
            }
        }
        flush()
        val m = Meta(
            id = id,
            title = meta["title"] ?: id,
            summary = meta["summary"].orEmpty(),
            section = meta["section"] ?: "Guides",
            order = meta["order"]?.toIntOrNull() ?: 100,
        )
        return Guide(m, blocks)
    }

    /** Split "**bold**" spans: (text, bold) pieces in order. */
    fun inline(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        var bold = false
        text.split("**").forEachIndexed { idx, part ->
            if (idx > 0) bold = !bold
            if (part.isNotEmpty()) out += part to bold
        }
        return out
    }
}
