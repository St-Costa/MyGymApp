package com.mygymapp.data.parser

import com.mygymapp.data.model.ContextStats
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseStats
import com.mygymapp.data.model.PreviousSet
import com.mygymapp.data.model.SlotContext
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.estimate1RM

/**
 * Pure derivation of [ExerciseStats] from session history. No I/O — the repository hands it
 * parsed [WorkoutSession]s and persists the result. Kept separate so the rules ("what is
 * previous", "what is the PR") are unit-testable in isolation and impossible to diverge
 * between the incremental (one new session) and full-rebuild paths, which both go through
 * here.
 *
 * The rules must match what StrengthExerciseViewModel / SupersetViewModel did inline before
 * the sidecar existed — see [ExerciseStats.SCHEMA_VERSION] doc for v1.
 */
object ExerciseStatsCalculator {

    /** A set counts toward tonnage/PR/previous only if it carries real load. */
    private fun ExerciseSet.Strength.isReal(): Boolean =
        reps > 0 && weight > 0.0

    private fun ExerciseSet.Strength.toPreviousSet() =
        PreviousSet(
            reps = reps,
            weight = weight,
            bwBaseWeightKg = bwBaseWeightKg,
            isBodyweight = isBodyweight,
        )

    /**
     * The weighting approach a session used for this exercise+context: `true` if any of its
     * real sets was bodyweight. A session that mixes both (rare) counts as bodyweight — the
     * active-routine badge only reads this to pick a like-with-like previous, and a mixed
     * session is closer to the bodyweight case than the manual-load one.
     */
    private fun List<ExerciseSet.Strength>.isBodyweightSession(): Boolean =
        any { it.isBodyweight }

    private fun ExerciseSet.Strength.tonnage(): Double = reps * weight
    private fun PreviousSet.tonnage(): Double = reps * weight

    private fun ExerciseSet.Strength.e1rm(): Double = estimate1RM(weight, reps)
    private fun PreviousSet.e1rm(): Double = estimate1RM(weight, reps)

    /**
     * The day a session belongs to, as a bare `YYYY-MM-DD` string. `completedAt` is an ISO
     * datetime (`2026-08-25T19:04:11`), `date` is already a bare date — take the first 10 chars
     * of whichever is present so `previousSessionDate` comparisons are always day-vs-day and
     * never mix a datetime with a date (a lexicographic `>=` between the two formats is only
     * accidentally correct). Both [rebuild] and [merge] store and compare via this.
     */
    private fun WorkoutSession.dayKey(): String =
        completedAt.ifBlank { date }.take(10)

    /**
     * Full rebuild: fold every session that contains [exerciseId] into a fresh [ExerciseStats].
     * [sessions] may be in any order and may include non-completed ones (they're skipped).
     * This is the authoritative computation; [merge] is an optimization that must agree with it.
     */
    fun rebuild(exerciseId: String, sessions: List<WorkoutSession>): ExerciseStats {
        // Newest first, by full completedAt timestamp — matches the old
        // `getSessionsForExercise(...).sortedByDescending { it.completedAt }` ordering, so
        // "previous" resolves to exactly the same session the inline code used to pick.
        val completed = sessions
            .filter { it.completedAt.isNotBlank() }
            .sortedByDescending { it.completedAt }
        val perContext = SlotContext.entries.mapNotNull { ctx ->
            val stats = contextStatsFrom(exerciseId, ctx, completed)
            stats?.let { ctx to it }
        }.toMap()
        return ExerciseStats(exerciseId = exerciseId, perContext = perContext)
    }

