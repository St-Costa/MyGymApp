package com.mygymapp.data.util

/**
 * Pruning rules for `gymdata/logs/app.log` (pure, tested in `LogRetentionTest`).
 *
 * The log is line-oriented, but one *entry* can span several lines: an error logged with a
 * throwable is followed by its indented stack trace. Pruning works on entries, so a stack
 * trace is always kept or dropped together with the line that owns it. A line is the start of
 * an entry iff it begins with a `yyyy-MM-dd` date; anything else continues the previous entry.
 * Lines before the first dated one (a hand-edited or legacy file) form their own entry, which
 * is always kept by the date rule.
 */
object LogRetention {

    private val DATE_PREFIX = Regex("""^\d{4}-\d{2}-\d{2}""")

    fun isEntryStart(line: String): Boolean = DATE_PREFIX.containsMatchIn(line)

    fun groupEntries(lines: List<String>): List<List<String>> {
        val entries = mutableListOf<MutableList<String>>()
        for (line in lines) {
            if (isEntryStart(line) || entries.isEmpty()) entries += mutableListOf(line)
            else entries.last() += line
        }
        return entries
    }

    /**
     * Drops entries dated before [cutoffDate] (`yyyy-MM-dd`, inclusive keep), then drops the
     * oldest remaining entries until the total UTF-8 size (with newlines) is at most
     * [maxBytes]. The newest entry is never dropped by the size rule, even if it alone exceeds
     * the cap — losing the most recent error to make room would defeat the log's purpose.
     */
    fun prune(lines: List<String>, cutoffDate: String, maxBytes: Long): List<String> {
        val dated = groupEntries(lines).filter { entry ->
            val first = entry.first()
            !isEntryStart(first) || first.substring(0, DATE_LEN) >= cutoffDate
        }
        val sizes = dated.map { entry -> entry.sumOf { it.toByteArray(Charsets.UTF_8).size + 1L } }
        var total = sizes.sum()
        var start = 0
        while (total > maxBytes && start < dated.size - 1) {
            total -= sizes[start]
            start++
        }
        return dated.drop(start).flatten()
    }

    /**
     * For the in-app viewer: the newest [maxEntries] entries, newest first, each entry's
     * lines kept in order (so a stack trace still reads top-down under its header line).
     */
    fun newestFirst(lines: List<String>, maxEntries: Int): List<String> =
        groupEntries(lines).takeLast(maxEntries).asReversed().flatten()

    private const val DATE_LEN = 10
}
