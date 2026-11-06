package com.mygymapp.ui.screen.activeroutine

import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.measuredDurationSeconds
import com.mygymapp.data.model.measuredSessionDurationSeconds
import com.mygymapp.ui.util.groupSupersets
import kotlin.math.roundToInt

/**
 * Estimated wall-clock seconds for every section of the current session, shown next to the
 * WORKOUT / DAILY / WARMUP headers.
 *
 * The estimate is built the same way the session will actually be performed: one duration per
 * exercise group (superset members share one window, so they are counted once, not once per
 * member). A group that has a measured duration in the previous session of this routine uses
 * it — [com.mygymapp.data.model.WorkoutExercise.measuredDurationSeconds] is wall-clock, so the
 * node includes rest/recovery, and for stretching that is the difference between "3 × 90 s" and
 * the real time spent. A group with no usable measurement falls back to the configured hold /
 * block duration; strength groups have no configured duration, so they only get an estimate
 * once a timed session exists.
 *
 * Daily composition: [currentExercises] is already the routine-filtered list (a fixed-daily
 * exercise that also lives in the routine is dropped, never duplicated), so the DAILY section
 * estimate naturally reflects only the daily slots actually present under this routine — no
 * extra filtering is needed here.
 *
 * Skipped slots: [com.mygymapp.data.model.WorkoutExercise.measuredDurationSeconds] returns
 * null for untouched slots (`!completed || completedEmpty`), so a strength slot skipped in
 * the previous session contributes 0 (never a phantom window), while a skipped timed slot
 * (stretch/cardio) falls back to its configured duration — the "if you do it, this is how
 * long it takes" figure, not a record of the skip.
 *
 * The per-slot windows are then reconciled with the **whole previous session** envelope. A slot
 * opened after the first set (common when the lifter already knows the load) measures only the
 * tail of that exercise, so summing slots would understate the session. When the session's
 * startedAt→completedAt span is longer than that sum, the surplus is distributed proportionally
 * across the groups: the section estimates add up to the real session duration and each group
 * keeps its relative weight. The envelope is capped at [MAX_RECONCILIATION_SCALE] × the measured
 * sum: a session left open for hours is far more likely to contain idle time than missed work.
 * If the envelope is missing or shorter than the sum (overlapping windows, legacy data), the
 * measured/configured values are left untouched.
 *
 * Pure so the header rendering stays trivial and this rule is unit-tested.
 */
internal fun estimateSectionSeconds(
    currentExercises: List<ActiveExerciseUi>,
    previousSession: WorkoutSession?,
): Map<SessionExerciseCategory, Int> {
    if (currentExercises.isEmpty()) return emptyMap()
    val previousById = previousSession?.exercises?.associateBy { it.exerciseId }.orEmpty()

    // Group first so superset members — which were stamped with the same startedAt/completedAt
    // — contribute their shared window once. Section boundaries never split a chain (the
    // session builder clears the tail link), so grouping the whole ordered list is safe.
    val groups = groupSupersets(
        items = currentExercises,
        isPairedWithNext = { it.supersetWithNext },
        single = { index -> listOf(currentExercises[index]) },
        group = { indices -> indices.map { currentExercises[it] } },
    )

    val perGroup = groups.mapNotNull { group ->
        val measured = group.mapNotNull { exercise ->
            previousById[exercise.exerciseId]?.measuredDurationSeconds()
        }
        val seconds = if (measured.isNotEmpty()) {
            // Superset members carry the same window: max, never sum.
            measured.max()
        } else {
            group.sumOf { it.configuredDurationSeconds() }
        }
        seconds.takeIf { it > 0 }?.let { GroupEstimate(group.first().category, it) }
    }

    val sessionSeconds = previousSession?.measuredSessionDurationSeconds()
    val baseTotal = perGroup.sumOf { it.seconds }
    val targetSeconds = sessionSeconds?.let {
        minOf(it.toDouble(), baseTotal * MAX_RECONCILIATION_SCALE)
    }
    val reconciled = if (targetSeconds != null && baseTotal > 0 && targetSeconds > baseTotal) {
        val scale = targetSeconds / baseTotal
        perGroup.map { it.copy(seconds = (it.seconds * scale).roundToInt().coerceAtLeast(1)) }
    } else {
        perGroup
    }

    return reconciled
        .groupBy { it.category }
        .mapValues { (_, estimates) -> estimates.sumOf { it.seconds } }
}

/**
 * A real session can run longer than the sum of its slot windows (screens opened late, rests
 * between exercises), but not indefinitely: beyond 2× the difference is more plausibly idle
 * time than missed work, and inflating the header would be worse than undercounting it.
 */
private const val MAX_RECONCILIATION_SCALE = 2.0

private data class GroupEstimate(
    val category: SessionExerciseCategory,
    val seconds: Int,
)


/**
 * Configured duration for one slot when no measured history exists. Only timed exercise types
 * have one: stretching = hold seconds × sets, cardio = configured block length. Strength has
 * no fixed duration in the routine model (reps/rest are lifter-dependent), so it stays 0 and
 * simply contributes nothing until a measured session provides the real number.
 */
private fun ActiveExerciseUi.configuredDurationSeconds(): Int = when (type) {
    ExerciseType.STRETCH -> (timePerSetSeconds * setCount).coerceAtLeast(0)
    ExerciseType.CARDIO -> timePerSetSeconds.coerceAtLeast(0)
    ExerciseType.FORZA -> 0
}

/**
 * IDs that belong to a 2+ exercise superset chain. Used when a stretch row's badge decides
 * whether the shared screen window is a meaningful "time spent stretching": for a superset the
 * window covers the whole interleaved chain and would count the same minutes on every member,
 * so those rows keep showing their own done-hold seconds instead.
 */
internal fun exerciseIdsInSupersets(exercises: List<ActiveExerciseUi>): Set<String> =
    groupSupersets(
        items = exercises,
        isPairedWithNext = { it.supersetWithNext },
        single = { emptySet() },
        group = { indices -> indices.map { exercises[it].exerciseId }.toSet() },
    ).flatten().toSet()
