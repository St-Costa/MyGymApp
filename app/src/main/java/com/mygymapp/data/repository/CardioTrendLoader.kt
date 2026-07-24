package com.mygymapp.data.repository

import com.mygymapp.data.model.WorkoutSession
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

enum class TrendDirection { UP, DOWN, FLAT }
enum class TrendSemaphore { GREEN, YELLOW, RED, GRAY }

/**
 * A single metric within the cardio trend card.
 * @param series one point per completed session (oldest→newest) for sparkline
 * @param current mean of the most recent 14 days
 * @param delta current - baseline (14-28 days ago)
 * @param direction UP if delta>0, DOWN if <0, FLAT if near zero
 * @param semaphore green/yellow/red based on direction+value semantics for this metric
 * @param unitLabel e.g. "BPM", "ms", "ml/kg/min"
 */
data class CardioMetric(
    val key: String,
    val label: String,
    val series: List<Double>,
    val current: Double,
    val delta: Double,
    val direction: TrendDirection,
    val semaphore: TrendSemaphore,
    val unitLabel: String,
    val formatCurrent: String = "%.0f",
    val formatDelta: String = "%+.1f",
)

data class CardioRhythmCounters(
    val afibEpisodes: Int = 0,
    val pauses: Int = 0,
    val premature: Int = 0,
    val uneven: Int = 0,
    val afibSemaphore: TrendSemaphore = TrendSemaphore.GREEN,
    val pausesSemaphore: TrendSemaphore = TrendSemaphore.GREEN,
    val prematureSemaphore: TrendSemaphore = TrendSemaphore.GREEN,
    val unevenSemaphore: TrendSemaphore = TrendSemaphore.GREEN,
)

data class CardioTrendReport(
    val sessionCount: Int = 0,
    val avgDurationMinutes: Int = 0,
    val metrics: List<CardioMetric> = emptyList(),
    val rhythm: CardioRhythmCounters = CardioRhythmCounters(),
    val alerts: List<String> = emptyList(),
    val hasEnoughData: Boolean = false,
)

