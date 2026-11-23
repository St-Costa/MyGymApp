package com.mygymapp.ui.screen.activeroutine

import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.measuredDurationSeconds
import com.mygymapp.data.model.measuredSessionDurationSeconds
import com.mygymapp.ui.util.groupSupersets
import kotlin.math.roundToInt

/**
 * Estimated wall-clock seconds for every section of the current session, shown next to the
 * WORKOUT / DAILY / WARMUP headers.
 *
 * The estimate is built from **this routine's previous completed session** — never from another
 * routine — so two routines that share an exercise (the fixed-daily mobility block, a common
 * warmup cardio) still get their own number. The previous session is the only history source;
 * the current session's slots are grouped exactly as they will be performed (superset members
 * share one window and are counted once, not once per member).
 *
 * Per-group rules:
 * - **Measured window available** (the previous session recorded `startedAt`/`completedAt`, or
 *   closed cardio blocks): use it. For a group that also has a fixed-time member, the configured
 *   hold/block total is a floor — a stretch or cardio estimate is never below the time it takes
 *   to execute the configured work, even if the measured window was cut short.
 * - **Performed strength slot without a window** (a legacy session saved before per-slot
 *   timestamps existed): start from [DEFAULT_STRENGTH_SET_SECONDS] per set. The session
 *   envelope below then turns that prior into the real per-set rate of this session.
 * - **Skipped strength slot** (`!completed` / `completedEmpty`): contributes 0, exactly as
 *   before — a slot the lifter skipped last time is not billed as performed work.
 * - **Fixed-time slot with no measurement**: use the configured duration (hold × sets for
 *   stretching, block length for cardio). That is the "if you do it, this is how long it takes"
 *   figure, not a record of a skip.
 *
 * Session envelope: the previous session's `startedAt→completedAt` span is the total wall-clock
 * time available. When it is longer than the sum above, the surplus is distributed **only across
 * performed strength sets**, proportionally to their count. This is what lets a legacy session
 * (no per-slot timestamps) still produce a realistic strength estimate from its total duration
 * and set count. Fixed-only groups are never inflated: a 10-minute cardio block stays 10 minutes
 * even when the whole session ran for an hour, because the extra time is not cardio work. The
 * envelope is capped at [MAX_RECONCILIATION_SCALE] × the base sum, so a session left open for
 * hours cannot turn into a nonsense estimate. A missing or shorter envelope leaves the measured /
 * configured / per-set values untouched.
 *
 * Pure so the header rendering stays trivial and this rule is unit-tested.
 */
internal fun estimateSectionSeconds(
    currentExercises: List<ActiveExerciseUi>,
    previousSession: WorkoutSession?,
): Map<SessionExerciseCategory, Int> {
    val reconciled = computeReconciledGroups(currentExercises, previousSession)

    return reconciled
        .filter { it.estimate.totalSeconds > 0 }
        .groupBy { it.estimate.category }
        .mapValues { (_, groups) -> groups.sumOf { it.estimate.totalSeconds } }
}

/**
 * Per-exercise wall-clock estimate (`exerciseId → seconds`), from the same source as the
 * section headers. Single exercises get their group's full total; superset members split
 * it so the shares add up to the group total: each member keeps its own configured
 * fixed time (stretch holds / cardio block) plus a proportional share of the group's
 * strength seconds (by performed strength set count). Entries with no usable time
 * (e.g. a strength slot with no history, or a slot skipped last time) are omitted —
 * the row then shows only the set count.
 *
 * Pure so the row rendering stays trivial and this rule is unit-tested.
 */
internal fun estimateExerciseSeconds(
    currentExercises: List<ActiveExerciseUi>,
    previousSession: WorkoutSession?,
): Map<String, Int> {
    val reconciled = computeReconciledGroups(currentExercises, previousSession)
    val previousById = previousSession?.exercises?.associateBy { it.exerciseId }.orEmpty()
    val hasHistory = previousSession != null
    val result = mutableMapOf<String, Int>()
    for ((members, estimate) in reconciled) {
        if (estimate.totalSeconds <= 0) continue
        if (members.size == 1) {
            result[members.first().exerciseId] = estimate.totalSeconds
            continue
        }
        val strengthSets = members.map { member ->
            if (!hasHistory || member.type != ExerciseType.FORZA) 0
            else {
                val previous = previousById[member.exerciseId]
                if (previous == null || !previous.isUntouched()) member.setCount else 0
            }
        }
        val totalStrengthSets = strengthSets.sum()
        var remainingStrength = estimate.strengthSeconds
        var remainingSets = totalStrengthSets
        members.forEachIndexed { i, member ->
            val configured = member.configuredDurationSeconds()
            val share = if (totalStrengthSets <= 0 || strengthSets[i] <= 0) {
                0
            } else if (remainingSets == strengthSets[i]) {
                remainingStrength
            } else {
                (estimate.strengthSeconds.toLong() * strengthSets[i] / totalStrengthSets).toInt()
            }
            if (strengthSets[i] > 0) {
                remainingStrength -= share
                remainingSets -= strengthSets[i]
            }
            val memberTotal = configured + share
            if (memberTotal > 0) result[member.exerciseId] = memberTotal
        }
    }
    return result
}

private data class ReconciledGroup(
    val members: List<ActiveExerciseUi>,
    val estimate: GroupEstimate,
)

