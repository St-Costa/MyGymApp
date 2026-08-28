package com.mygymapp.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [MarkdownParser]'s YAML frontmatter round-trip, focused on the string-escaping
 * fix in [MarkdownParser.serialize]/[MarkdownParser.parse]: a frontmatter string is free text
 * the user typed (exercise/routine name, bodypart, link) — before this fix, a literal `"` or
 * `\` in that text would write a corrupted YAML file that every repository's `ensureLoaded()`
 * then silently discards on the next read (`catch (_: Exception) { /* Skip malformed */ }`),
 * making the record vanish with no visible error.
 */
class MarkdownParserTest {

    private fun roundTripString(value: String): String {
        val serialized = MarkdownParser.serialize(mapOf("name" to value), body = "")
        val parsed = MarkdownParser.parse(serialized)
        return parsed.frontmatter["name"].toString()
    }

    @Test
    fun `plain string round-trips unchanged`() {
        assertEquals("Bench Press", roundTripString("Bench Press"))
    }

    @Test
    fun `double quote in value round-trips instead of corrupting the YAML`() {
        assertEquals("""Push-up "diamond" variant""", roundTripString("""Push-up "diamond" variant"""))
    }

    @Test
    fun `backslash in value round-trips`() {
        assertEquals("""Legs \ Core""", roundTripString("""Legs \ Core"""))
    }

    @Test
    fun `newline in value is flattened to a space rather than breaking the single-line scalar`() {
        assertEquals("Line one Line two", roundTripString("Line one\nLine two"))
    }

    @Test
    fun `combination of quotes and backslashes round-trips`() {
        val tricky = """He said \"go heavy\" for chest & \back\"""
        assertEquals(tricky, roundTripString(tricky))
    }

    @Test
    fun `serialize omits the body when blank`() {
        val serialized = MarkdownParser.serialize(mapOf("id" to "ex-1"), body = "")
        assertEquals(false, serialized.contains("\n\n\n"))
    }

    @Test
    fun `parse on content without frontmatter delimiters returns empty frontmatter`() {
        val doc = MarkdownParser.parse("just plain body text")
        assertEquals(emptyMap<String, Any>(), doc.frontmatter)
        assertEquals("just plain body text", doc.body)
    }

    @Test
    fun `parse on unterminated frontmatter falls back to treating everything as body`() {
        val doc = MarkdownParser.parse("---\nid: \"ex-1\"\nno closing delimiter")
        assertEquals(emptyMap<String, Any>(), doc.frontmatter)
    }

    @Test
    fun `nested list of maps with an inner list round-trips (exercise-stats sidecar shape)`() {
        // Mirrors ExerciseStatsParser's `contexts: [ { context, pr: [..], previousSets: [..] } ]`
        // — the deepest path through the serializer, and the one the sidecar refactor touched.
        val fm = linkedMapOf<String, Any?>(
            "exerciseId" to "ex-abcd1234",
            "schemaVersion" to 1,
            "contexts" to listOf(
                linkedMapOf<String, Any?>(
                    "context" to "NORMAL",
                    "previousSessionDate" to "2026-08-10",
                    "hasPriorRealTonnage" to true,
                    "pr" to listOf(linkedMapOf<String, Any?>("reps" to 6, "weight" to 90.0)),
                    "previousSets" to listOf(
                        linkedMapOf<String, Any?>("reps" to 8, "weight" to 80.0),
                        linkedMapOf<String, Any?>("reps" to 6, "weight" to 82.5),
                    ),
                ),
            ),
        )
        val parsed = MarkdownParser.parse(MarkdownParser.serialize(fm, body = "")).frontmatter

        assertEquals("ex-abcd1234", parsed["exerciseId"])
        @Suppress("UNCHECKED_CAST")
        val ctx = (parsed["contexts"] as List<Map<String, Any?>>).single()
        assertEquals("NORMAL", ctx["context"])
        assertEquals(true, ctx["hasPriorRealTonnage"])
        @Suppress("UNCHECKED_CAST")
        val pr = (ctx["pr"] as List<Map<String, Any?>>).single()
        assertEquals(6, (pr["reps"] as Number).toInt())
        assertEquals(90.0, (pr["weight"] as Number).toDouble(), 1e-9)
        @Suppress("UNCHECKED_CAST")
        val prev = ctx["previousSets"] as List<Map<String, Any?>>
        assertEquals(2, prev.size)
        assertEquals(82.5, (prev[1]["weight"] as Number).toDouble(), 1e-9)
    }
}
