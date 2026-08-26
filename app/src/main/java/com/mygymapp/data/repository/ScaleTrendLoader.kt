package com.mygymapp.data.repository

import com.mygymapp.data.model.ScaleWeighIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class WeeklyPoint(
    val weekStart: LocalDate,
    val value: Double,
) {
    val weekLabel: String get() = "W${weekStart.get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear())}"

    /** Abbreviated month name (e.g. "Gen", "Feb") — used for multi-week windows where a week number is less readable. */
    val monthLabel: String get() = weekStart.month
        .getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
        .replaceFirstChar { it.uppercase() }
}

/** Average of the week-to-week deltas (last - first, divided by number of gaps). Null if fewer than 2 points. */
fun List<WeeklyPoint>.averageWeeklyDelta(): Double? {
    if (size < 2) return null
    var total = 0.0
    for (i in 1 until size) {
        total += this[i].value - this[i - 1].value
    }
    return total / (size - 1)
}

data class ScaleTrendReport(
    val weighIns: List<ScaleWeighIn> = emptyList(),
) {
    val hasData: Boolean get() = weighIns.isNotEmpty()
    val latest: ScaleWeighIn? get() = weighIns.lastOrNull()

    /** Fixed Monday..Sunday slots for the current week; null where no weigh-in was recorded. */
    val currentWeekSlots: List<ScaleWeighIn?> get() {
        val mondayThisWeek = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val byDate = weighIns.associateBy { it.date }
        return (0..6).map { offset ->
            byDate[mondayThisWeek.plusDays(offset.toLong()).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)]
        }
    }

    /** One point per ISO week (Monday) over the last 2 months, value = median weight that week. */
    val weeklyMedianWeights: List<WeeklyPoint> get() = weeklyMedians { it.weightKg }

    /** One point per ISO week (Monday) over the last 2 months, value = median BMI that week. */
    val weeklyMedianBmi: List<WeeklyPoint> get() = weeklyMedians(filter = { it.bmi > 0.0 }) { it.bmi }

    /** Median of this week's (Mon-Sun so far) weights, or null if no weigh-in yet this week. */
    val currentWeekMedianWeight: Double? get() {
        val weights = currentWeekSlots.mapNotNull { it?.weightKg }
        return if (weights.isEmpty()) null else median(weights)
    }

    /** Median of this week's BMI values, or null if no weigh-in yet this week. */
    val currentWeekMedianBmi: Double? get() {
        val values = currentWeekSlots.mapNotNull { it?.bmi }.filter { it > 0.0 }
        return if (values.isEmpty()) null else median(values)
    }

    /** Median of this week's body-fat % values, or null if no weigh-in yet this week. */
    val currentWeekMedianFatPercent: Double? get() {
        val values = currentWeekSlots.mapNotNull { it?.bodyFatPercent }.filter { it > 0.0 }
        return if (values.isEmpty()) null else median(values)
    }

    /**
     * Index (0=Monday..6=Sunday) into [currentWeekSlots] whose weight is
     * closest to this week's median — the point to highlight as "the median".
     * Null if there's no data this week.
     */
    val currentWeekMedianSlotIndex: Int? get() {
        val median = currentWeekMedianWeight ?: return null
        return currentWeekSlots.indices
            .filter { currentWeekSlots[it] != null }
            .minByOrNull { kotlin.math.abs(currentWeekSlots[it]!!.weightKg - median) }
    }

    /** One point per ISO week (Monday) over the last 2 months, value = median body-fat % that week. */
    val weeklyMedianFatPercent: List<WeeklyPoint> get() = weeklyMedians(filter = { it.bodyFatPercent > 0.0 }) { it.bodyFatPercent }

    /** One point per ISO week (Monday) over the last 2 months, value = median lean-mass % that week. */
    val weeklyMedianLeanPercent: List<WeeklyPoint> get() = weeklyMedians(filter = { it.bodyFatPercent > 0.0 }) { it.leanMassPercent }

    private fun weeklyMedians(
        filter: (ScaleWeighIn) -> Boolean = { true },
        valueOf: (ScaleWeighIn) -> Double,
    ): List<WeeklyPoint> {
        // Exactly 8 weeks (incl. the current one), not "2 calendar months" —
        // the latter rounds up to 9-10 weeks once snapped to Monday boundaries.
        val currentWeekMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val cutoff = currentWeekMonday.minusWeeks(7)
        val grouped = weighIns
            .filter { filter(it) && !LocalDate.parse(it.date).isBefore(cutoff) }
            .groupBy { LocalDate.parse(it.date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        return grouped.entries
            .sortedBy { it.key }
            .map { (weekStart, entries) -> WeeklyPoint(weekStart, median(entries.map(valueOf))) }
    }
}

/**
 * Middle value of the sorted list (average of the two middle values when the size is
 * even). Shared by [ScaleTrendReport] and [CardioMetricsTrendLoader] — both bucket
 * samples into weekly/4-weekly windows and need the same "one representative value per
 * window" reduction, robust to the occasional outlier session/weigh-in a mean would not be.
 */
internal fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
}

/** Loads scale weigh-ins for trend graphs (weight, BMI, fat/lean %). */
@Singleton
class ScaleTrendLoader @Inject constructor(
    private val scaleHistoryRepository: ScaleHistoryRepository,
) {
    suspend fun load(days: Long = 90): ScaleTrendReport {
        val end = LocalDate.now()
        val start = end.minusDays(days)
        val weighIns = scaleHistoryRepository.getWeighInsInRange(start, end)
        return ScaleTrendReport(weighIns = weighIns)
    }
}
