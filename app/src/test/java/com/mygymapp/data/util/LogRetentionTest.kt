package com.mygymapp.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LogRetentionTest {

    private val oldError = listOf(
        "2026-09-01 10:00:00 E Sync: failed",
        "    java.io.IOException: boom",
        "    \tat com.mygymapp.Foo.bar(Foo.kt:1)",
    )
    private val recent = listOf(
        "2026-10-10 08:00:00 I App: Process start",
        "2026-10-14 16:00:00 W PolarManager: reconnect failed",
    )

    @Test
    fun `stack-trace lines stay grouped with their header`() {
        val entries = LogRetention.groupEntries(oldError + recent)
        assertEquals(3, entries.size)
        assertEquals(oldError, entries[0])
    }

    @Test
    fun `date cutoff drops a whole entry including its stack trace`() {
        val kept = LogRetention.prune(oldError + recent, cutoffDate = "2026-09-14", maxBytes = Long.MAX_VALUE)
        assertEquals(recent, kept)
    }

    @Test
    fun `leading undated lines are kept by the date rule`() {
        val legacy = listOf("garbage header")
        val kept = LogRetention.prune(legacy + recent, cutoffDate = "2026-10-11", maxBytes = Long.MAX_VALUE)
        assertEquals(legacy + recent.last(), kept)
    }

    @Test
    fun `size cap drops oldest entries first`() {
        val lines = (1..10).map { "2026-10-14 10:00:%02d I T: msg".format(it) }
        val oneLine = lines.first().length + 1L
        val kept = LogRetention.prune(lines, cutoffDate = "2026-01-01", maxBytes = oneLine * 4)
        assertEquals(lines.takeLast(4), kept)
    }

    @Test
    fun `size cap never drops the newest entry`() {
        val big = listOf("2026-10-14 10:00:00 E T: " + "x".repeat(500))
        val kept = LogRetention.prune(recent + big, cutoffDate = "2026-01-01", maxBytes = 10)
        assertEquals(big, kept)
    }

    @Test
    fun `newestFirst reverses entries but not lines inside one`() {
        val shown = LogRetention.newestFirst(oldError + recent, maxEntries = 2)
        assertEquals(listOf(recent[1], recent[0]), shown)
        val all = LogRetention.newestFirst(oldError + recent, maxEntries = 10)
        assertEquals(oldError, all.takeLast(3))
    }

    @Test
    fun `formatEntry indents the stack trace so pruning treats it as continuation`() {
        val text = AppLogger.formatEntry(
            LocalDateTime.of(2026, 10, 14, 9, 31, 22), 'E', "Tag", "line1\nline2", IllegalStateException("bad"),
        )
        val lines = text.trimEnd('\n').lines()
        assertEquals("2026-10-14 09:31:22 E Tag: line1 line2", lines[0])
        assertTrue(lines.size > 1)
        lines.drop(1).forEach { assertFalse(it, LogRetention.isEntryStart(it)) }
        assertEquals(1, LogRetention.groupEntries(lines).size)
    }
}
