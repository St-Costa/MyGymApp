package com.mygymapp.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tests the pure `isPowerliftingWeek(date, anchor, interval)` companion, extracted
 * from the SharedPreferences-backed instance method so it can run on plain JVM.
 */
class PowerliftingScheduleRepositoryTest {

    private val anchor = LocalDate.of(2026, 1, 5) // a Monday

    @Test
    fun `anchor week itself is a powerlifting week`() {
        assertTrue(isWeek(anchor, anchor, interval = 4))
    }

    @Test
    fun `every Nth week after anchor is a powerlifting week`() {
        assertTrue(isWeek(anchor.plusWeeks(4), anchor, interval = 4))
        assertTrue(isWeek(anchor.plusWeeks(8), anchor, interval = 4))
        assertTrue(isWeek(anchor.plusWeeks(52), anchor, interval = 4))
    }

    @Test
    fun `off-cycle weeks are not powerlifting weeks`() {
        assertFalse(isWeek(anchor.plusWeeks(1), anchor, interval = 4))
        assertFalse(isWeek(anchor.plusWeeks(2), anchor, interval = 4))
        assertFalse(isWeek(anchor.plusWeeks(3), anchor, interval = 4))
    }

    @Test
    fun `any day of an anchor week counts (Monday through Sunday)`() {
        for (offset in 0..6) {
            assertTrue(
                "day-offset=$offset",
                isWeek(anchor.plusDays(offset.toLong()), anchor, interval = 4),
            )
        }
    }

    @Test
    fun `dates before the anchor use floorMod so past weeks still align`() {
        // 4 weeks before the anchor is also a powerlifting week under floorMod
        assertTrue(isWeek(anchor.minusWeeks(4), anchor, interval = 4))
        assertTrue(isWeek(anchor.minusWeeks(8), anchor, interval = 4))
        // But 1 week before is not
        assertFalse(isWeek(anchor.minusWeeks(1), anchor, interval = 4))
    }

    @Test
    fun `interval of 1 means every week is a powerlifting week`() {
        for (w in -3..12) {
            assertTrue(isWeek(anchor.plusWeeks(w.toLong()), anchor, interval = 1))
        }
    }

    @Test
    fun `interval below 1 is coerced up to 1 rather than crashing`() {
        // Math.floorMod(_, 0) would throw ArithmeticException; the coerceAtLeast
        // in the implementation prevents that.
        assertTrue(isWeek(anchor, anchor, interval = 0))
        assertTrue(isWeek(anchor.plusWeeks(1), anchor, interval = -5))
    }

    private fun isWeek(date: LocalDate, anchor: LocalDate, interval: Int) =
        PowerliftingScheduleRepository.isPowerliftingWeek(date, anchor, interval)
}
