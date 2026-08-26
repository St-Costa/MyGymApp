package com.mygymapp.data.repository

import com.mygymapp.data.model.ScaleWeighIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Coverage for the pure aggregation logic in ScaleTrendLoader.kt: [median] (shared with
 * [com.mygymapp.data.repository.CardioMetricsTrendLoader]) and [List<WeeklyPoint>.averageWeeklyDelta].
 * [ScaleTrendReport.weeklyMedianWeights] etc. are exercised indirectly through these, since they're
 * all thin wrappers around the same private `weeklyMedians` grouping.
 */
class ScaleTrendLoaderTest {

    private fun weighIn(date: String, weightKg: Double) = ScaleWeighIn(
        id = date,
        date = date,
        recordedAt = "${date}T08:00:00",
        weightKg = weightKg,
        bmi = 0.0,
        bodyFatPercent = 0.0,
        leanMassPercent = 0.0,
    )

    @Test
    fun `median of an odd-sized list is the middle value`() {
        assertEquals(2.0, median(listOf(3.0, 1.0, 2.0)), 0.0001)
    }

    @Test
    fun `median of an even-sized list averages the two middle values`() {
        assertEquals(2.5, median(listOf(1.0, 2.0, 3.0, 4.0)), 0.0001)
    }

    @Test
    fun `median of a single value list is that value`() {
        assertEquals(42.0, median(listOf(42.0)), 0.0001)
    }

    @Test
    fun `averageWeeklyDelta is null with fewer than two points`() {
        assertNull(emptyList<WeeklyPoint>().averageWeeklyDelta())
        assertNull(listOf(WeeklyPoint(LocalDate.of(2026, 1, 5), 80.0)).averageWeeklyDelta())
    }

    @Test
    fun `averageWeeklyDelta averages the deltas between consecutive weeks`() {
        // 80 -> 81 -> 79: deltas are +1 and -2, average -0.5
        val points = listOf(
            WeeklyPoint(LocalDate.of(2026, 1, 5), 80.0),
            WeeklyPoint(LocalDate.of(2026, 1, 12), 81.0),
            WeeklyPoint(LocalDate.of(2026, 1, 19), 79.0),
        )
        assertEquals(-0.5, points.averageWeeklyDelta()!!, 0.0001)
    }

    // weeklyMedians() only looks back 8 weeks from *today* (LocalDate.now()), so these two
    // tests anchor to the current week's Monday rather than a fixed calendar date.
    private val thisWeekMonday: LocalDate =
        LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

    @Test
    fun `weeklyMedianWeights groups by ISO week Monday and takes the median per week`() {
        // Same week: median of 80/82/81 -> 81
        val mon = thisWeekMonday.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val wed = thisWeekMonday.plusDays(2).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val fri = thisWeekMonday.plusDays(4).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val report = ScaleTrendReport(
            weighIns = listOf(
                weighIn(mon, 80.0),
                weighIn(wed, 82.0),
                weighIn(fri, 81.0),
            )
        )

        val weekly = report.weeklyMedianWeights
        assertEquals(1, weekly.size)
        assertEquals(thisWeekMonday, weekly[0].weekStart)
        assertEquals(81.0, weekly[0].value, 0.0001)
    }

    @Test
    fun `weeklyMedianBmi filters out zero BMI entries`() {
        val day1 = thisWeekMonday.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val day2 = thisWeekMonday.plusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val report = ScaleTrendReport(
            weighIns = listOf(
                ScaleWeighIn("d1", day1, "${day1}T08:00:00", 80.0, bmi = 0.0, bodyFatPercent = 0.0, leanMassPercent = 0.0),
                ScaleWeighIn("d2", day2, "${day2}T08:00:00", 80.0, bmi = 24.5, bodyFatPercent = 0.0, leanMassPercent = 0.0),
            )
        )

        val weekly = report.weeklyMedianBmi
        assertEquals(1, weekly.size)
        assertEquals(24.5, weekly[0].value, 0.0001)
    }

    @Test
    fun `hasData is false for an empty report`() {
        assertEquals(false, ScaleTrendReport().hasData)
    }

    @Test
    fun `latest returns the last weigh-in in list order`() {
        val report = ScaleTrendReport(
            weighIns = listOf(weighIn("2026-01-05", 80.0), weighIn("2026-01-06", 79.5))
        )
        assertEquals("2026-01-06", report.latest?.date)
    }
}
