package com.mygymapp.data.parser

import com.mygymapp.data.model.DayCellStatus
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.GitgraphDay
import com.mygymapp.data.model.GitgraphHistory
import com.mygymapp.data.model.WorkoutSession
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pure derivation of the home gitgraph's day squares from session history. No I/O — the
 * repository supplies parsed [WorkoutSession]s and persists the result. This is the *single*
 * implementation of the per-day status / tonnage-% / stretching-minutes / cardio-minutes rule;
 * `MainViewModel`
 * calls the same [dayCell] for the current-week row so the history rows and the live row can
 * never diverge.
 *
 * Must match what `MainViewModel.computeDayCell` / `computeCommonTonnage` / `cardioMinutesFor`
 * did inline before this cache existed — see [GitgraphHistory.SCHEMA_VERSION] for the cache schema.
 */
object GitgraphHistoryCalculator {

    /** Result for one day square — same shape `MainViewModel.DayCell` had. */
    data class DayCell(
        val status: DayCellStatus,
        val tonnageChangePct: Double?,
        val stretchMinutes: Int?,
        val cardioMinutes: Int?,
    )

    /**
     * Builds the 28-day window starting at [windowStartMonday].
     *
     * @param sessionsInWindow completed sessions whose `date` falls in the 28-day window.
     * @param priorSessionsByRoutine, for each routine, its completed sessions *before* the
     *   window (newest-relevant first is not required — the pick is `maxByOrNull(completedAt)`),
     *   used so the oldest visible days still have a "previous" to compare against. Sessions
     *   inside the window are also consulted for the comparison automatically.
     */
    fun buildWindow(
        windowStartMonday: LocalDate,
        sessionsInWindow: List<WorkoutSession>,
        priorSessionsByRoutine: Map<String, List<WorkoutSession>>,
    ): GitgraphHistory {
        val completedInWindow = sessionsInWindow.filter { it.completedAt.isNotBlank() }
        // routineId -> all its completed sessions we know about (window + prior), for the
        // "previous session of the same routine" lookup inside dayCell.
        val byRoutine: Map<String, List<WorkoutSession>> =
            (completedInWindow + priorSessionsByRoutine.values.flatten())
                .groupBy { it.routineId }

        val days = (0 until GitgraphHistory.DAY_COUNT).map { offset ->
            val date = windowStartMonday.plusDays(offset.toLong())
            val dateStr = date.toString()
            val daySessions = completedInWindow.filter { it.date == dateStr }
            val lastSession = daySessions.maxByOrNull { it.completedAt }

            if (lastSession == null) {
                GitgraphDay(date = dateStr, status = DayCellStatus.NONE)
            } else {
                val cell = dayCell(lastSession, byRoutine)
                GitgraphDay(
                    date = dateStr,
                    status = cell.status,
                    tonnageChangePct = cell.tonnageChangePct,
                    stretchMinutes = cell.stretchMinutes,
                    cardioMinutes = cell.cardioMinutes,
                    routineName = lastSession.routineName,
                    sessionId = lastSession.id,
                )
            }
        }
        return GitgraphHistory(windowStartMonday = windowStartMonday.toString(), days = days)
    }

    /**
     * Status / tonnage-% / cardio-minutes for one day's [session], compared against that
     * routine's most recent *earlier* session found in [sessionsByRoutine].
     */
    fun dayCell(
        session: WorkoutSession,
        sessionsByRoutine: Map<String, List<WorkoutSession>>,
    ): DayCell {
        val previous = sessionsByRoutine[session.routineId]
            ?.filter { it.date < session.date }
            ?.maxByOrNull { it.completedAt }

        val (currTonnage, prevTonnage) = if (previous != null)
            computeCommonTonnage(session, previous)
        else session.totalTonnage to 0.0

        val status = if (previous != null) {
            if (currTonnage >= prevTonnage) DayCellStatus.IMPROVED else DayCellStatus.REGRESSED
        } else {
            DayCellStatus.IMPROVED // first session of this routine
        }
        val tonnageChangePct = if (previous != null && prevTonnage > 0)
            (currTonnage - prevTonnage) / prevTonnage * 100.0
        else null
        // Display priority: strength tonnage %, then stretching duration, then cardio duration.
        val stretchMinutes = if (tonnageChangePct == null) stretchMinutesFor(session) else null
        val cardioMinutes = if (tonnageChangePct == null && stretchMinutes == null) cardioMinutesFor(session) else null
        return DayCell(status, tonnageChangePct, stretchMinutes, cardioMinutes)
    }

    /**
     * Tonnage of each session over only the exercises present in **both**, excluding
     * warmup/fixed-daily, untouched, and completed-empty slots. Falls back to `totalTonnage`
     * when the two share no comparable exercise.
     */
    private fun computeCommonTonnage(s1: WorkoutSession, s2: WorkoutSession): Pair<Double, Double> {
        fun comparableIds(s: WorkoutSession) = s.exercises
            .filterNot { it.excludeFromTonnage || it.isUntouched() }
            .map { it.exerciseId }
            .toSet()

        val commonIds = comparableIds(s1).intersect(comparableIds(s2))
        if (commonIds.isEmpty()) return s1.totalTonnage to s2.totalTonnage

        fun tonnageFor(s: WorkoutSession): Double = s.exercises
            .filter { it.exerciseId in commonIds && !it.excludeFromTonnage }
            .sumOf { ex -> ex.sets.filterIsInstance<ExerciseSet.Strength>().sumOf { it.reps * it.weight } }
        return tonnageFor(s1) to tonnageFor(s2)
    }

    /** Minutes across every closed `ExerciseSet.Cardio` block; null if none. */
    private fun cardioMinutesFor(session: WorkoutSession): Int? {
        val totalSeconds = session.exercises
            .flatMap { it.sets }
            .filterIsInstance<ExerciseSet.Cardio>()
            .filter { it.startedAt.isNotBlank() && it.endedAt.isNotBlank() }
            .sumOf { block ->
                val start = runCatching { LocalDateTime.parse(block.startedAt) }.getOrNull()
                val end = runCatching { LocalDateTime.parse(block.endedAt) }.getOrNull()
                if (start != null && end != null)
                    Duration.between(start, end).seconds.coerceAtLeast(0)
                else 0
            }
        return if (totalSeconds > 0) (totalSeconds / 60).toInt() else null
    }

    /** Minutes across completed STRETCH sets; null if none. */
    private fun stretchMinutesFor(session: WorkoutSession): Int? {
        val totalSeconds = session.exercises
            .filter { it.type == ExerciseType.STRETCH }
            .flatMap { it.sets }
            .filterIsInstance<ExerciseSet.Stretch>()
            .filter { it.done }
            .sumOf { it.timeSeconds.coerceAtLeast(0) }
        return if (totalSeconds > 0) (totalSeconds / 60).toInt() else null
    }
}