    /**
     * Incremental update: fold a single freshly-saved [session] into [current] (which may be
     * null / a different schema version — then this degrades to "stats from just this
     * session", still correct as a lower bound, and a full rebuild will fill the rest in on
     * the next delete or schema bump). Only [SlotContext]s present in [session] are touched.
     *
     * Semantics, per context the session touches:
     *  - `pr`: max of the old PR and this session's best real set.
     *  - `previousSets` / `previousSessionDate`: replaced with this session's sets **iff** this
     *    session has real data for the exercise in that context AND its date is >= the stored
     *    one (a session is normally saved in chronological order; the `>=` guards re-saves of
     *    the same day).
     *  - `hasPriorRealTonnage`: OR-ed with "this session had a real set".
     */
    fun merge(exerciseId: String, current: ExerciseStats?, session: WorkoutSession): ExerciseStats {
        if (session.completedAt.isBlank()) return current ?: ExerciseStats(exerciseId)

        val base = current
            ?.takeIf { it.schemaVersion == ExerciseStats.SCHEMA_VERSION && it.exerciseId == exerciseId }
            ?: ExerciseStats(exerciseId)

        val sessionDate = session.dayKey()
        val touchedContexts = session.exercises
            .filter { it.exerciseId == exerciseId }
            .map { it.slotContext }
            .toSet()

        val updated = base.perContext.toMutableMap()
        for (ctx in touchedContexts) {
            val realSets = strengthSetsFor(exerciseId, ctx, session).filter { it.isReal() }
            val old = updated[ctx]

            val newBestThisSession = realSets.maxByOrNull { it.tonnage() }
            val mergedPr = listOfNotNull(
                old?.pr,
                newBestThisSession?.toPreviousSet(),
            ).maxByOrNull { it.tonnage() }

            val newBestE1rmThisSession = realSets.maxByOrNull { it.e1rm() }
            val mergedRmPr = listOfNotNull(
                old?.rmPr,
                newBestE1rmThisSession?.toPreviousSet(),
            ).maxByOrNull { it.e1rm() }

            val hasReal = realSets.isNotEmpty()
            // This session's sets go into exactly one of the two previous slots (bodyweight or
            // manual load) — see ExerciseStats.SCHEMA_VERSION v4. `>=` (not `>`): a session
            // re-saved on the same day it was first saved must still replace its slot's
            // "previous". Compared day-vs-day via dayKey().
            val isBwSession = realSets.isBodyweightSession()
            val relevantOldDate =
                if (isBwSession) old?.previousSessionDateBodyweight else old?.previousSessionDate
            val takeThisAsPrevious = hasReal &&
                (old == null || relevantOldDate.isNullOrBlank() ||
                    sessionDate.take(10) >= relevantOldDate.take(10))

            updated[ctx] = ContextStats(
                previousSets = when {
                    takeThisAsPrevious && !isBwSession -> realSets.map { it.toPreviousSet() }
                    else -> old?.previousSets ?: emptyList()
                },
                previousSessionDate = when {
                    takeThisAsPrevious && !isBwSession -> sessionDate
                    else -> old?.previousSessionDate ?: ""
                },
                previousSetsBodyweight = when {
                    takeThisAsPrevious && isBwSession -> realSets.map { it.toPreviousSet() }
                    else -> old?.previousSetsBodyweight ?: emptyList()
                },
                previousSessionDateBodyweight = when {
                    takeThisAsPrevious && isBwSession -> sessionDate
                    else -> old?.previousSessionDateBodyweight ?: ""
                },
                pr = mergedPr,
                rmPr = mergedRmPr,
                hasPriorRealTonnage = (old?.hasPriorRealTonnage ?: false) || hasReal,
            )
        }
        return base.copy(perContext = updated)
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** @param completedSessions newest first (by completedAt). */
    private fun contextStatsFrom(
        exerciseId: String,
        ctx: SlotContext,
        completedSessions: List<WorkoutSession>,
    ): ContextStats? {
        var pr: ExerciseSet.Strength? = null
        var prTonnage = 0.0
        var rmPr: ExerciseSet.Strength? = null
        var rmPrE1rm = 0.0
        var hasReal = false
        // "previous" = first session in newest-first order that has real data, tracked
        // separately per weighting approach (bodyweight vs manual load) so a manual↔bodyweight
        // switch never compares across approaches — see ExerciseStats.SCHEMA_VERSION v4. Once a
        // slot is set, the rest of the loop only updates the PR.
        var prevDate = ""
        var prevSets: List<PreviousSet> = emptyList()
        var prevFound = false
        var prevDateBw = ""
        var prevSetsBw: List<PreviousSet> = emptyList()
        var prevFoundBw = false

        for (session in completedSessions) {
            val realSets = strengthSetsFor(exerciseId, ctx, session).filter { it.isReal() }
            if (realSets.isEmpty()) continue
            hasReal = true

            val bestThisSession = realSets.maxByOrNull { it.tonnage() }
            if (bestThisSession != null && bestThisSession.tonnage() > prTonnage) {
                pr = bestThisSession
                prTonnage = bestThisSession.tonnage()
            }

            val bestE1rmThisSession = realSets.maxByOrNull { it.e1rm() }
            if (bestE1rmThisSession != null && bestE1rmThisSession.e1rm() > rmPrE1rm) {
                rmPr = bestE1rmThisSession
                rmPrE1rm = bestE1rmThisSession.e1rm()
            }

            if (realSets.isBodyweightSession()) {
                if (!prevFoundBw) {
                    prevFoundBw = true
                    prevDateBw = session.dayKey()
                    prevSetsBw = realSets.map { it.toPreviousSet() }
                }
            } else {
                if (!prevFound) {
                    prevFound = true
                    prevDate = session.dayKey()
                    prevSets = realSets.map { it.toPreviousSet() }
                }
            }
        }

        if (!hasReal) return null
        return ContextStats(
            previousSets = prevSets,
            previousSessionDate = prevDate,
            previousSetsBodyweight = prevSetsBw,
            previousSessionDateBodyweight = prevDateBw,
            pr = pr?.toPreviousSet(),
            rmPr = rmPr?.toPreviousSet(),
            hasPriorRealTonnage = hasReal,
        )
    }

    /** Strength sets for [exerciseId] performed in slot context [ctx] within [session]. */
    private fun strengthSetsFor(
        exerciseId: String,
        ctx: SlotContext,
        session: WorkoutSession,
    ): List<ExerciseSet.Strength> =
        session.exercises
            .filter { it.exerciseId == exerciseId && it.slotContext == ctx }
            .flatMap { it.sets.filterIsInstance<ExerciseSet.Strength>() }
}
