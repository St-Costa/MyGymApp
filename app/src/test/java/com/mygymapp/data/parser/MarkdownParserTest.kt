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
}
