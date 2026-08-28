package com.mygymapp.data.parser

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

data class MarkdownDocument(
    val frontmatter: Map<String, Any>,
    val body: String,
)

object MarkdownParser {

    private val loadSettings = LoadSettings.builder().build()

    fun parse(content: String): MarkdownDocument {
        val trimmed = content.trim()
        if (!trimmed.startsWith("---")) {
            return MarkdownDocument(emptyMap(), trimmed)
        }

        val secondDelimiter = trimmed.indexOf("---", 3)
        if (secondDelimiter == -1) {
            return MarkdownDocument(emptyMap(), trimmed)
        }

        val yamlContent = trimmed.substring(3, secondDelimiter).trim()
        val body = trimmed.substring(secondDelimiter + 3).trim()

        val yaml = Load(loadSettings)
        val parsed = yaml.loadFromString(yamlContent)

        @Suppress("UNCHECKED_CAST")
        val frontmatter = (parsed as? Map<String, Any>) ?: emptyMap()

        return MarkdownDocument(frontmatter, body)
    }

    fun serialize(frontmatter: Map<String, Any?>, body: String): String {
        val sb = StringBuilder()
        sb.appendLine("---")
        serializeYaml(sb, frontmatter, indent = 0)
        sb.appendLine("---")
        if (body.isNotBlank()) {
            sb.appendLine()
            sb.append(body)
            if (!body.endsWith("\n")) sb.appendLine()
        }
        return sb.toString()
    }

    private fun serializeYaml(sb: StringBuilder, map: Map<String, Any?>, indent: Int) {
        val prefix = "  ".repeat(indent)
        for ((key, value) in map) {
            when (value) {
                null -> sb.appendLine("$prefix$key:")
                is String -> sb.appendLine("$prefix$key: \"${escapeYamlString(value)}\"")
                is Boolean -> sb.appendLine("$prefix$key: $value")
                is Number -> sb.appendLine("$prefix$key: ${formatValue(value)}")
                is List<*> -> {
                    sb.appendLine("$prefix$key:")
                    serializeList(sb, value, dashIndent = indent + 1)
                }
                is Map<*, *> -> {
                    sb.appendLine("$prefix$key:")
                    @Suppress("UNCHECKED_CAST")
                    serializeYaml(sb, value as Map<String, Any?>, indent + 1)
                }
                else -> sb.appendLine("$prefix$key: \"$value\"")
            }
        }
    }

    /**
     * Renders a list whose `- ` markers sit at [dashIndent] levels of indent. A scalar item is
     * `- value`; a map item hangs its first key off the dash and aligns the rest one level
     * deeper. Byte-identical to the pre-refactor serializer so on-disk YAML round-trips.
     */
    private fun serializeList(sb: StringBuilder, list: List<*>, dashIndent: Int) {
        val dashPrefix = "  ".repeat(dashIndent)
        for (item in list) {
            when (item) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val entries = (item as Map<String, Any?>).entries.toList()
                    entries.forEachIndexed { i, (k, v) ->
                        serializeMapEntry(sb, k, v, dashIndent, isFirst = i == 0)
                    }
                }
                else -> sb.appendLine("$dashPrefix- ${formatValue(item)}")
            }
        }
    }

    /**
     * One key of a map that is an item of a list whose dash sits at [dashIndent]. The first key
     * hangs off `"  "*dashIndent + "- "`; the rest align one level deeper (`"  "*(dashIndent+1)`).
     */
    private fun serializeMapEntry(
        sb: StringBuilder,
        key: String,
        value: Any?,
        dashIndent: Int,
        isFirst: Boolean,
    ) {
        val keyPrefix = "  ".repeat(dashIndent + 1)
        val linePrefix = if (isFirst) "  ".repeat(dashIndent) + "- " else keyPrefix
        when (value) {
            is List<*> -> {
                sb.appendLine("$linePrefix$key:")
                serializeList(sb, value, dashIndent = dashIndent + 2)
            }
            is Map<*, *> -> {
                sb.appendLine("$linePrefix$key:")
                @Suppress("UNCHECKED_CAST")
                serializeYaml(sb, value as Map<String, Any?>, dashIndent + 2)
            }
            else -> sb.appendLine("$linePrefix$key: ${formatValue(value)}")
        }
    }

    private fun formatValue(value: Any?): String = when (value) {
        null -> ""
        is String -> "\"${escapeYamlString(value)}\""
        is Boolean -> value.toString()
        is Double -> formatDouble(value)
        is Float -> formatDouble(value.toDouble())
        is Number -> value.toString()
        else -> "\"${escapeYamlString(value.toString())}\""
    }

    /**
     * Escapes a value going into a double-quoted YAML scalar. Every frontmatter string field
     * (exercise/routine/bodypart names, links) is free text the user typed — a literal `"` or
     * `\` would otherwise terminate/corrupt the quoted scalar early, and a literal newline
     * would break the single-line `key: "value"` shape entirely. Either failure mode is
     * silent: the file is written corrupted, then discarded by the `catch (_: Exception) {
     * /* Skip malformed */ }` every repository's `ensureLoaded()`/read path already has —
     * the record just vanishes on next load with no visible error.
     */
    private fun escapeYamlString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")

    // Round doubles to 2 decimals on write so session YAML stays readable.
    private fun formatDouble(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "0.0"
        val rounded = Math.round(v * 100.0) / 100.0
        return rounded.toString()
    }
}