@Singleton
class CardioTrendLoader @Inject constructor(
    private val workoutRepository: WorkoutRepository,
) {

    suspend fun load(reference: LocalDate = LocalDate.now()): CardioTrendReport {
        val start = reference.minusDays(28)
        val sessions = workoutRepository.getSessionsInRange(start, reference)
            .filter { it.completedAt.isNotBlank() }
            .sortedBy { it.date }

        if (sessions.isEmpty()) return CardioTrendReport(hasEnoughData = false)

        // split: recent = last 14 days, baseline = 14..28 days ago
        val cutoff = reference.minusDays(14)
        val (recent, baseline) = sessions.partition { LocalDate.parse(it.date) > cutoff }

        val hasEnough = recent.size >= 2  // need at least 2 recent sessions to show anything

        // Pre-classify by recency once so metric() can extract in a single pass
        // over sessions instead of one pass each per (all/recent/baseline).
        val sessionsWithRecency = sessions.map { it to (LocalDate.parse(it.date) > cutoff) }

        val metrics = buildList {
            add(metric("restingHr", "Resting HR", "BPM", sessionsWithRecency,
                { it.restingHr.toDouble().takeIf { v -> v > 0 } },
                goodDirection = TrendDirection.DOWN,
                absoluteBad = { it > 85.0 },
                absoluteOk = { it in 40.0..75.0 }))

            add(metric("hrr60s", "HRR (1 min)", "BPM", sessionsWithRecency,
                { it.hrr60s.takeIf { v -> v > 0 } },
                goodDirection = TrendDirection.UP,
                absoluteBad = { it < 12.0 },
                absoluteOk = { it >= 20.0 }))

            add(metric("vo2max", "VO2max", "ml/kg/min", sessionsWithRecency,
                { it.vo2max.takeIf { v -> v > 0 } },
                goodDirection = TrendDirection.UP,
                absoluteBad = { false },
                absoluteOk = { true },
                formatCurrent = "%.1f"))

            add(metric("rmssd", "RMSSD", "ms", sessionsWithRecency,
                { it.ecgSessionRmssd.takeIf { v -> v > 0 } },
                goodDirection = TrendDirection.UP,
                absoluteBad = { false },
                absoluteOk = { true }))

            add(metric("sdnn", "SDNN", "ms", sessionsWithRecency,
                { it.sdnn.takeIf { v -> v > 0 } },
                goodDirection = TrendDirection.UP,
                absoluteBad = { false },
                absoluteOk = { true }))

            add(metric("drift", "Cardiac drift", "BPM/min", sessionsWithRecency,
                { it.cardiacDriftBpmMin.takeIf { v -> v != 0.0 } },
                goodDirection = TrendDirection.DOWN,
                absoluteBad = { it > 1.0 },
                absoluteOk = { it < 0.5 },
                formatCurrent = "%+.2f",
                formatDelta = "%+.2f"))

            // Poincare ratio as a standalone (no series, single value) — we still
            // pass a series if you want to visualize; here we include it in metrics
            // list with a flat series using the recent mean.
            add(metric("poincareRatio", "SD2/SD1 ratio", "", sessionsWithRecency,
                { it.poincareRatio.takeIf { v -> v > 0 } },
                goodDirection = TrendDirection.FLAT,
                absoluteBad = { it < 1.0 || it > 6.0 },
                absoluteOk = { it in 1.5..4.5 },
                formatCurrent = "%.2f",
                formatDelta = "%+.2f"))
        }.filter { it.series.isNotEmpty() }

        // Rhythm counters (last 4 weeks cumulative)
        val afib = sessions.sumOf { it.afibSuspicionEpisodes }
        val pauses = sessions.sumOf { it.ecgPauseCount }
        val premature = sessions.sumOf { it.ecgPacCount }
        val uneven = sessions.sumOf { it.ecgIrregularBeats }

        val rhythm = CardioRhythmCounters(
            afibEpisodes = afib,
            pauses = pauses,
            premature = premature,
            uneven = uneven,
            afibSemaphore = if (afib == 0) TrendSemaphore.GREEN else if (afib <= 2) TrendSemaphore.YELLOW else TrendSemaphore.RED,
            pausesSemaphore = if (pauses == 0) TrendSemaphore.GREEN else if (pauses <= 2) TrendSemaphore.YELLOW else TrendSemaphore.RED,
            prematureSemaphore = if (premature <= 20) TrendSemaphore.GREEN else if (premature <= 60) TrendSemaphore.YELLOW else TrendSemaphore.RED,
            unevenSemaphore = if (uneven <= 30) TrendSemaphore.GREEN else if (uneven <= 100) TrendSemaphore.YELLOW else TrendSemaphore.RED,
        )

        val alerts = buildAlerts(sessions, recent, baseline, rhythm)

        val totalDurationMin = sessions.sumOf { it.ecgDurationSec.toInt() } / 60
        val avgDuration = if (sessions.isNotEmpty()) totalDurationMin / sessions.size else 0

        return CardioTrendReport(
            sessionCount = sessions.size,
            avgDurationMinutes = avgDuration,
            metrics = metrics,
            rhythm = rhythm,
            alerts = alerts,
            hasEnoughData = hasEnough,
        )
    }

    private fun metric(
        key: String,
        label: String,
        unitLabel: String,
        sessionsWithRecency: List<Pair<WorkoutSession, Boolean>>,
        extractor: (WorkoutSession) -> Double?,
        goodDirection: TrendDirection,
        absoluteBad: (Double) -> Boolean,
        absoluteOk: (Double) -> Boolean,
        formatCurrent: String = "%.0f",
        formatDelta: String = "%+.1f",
    ): CardioMetric {
        // Single pass: extract once per session, classify into series (all
        // non-null values) + recent + baseline based on the pre-computed flag.
        val series = ArrayList<Double>(sessionsWithRecency.size)
        val recentValues = ArrayList<Double>()
        val baselineValues = ArrayList<Double>()
        for ((session, isRecent) in sessionsWithRecency) {
            val v = extractor(session) ?: continue
            series.add(v)
            if (isRecent) recentValues.add(v) else baselineValues.add(v)
        }
        val current = if (recentValues.isNotEmpty()) recentValues.average() else 0.0
        val baselineAvg = if (baselineValues.isNotEmpty()) baselineValues.average() else current
        val delta = current - baselineAvg

        val direction = when {
            current == 0.0 -> TrendDirection.FLAT
            kotlin.math.abs(delta) < kotlin.math.abs(baselineAvg) * 0.02 -> TrendDirection.FLAT
            delta > 0 -> TrendDirection.UP
            else -> TrendDirection.DOWN
        }

        // Semaphore: combine direction judgment with absolute thresholds
        val semaphore = when {
            current == 0.0 -> TrendSemaphore.GRAY
            absoluteBad(current) -> TrendSemaphore.RED
            goodDirection != TrendDirection.FLAT && direction != TrendDirection.FLAT &&
                    direction != goodDirection &&
                    kotlin.math.abs(delta) > kotlin.math.abs(baselineAvg) * 0.10 ->
                TrendSemaphore.RED
            goodDirection != TrendDirection.FLAT && direction != TrendDirection.FLAT &&
                    direction != goodDirection -> TrendSemaphore.YELLOW
            absoluteOk(current) -> TrendSemaphore.GREEN
            else -> TrendSemaphore.YELLOW
        }

        return CardioMetric(
            key = key,
            label = label,
            series = series,
            current = current,
            delta = delta,
            direction = direction,
            semaphore = semaphore,
            unitLabel = unitLabel,
            formatCurrent = formatCurrent,
            formatDelta = formatDelta,
        )
    }

    private fun buildAlerts(
        all: List<WorkoutSession>,
        recent: List<WorkoutSession>,
        baseline: List<WorkoutSession>,
        rhythm: CardioRhythmCounters,
    ): List<String> {
        val alerts = mutableListOf<String>()

        if (rhythm.afibEpisodes > 0) {
            alerts += "${rhythm.afibEpisodes} AFib-suspicious episode(s) in last 4 weeks — consult a physician if recurring."
        }
        if (rhythm.pauses > 2) {
            alerts += "${rhythm.pauses} pauses > 2s in last 4 weeks — uncommon, mention to doctor."
        }

        // Resting HR trending up ≥ 5 BPM for 2+ weeks
        val restingRecent = recent.mapNotNull { it.restingHr.takeIf { v -> v > 0 } }
        val restingBaseline = baseline.mapNotNull { it.restingHr.takeIf { v -> v > 0 } }
        if (restingRecent.size >= 3 && restingBaseline.size >= 3) {
            val dRhr = restingRecent.average() - restingBaseline.average()
            if (dRhr >= 5) alerts += "Resting HR up ${"%+.0f".format(dRhr)} BPM vs baseline — possible fatigue or illness."
        }

        // HRR chronically < 12
        val hrrRecent = recent.mapNotNull { it.hrr60s.takeIf { v -> v > 0 } }
        if (hrrRecent.size >= 3 && hrrRecent.average() < 12) {
            alerts += "HRR chronically below 12 BPM — poor recovery trend."
        }

        if (all.size < 4) {
            alerts += "Only ${all.size} session(s) in last 4 weeks — trends not yet reliable."
        }

        return alerts
    }
}
