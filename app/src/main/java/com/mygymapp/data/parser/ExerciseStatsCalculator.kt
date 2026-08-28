package com.mygymapp.data.parser

import com.mygymapp.data.model.ContextStats
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseStats
import com.mygymapp.data.model.PreviousSet
import com.mygymapp.data.model.SlotContext
import com.mygymapp.data.model.WorkoutSession

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

    private fun ExerciseSet.Strength.toPreviousSet() = PreviousSet(reps = reps, weight = weight)

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

        val sessionDate = session.completedAt.ifBlank { session.date }
        val touchedContexts = session.exercises
            .filter { it.exerciseId == exerciseId }
            .map { it.slotContext }
            .toSet()

        val updated = base.perContext.toMutableMap()
        for (ctx in touchedContexts) {
            val realSets = strengthSetsFor(exerciseId, ctx, session).filter { it.isReal() }
            val old = updated[ctx]

            val newBestThisSession = realSets.maxByOrNull { it.reps * it.weight }
            val mergedPr = listOfNotNull(
                old?.pr,
                newBestThisSession?.toPreviousSet(),
            ).maxByOrNull { it.reps * it.weight }

            val hasReal = realSets.isNotEmpty()
            val takeThisAsPrevious = hasReal &&
                (old == null || old.previousSessionDate.isEmpty() || sessionDate >= old.previousSessionDate)

            updated[ctx] = ContextStats(
                previousSets = if (takeThisAsPrevious) realSets.map { it.toPreviousSet() }
                    else old?.previousSets ?: emptyList(),
                previousSessionDate = if (takeThisAsPrevious) sessionDate
                    else old?.previousSessionDate ?: "",
                pr = mergedPr,
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
        var hasReal = false
        // "previous" = first session in newest-first order that has real data. Once set, the
        // rest of the loop only updates the PR.
        var prevDate = ""
        var prevSets: List<PreviousSet> = emptyList()
        var prevFound = false

        for (session in completedSessions) {
            val realSets = strengthSetsFor(exerciseId, ctx, session).filter { it.isReal() }
            if (realSets.isEmpty()) continue
            hasReal = true

            val bestThisSession = realSets.maxByOrNull { it.reps * it.weight }
            if (bestThisSession != null &&
                (pr == null || bestThisSession.reps * bestThisSession.weight > pr!!.reps * pr!!.weight)
            ) {
                pr = bestThisSession
            }

            if (!prevFound) {
                prevFound = true
                prevDate = session.completedAt.ifBlank { session.date }
                prevSets = realSets.map { it.toPreviousSet() }
            }
        }

        if (!hasReal) return null
        return ContextStats(
            previousSets = prevSets,
            previousSessionDate = prevDate,
            pr = pr?.toPreviousSet(),
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
