package com.mygymapp.data.repository

import com.mygymapp.data.model.WorkoutSession
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

data class CardioMetricsTrendReport(
    val restingHrWeekly: List<WeeklyPoint> = emptyList(),
    val hrr60sWeekly: List<WeeklyPoint> = emptyList(),
    val vo2maxMonthly: List<WeeklyPoint> = emptyList(),
) {
    val hasData: Boolean get() = restingHrWeekly.isNotEmpty() || hrr60sWeekly.isNotEmpty() || vo2maxMonthly.isNotEmpty()
}

/**
 * Loads Polar-derived cardio metrics from completed workout sessions and
 * aggregates them for trend graphs. Each metric uses a different window —
 * chosen per its own noise characteristics, not a one-size-fits-all:
 * - Resting HR / HRR60s: high day-to-day and session-to-session noise, so
 *   weekly median over the last 2 months (same cadence as the scale charts).
 * - VO2max: changes slowly (weeks/months) and each session's estimate is
 *   itself noisy, so it's aggregated over 4-week windows across 6 months —
 *   a single week of data would be too sparse/noisy to mean anything.
 */
@Singleton
class CardioMetricsTrendLoader @Inject constructor(
    private val workoutRepository: WorkoutRepository,
) {
    suspend fun load(): CardioMetricsTrendReport {
        val today = LocalDate.now()
        // Exactly 8 weeks (incl. the current one), not "2 calendar months" —
        // the latter rounds up to 9-10 weeks once snapped to Monday boundaries.
        val eightWeeksAgo = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(7)
        val sixMonthsAgo = today.minusMonths(6)

        val sessions = workoutRepository.getSessionsInRange(sixMonthsAgo, today)
            .filter { it.completedAt.isNotBlank() }

        val recentSessions = sessions.filter { !LocalDate.parse(it.date).isBefore(eightWeeksAgo) }

        return CardioMetricsTrendReport(
            restingHrWeekly = weeklyMedian(recentSessions, { it.restingHr > 0 }) { it.restingHr.toDouble() },
            hrr60sWeekly = weeklyMedian(recentSessions, { it.hrr60s > 0.0 }) { it.hrr60s },
            vo2maxMonthly = fourWeekMedian(sessions, sixMonthsAgo, { it.vo2max > 0.0 }) { it.vo2max },
        )
    }

    private fun weeklyMedian(
        sessions: List<WorkoutSession>,
        filter: (WorkoutSession) -> Boolean,
        valueOf: (WorkoutSession) -> Double,
    ): List<WeeklyPoint> {
        val grouped = sessions
            .filter(filter)
            .groupBy { LocalDate.parse(it.date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        return grouped.entries
            .sortedBy { it.key }
            .map { (weekStart, entries) -> WeeklyPoint(weekStart, median(entries.map(valueOf))) }
    }

    /** One point per 4-week window, labeled by the window's start Monday. */
    private fun fourWeekMedian(
        sessions: List<WorkoutSession>,
        rangeStart: LocalDate,
        filter: (WorkoutSession) -> Boolean,
        valueOf: (WorkoutSession) -> Double,
    ): List<WeeklyPoint> {
        val windowStart = rangeStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val grouped = sessions
            .filter(filter)
            .groupBy { date ->
                val d = LocalDate.parse(date.date)
                val weeksSinceStart = java.time.temporal.ChronoUnit.WEEKS.between(windowStart, d)
                windowStart.plusWeeks((weeksSinceStart / 4) * 4)
            }
        return grouped.entries
            .sortedBy { it.key }
            .map { (windowStartDate, entries) -> WeeklyPoint(windowStartDate, median(entries.map(valueOf))) }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
