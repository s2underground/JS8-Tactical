package com.js8call.example.library

import android.content.Context

/** Guides bundled as Markdown files in assets/library, parsed once and kept in memory. */
object Library {
    @Volatile private var cache: List<GuideText.Guide>? = null

    /** Sections in display order; anything else goes last. */
    val SECTIONS = listOf("Start here", "JS8 from zero", "Antennas", "Operating", "Tools")

    fun all(ctx: Context): List<GuideText.Guide> {
        cache?.let { return it }
        val am = ctx.applicationContext.assets
        val guides = (am.list("library") ?: emptyArray()).filter { it.endsWith(".md") }.mapNotNull { name ->
            try {
                val text = am.open("library/$name").bufferedReader().use { it.readText() }
                GuideText.parse(name.removeSuffix(".md"), text)
            } catch (e: Exception) {
                android.util.Log.w("Library", "bad guide $name", e)
                null
            }
        }.sortedWith(compareBy({ SECTIONS.indexOf(it.meta.section).let { i -> if (i < 0) 99 else i } }, { it.meta.order }, { it.meta.title }))
        cache = guides
        return guides
    }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.meta.id == id }

    fun search(ctx: Context, query: String): List<GuideText.Guide> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return all(ctx)
        return all(ctx).filter { g -> words.all { g.searchText.contains(it) } }
    }

    /** The "Start here" guide for a first-run role. */
    fun startGuideFor(role: String) = when (role) {
        "listener" -> "start-listener"
        "netcontrol" -> "start-netcontrol"
        else -> "start-operator"
    }
}
