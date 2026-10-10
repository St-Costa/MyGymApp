package com.mygymapp.data.steps

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Sprint 5: the step average divides by touched calendar days, not truncated 24h blocks
 * (36h of steps / 1 "day" inflated the average after any skipped day).
 */
class CalendarDaysSpannedTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `same calendar day is 1`() {
        assertEquals(
            1,
            calendarDaysSpanned(
                Instant.parse("2026-10-09T08:00:00Z"),
                Instant.parse("2026-10-09T09:00:00Z"),
                utc,
            ),
        )
    }

    @Test
    fun `36h across midnight is 2, not 1`() {
        assertEquals(
            2,
            calendarDaysSpanned(
                Instant.parse("2026-10-09T08:00:00Z"),
                Instant.parse("2026-10-10T20:00:00Z"),
                utc,
            ),
        )
    }

    @Test
    fun `three calendar days is 3`() {
        assertEquals(
            3,
            calendarDaysSpanned(
                Instant.parse("2026-10-09T23:00:00Z"),
                Instant.parse("2026-10-11T01:00:00Z"),
                utc,
            ),
        )
    }

    @Test
    fun `midnight boundary counts both days`() {
        assertEquals(
            2,
            calendarDaysSpanned(
                Instant.parse("2026-10-09T23:59:00Z"),
                Instant.parse("2026-10-10T00:01:00Z"),
                utc,
            ),
        )
    }

    @Test
    fun `empty or inverted range is 1`() {
        val now = Instant.parse("2026-10-10T00:00:00Z")
        assertEquals(1, calendarDaysSpanned(now, now, utc))
        assertEquals(
            1,
            calendarDaysSpanned(
                Instant.parse("2026-10-11T00:00:00Z"),
                Instant.parse("2026-10-10T00:00:00Z"),
                utc,
            ),
        )
    }

    @Test
    fun `zone shifts the calendar days`() {
        // 23:30 UTC Oct 9 is already Oct 10 in +02:00 — same instant, different day count.
        val since = Instant.parse("2026-10-09T23:30:00Z")
        val now = Instant.parse("2026-10-10T00:30:00Z")
        assertEquals(2, calendarDaysSpanned(since, now, utc))
        assertEquals(1, calendarDaysSpanned(since, now, ZoneId.of("Europe/Rome")))
    }
}