private fun computeReconciledGroups(
    currentExercises: List<ActiveExerciseUi>,
    previousSession: WorkoutSession?,
): List<ReconciledGroup> {
    if (currentExercises.isEmpty()) return emptyList()
    val previousById = previousSession?.exercises?.associateBy { it.exerciseId }.orEmpty()

    // Group first so superset members — which were stamped with the same startedAt/completedAt
    // — contribute their shared window once. Section boundaries never split a chain (the
    // session builder clears the tail link), so grouping the whole ordered list is safe.
    val groupedMembers = groupSupersets(
        items = currentExercises,
        isPairedWithNext = { it.supersetWithNext },
        single = { index -> listOf(currentExercises[index]) },
        group = { indices -> indices.map { currentExercises[it] } },
    )

    val estimates = groupedMembers.map { group ->
        ReconciledGroup(
            members = group,
            estimate = estimateGroup(group, previousById, hasHistory = previousSession != null),
        )
    }

    val sessionSeconds = previousSession?.measuredSessionDurationSeconds()
    val baseTotal = estimates.sumOf { it.estimate.totalSeconds }
    val targetSeconds = sessionSeconds?.let {
        minOf(it.toDouble(), baseTotal * MAX_RECONCILIATION_SCALE)
    }
    if (targetSeconds == null || baseTotal <= 0 || targetSeconds <= baseTotal) return estimates
    val reconciled = distributeSurplus(
        estimates.map { it.estimate },
        (targetSeconds - baseTotal).roundToInt(),
    )
    return groupedMembers.zip(reconciled) { members, estimate ->
        ReconciledGroup(members, estimate)
    }
}

/**
 * One exercise group's estimate, split into the part that is fixed by configuration and the
 * part that represents strength work. Only the strength part may absorb the session surplus;
 * [fixedSeconds] is the stretch/cardio floor and never grows through reconciliation.
 */
private data class GroupEstimate(
    val category: SessionExerciseCategory,
    val fixedSeconds: Int,
    val strengthSeconds: Int,
    val strengthSets: Int,
) {
    val totalSeconds: Int get() = fixedSeconds + strengthSeconds
    val scalable: Boolean get() = strengthSets > 0
}

private fun estimateGroup(
    group: List<ActiveExerciseUi>,
    previousById: Map<String, WorkoutExercise>,
    hasHistory: Boolean,
): GroupEstimate {
    val configuredSeconds = group.sumOf { it.configuredDurationSeconds() }
    val measuredSeconds = group
        .mapNotNull { exercise -> previousById[exercise.exerciseId]?.measuredDurationSeconds() }
        .maxOrNull()

    // Sets that represent work the lifter will actually do: a slot missing from the previous
    // session (a new exercise in the routine) is counted, a slot that was present but skipped
    // is not. Skipped slots must keep contributing 0 (the long-standing rule).
    val performedStrengthSets = group
        .filter { it.type == ExerciseType.FORZA }
        .sumOf { exercise ->
            val previous = previousById[exercise.exerciseId]
            if (previous == null || !previous.isUntouched()) exercise.setCount else 0
        }

    val fixedSeconds = configuredSeconds
    val strengthSeconds = when {
        // A real window already includes the fixed work and its recovery/rest. The configured
        // total stays a floor; the remainder is strength time.
        measuredSeconds != null -> (measuredSeconds - configuredSeconds).coerceAtLeast(0)
        // Legacy slot: no per-exercise window, but the routine still contains the work. Start
        // from a per-set prior; the session envelope below calibrates it.
        hasHistory -> performedStrengthSets * DEFAULT_STRENGTH_SET_SECONDS
        else -> 0
    }

    return GroupEstimate(
        category = group.first().category,
        fixedSeconds = fixedSeconds,
        strengthSeconds = strengthSeconds,
        strengthSets = if (hasHistory) performedStrengthSets else 0,
    )
}

/**
 * Splits [surplusSeconds] across the groups that contain performed strength work, proportionally
 * to their set count. Fixed-only groups are left untouched, so a configured cardio block or a
 * stretch hold never inherits idle time from the rest of the session. Returns [estimates]
 * unchanged when there is no strength work to carry the surplus.
 */
private fun distributeSurplus(
    estimates: List<GroupEstimate>,
    surplusSeconds: Int,
): List<GroupEstimate> {
    val scalable = estimates.filter { it.scalable }
    val totalSets = scalable.sumOf { it.strengthSets }
    if (totalSets <= 0) return estimates

    var remainingSeconds = surplusSeconds
    var remainingSets = totalSets
    return estimates.map { estimate ->
        if (!estimate.scalable) return@map estimate
        // Largest-remainder-free integer split: the last scalable group absorbs the rounding
        // difference, so the section totals still add up to the session envelope.
        val share = if (remainingSets == estimate.strengthSets) {
            remainingSeconds
        } else {
            (surplusSeconds.toLong() * estimate.strengthSets / totalSets).toInt()
        }
        remainingSeconds -= share
        remainingSets -= estimate.strengthSets
        estimate.copy(strengthSeconds = estimate.strengthSeconds + share)
    }
}

/**
 * A real session can run longer than the sum of its slot windows (screens opened late, rests
 * between exercises), but not indefinitely: beyond 2× the difference is more plausibly idle
 * time than missed work, and inflating the header would be worse than undercounting it.
 */
private const val MAX_RECONCILIATION_SCALE = 2.0

/**
 * Starting point for a performed strength set whose slot has no measured window (a legacy
 * session, or a visit opened after the first set). It is only a prior: when the previous
 * session envelope is available, the surplus distribution makes the final per-set rate reflect
 * the session's real total duration. 3 minutes is a deliberately conservative middle ground
 * between a quick isolation set and a long compound set with full rest.
 */
private const val DEFAULT_STRENGTH_SET_SECONDS = 180

/**
 * Configured duration for one slot when no measured history exists. Only timed exercise types
 * have one: stretching = hold seconds × sets, cardio = configured block length. Strength has
 * no fixed duration in the routine model (reps/rest are lifter-dependent), so it stays 0 and
 * is estimated from its set count instead.
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
