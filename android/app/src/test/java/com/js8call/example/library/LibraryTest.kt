package com.js8call.example.library

import com.js8call.example.library.GuideText.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryTest {

    @Test
    fun parsesTheGuideFormat() {
        val g = GuideText.parse("x", """
            ---
            title: Test guide
            summary: One line
            section: Antennas
            order: 5
            ---
            # Heading
            First line
            second line with **bold** text.

            - one
            - two
              continued
            1. step a
            2. step b
            > WARNING: Hot
            > ends.
            [[diagram:wave|Caption here]]
            [[calc:dipole]]
            [[app:settings|Open Settings]]
            [[guide:antenna-basics|Basics]]
            Plain [[not an embed]] stays text.
        """.trimIndent())
        assertEquals(GuideText.Meta("x", "Test guide", "One line", "Antennas", 5), g.meta)
        val b = g.blocks
        assertEquals(Block.Heading(1, "Heading"), b[0])
        assertEquals(Block.Paragraph("First line second line with **bold** text."), b[1])
        assertEquals(Block.Bullets(listOf("one", "two continued")), b[2])
        assertEquals(Block.Steps(listOf("step a", "step b")), b[3])
        assertEquals(Block.Callout(GuideText.Kind.WARNING, "Hot ends."), b[4])
        assertEquals(Block.Diagram("wave", "Caption here"), b[5])
        assertEquals(Block.Calc("dipole"), b[6])
        assertEquals(Block.AppLink("settings", "Open Settings"), b[7])
        assertEquals(Block.GuideLink("antenna-basics", "Basics"), b[8])
        assertEquals(Block.Paragraph("Plain [[not an embed]] stays text."), b[9])
        assertEquals(listOf("a " to false, "b" to true, " c" to false), GuideText.inline("a **b** c"))
        assertTrue(g.searchText.contains("hot ends"))
    }

    @Test
    fun everyBundledGuideIsComplete() {
        val dir = File("src/main/assets/library")
        val files = dir.listFiles { f -> f.name.endsWith(".md") }!!.sortedBy { it.name }
        assertTrue("guides found", files.size >= 12)
        val guides = files.map { GuideText.parse(it.nameWithoutExtension, it.readText()) }
        val ids = guides.map { it.meta.id }.toSet()
        listOf("start-listener", "start-operator", "start-netcontrol").forEach { assertTrue(it, it in ids) }
        guides.forEach { g ->
            assertTrue("${g.meta.id} has a title", g.meta.title != g.meta.id)
            assertTrue("${g.meta.id} has a summary", g.meta.summary.isNotBlank())
            assertTrue("${g.meta.id} section ${g.meta.section}", g.meta.section in Library.SECTIONS)
            g.blocks.forEach { b ->
                when (b) {
                    is Block.Diagram -> assertTrue("${g.meta.id}: diagram ${b.id}", b.id in GuideText.DIAGRAMS)
                    is Block.Calc -> assertTrue("${g.meta.id}: calc ${b.id}", b.id in GuideText.CALCS)
                    is Block.AppLink -> assertTrue("${g.meta.id}: app ${b.target}", b.target in GuideText.APP_TARGETS)
                    is Block.GuideLink -> assertTrue("${g.meta.id}: guide ${b.guideId}", b.guideId in ids)
                    is Block.Paragraph -> assertTrue("${g.meta.id}: stray embed in \"${b.text}\"", !b.text.contains("[[") || b.text.contains("not an embed"))
                    is Block.Bullets -> b.items.forEach { assertTrue("${g.meta.id}: stray embed in \"$it\"", !it.contains("[[")) }
                    is Block.Steps -> b.items.forEach { assertTrue("${g.meta.id}: stray embed in \"$it\"", !it.contains("[[")) }
                    else -> {}
                }
                // Unbalanced bold markers would show as literal asterisks.
                val text = when (b) { is Block.Paragraph -> b.text; is Block.Callout -> b.text; else -> "" }
                assertEquals("${g.meta.id}: ** pairs in \"$text\"", 0, Regex("""\*\*""").findAll(text).count() % 2)
            }
        }
        // Every role has its start guide.
        listOf("listener", "operator", "netcontrol").forEach { assertTrue(Library.startGuideFor(it) in ids) }
    }

    @Test
    fun calculatorsMatchTheRulesOfThumb() {
        assertEquals(66.12, Calculators.dipoleTotalFt(7.078), 0.01)            // 40 m dipole ≈ 66 ft
        assertEquals(42.36, Calculators.wavelengthM(7.078), 0.01)
        assertEquals(33.24, Calculators.quarterWaveFt(7.04), 0.01)
        val nvis = Calculators.nvisHeightFt(7.078)
        assertEquals(13.9, nvis.start, 0.1); assertEquals(34.7, nvis.endInclusive, 0.1)
        assertEquals(0.0, Calculators.reflectedFraction(1.0), 1e-9)
        assertEquals(1.0 / 9, Calculators.reflectedFraction(2.0), 1e-9)       // 2:1 → 11 %
        assertEquals(0.51, Calculators.mismatchLossDb(2.0), 0.01)
        // Table points come back exactly; in between is between.
        assertEquals(1.4, Calculators.Coax.RG58.dbPer100Ft(10.0), 1e-9)
        assertEquals(2.5, Calculators.Coax.RG58.dbPer100Ft(30.0), 1e-9)
        val mid = Calculators.Coax.RG58.dbPer100Ft(14.078)
        assertTrue(mid in 1.4..2.5)
        val r = Calculators.feedline(Calculators.Coax.RG58, 100.0, 10.0, 100.0)
        assertEquals(1.4, r.lossDb, 1e-9)
        assertEquals(72.4, r.powerOutW, 0.1)
        assertEquals("66.1 ft (20.15 m)", Calculators.ftAndM(66.12))
    }
}
