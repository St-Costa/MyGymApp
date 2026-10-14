package com.mygymapp.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CrashReportFormatTest {

    private val at = LocalDateTime.of(2026, 10, 14, 16, 30, 12)

    @Test
    fun `file name round-trips kind and timestamp`() {
        val name = CrashReportFormat.fileName("crash", at)
        assertEquals("crash-20261014-163012.txt", name)
        assertEquals(at, CrashReportFormat.timestampOf(name))
        assertEquals("crash", CrashReportFormat.kindOf(name))
        assertEquals(at, CrashReportFormat.timestampOf("anr-20261014-163012-2.txt"))
    }

    @Test
    fun `foreign names are not reports`() {
        assertNull(CrashReportFormat.timestampOf("app.log"))
        assertNull(CrashReportFormat.timestampOf(".exit_info_seen"))
        assertNull(CrashReportFormat.timestampOf("crash-2026-10-14.txt"))
        assertNull(CrashReportFormat.kindOf("notes.txt"))
    }

    @Test
    fun `expired keeps the newest maxCount and drops anything past the age cutoff`() {
        val names = (1..5).map { CrashReportFormat.fileName("crash", at.minusDays(it.toLong())) } +
            CrashReportFormat.fileName("anr", at.minusDays(200)) + "README.txt"
        val expired = CrashReportFormat.expired(names, today = at.toLocalDate(), maxCount = 3, maxAgeDays = 90)
        assertEquals(
            setOf(
                CrashReportFormat.fileName("crash", at.minusDays(4)),
                CrashReportFormat.fileName("crash", at.minusDays(5)),
                CrashReportFormat.fileName("anr", at.minusDays(200)),
            ),
            expired.toSet(),
        )
        assertFalse("README.txt" in expired)
    }

    @Test
    fun `nothing expires under both limits`() {
        val names = listOf(CrashReportFormat.fileName("crash", at))
        assertTrue(CrashReportFormat.expired(names, LocalDate.of(2026, 10, 15)).isEmpty())
    }

    @Test
    fun `format writes header fields, skips nulls, and fences sections`() {
        val text = CrashReportFormat.format(
            title = "MyGymApp crash report",
            at = at,
            fields = listOf("app" to "1.186", "screen" to null, "thread" to "main"),
            sections = listOf("exception" to "boom", "last actions (oldest first)" to ""),
        )
        assertEquals(
            """
            MyGymApp crash report
            time: 2026-10-14 16:30:12
            app: 1.186
            thread: main

            --- exception ---
            boom

            --- last actions (oldest first) ---
            (empty)

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `stack trace includes the cause chain`() {
        val t = RuntimeException("outer", IllegalArgumentException("inner"))
        val trace = CrashReportFormat.stackTrace(t)
        assertTrue(trace.startsWith("java.lang.RuntimeException: outer"))
        assertTrue(trace.contains("Caused by: java.lang.IllegalArgumentException: inner"))
    }

    @Test
    fun `exit reasons - only abnormal deaths get a report`() {
        assertEquals("anr", ExitReasons.reportKind(6))
        assertEquals("native", ExitReasons.reportKind(5))
        assertEquals("oom", ExitReasons.reportKind(3))
        assertEquals("oom", ExitReasons.reportKind(9))
        assertEquals("killed", ExitReasons.reportKind(2))
        // Java crash: already written by the in-process handler.
        assertNull(ExitReasons.reportKind(4))
        // Routine exits: user swipe, background kill, update.
        listOf(0, 1, 10, 11, 13, 16).forEach { assertNull("reason $it", ExitReasons.reportKind(it)) }
        assertTrue(ExitReasons.hasTextTrace(6))
        assertFalse(ExitReasons.hasTextTrace(5))
        assertEquals("USER_REQUESTED", ExitReasons.name(10))
        assertEquals("REASON_99", ExitReasons.name(99))
    }
}
