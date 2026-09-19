package com.mygymapp.data.parser

import com.mygymapp.data.model.ContextStats
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseStats
import com.mygymapp.data.model.LoadMode
import com.mygymapp.data.model.PreviousSession
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
            isAssisted = isAssisted,
            assistOffsetKg = assistOffsetKg,
        )

    /**
     * The weighting approach a session used for this exercise+context, one of MANUAL /
     * BODYWEIGHT / ASSISTED (schema v5). A session mixing more than one (rare) is classified by
     * priority bodyweight > assisted > manual — the active-routine badge only reads this to pick
     * a like-with-like previous, and either non-manual case is closer to the other than to plain
     * manual load.
     */
    private fun List<ExerciseSet.Strength>.sessionLoadMode(): LoadMode = when {
        any { it.isBodyweight } -> LoadMode.BODYWEIGHT
        any { it.isAssisted } -> LoadMode.ASSISTED
        else -> LoadMode.MANUAL
    }

    private fun ExerciseSet.Strength.tonnage(): Double = reps * weight
    private fun PreviousSet.tonnage(): Double = reps * weight

    private fun ExerciseSet.Strength.e1rm(): Double = estimate1RM(weight, reps)
    private fun PreviousSet.e1rm(): Double = estimate1RM(weight, reps)

    /**
     * The day a session belongs to, as a bare `YYYY-MM-DD` string. `completedAt` is an ISO
     * datetime (`2026-08-25T19:04:11`), `date` is already a bare date — take the first 10 chars
     * of whichever is present so `previousSessionDate` comparisons are always day-vs-day and
     * never mix a datetime with a date (a lexicographic `>=` between the two formats is only
     * accidentally correct). History heads store this; ordering uses [WorkoutSession.orderKey].
     */
    private fun WorkoutSession.dayKey(): String =
        completedAt.ifBlank { date }.take(10)

    /** Full timestamp used for newest-first ordering (two sessions same day order by time). */
    private fun WorkoutSession.orderKey(): String =
        completedAt.ifBlank { date }

    /**
     * Real performed strength sets for [exerciseId] in slot context [ctx] within [session],
     * or empty when the slot was never performed (not completed, or completed-empty/skipped —
     * schema v6: a skip persists the on-screen pre-fill for walk-back continuity but must not
     * advance "last performed"). PR / previous / history all build on this.
     */
    private fun performedSetsFor(
        exerciseId: String,
        ctx: SlotContext,
        session: WorkoutSession,
    ): List<ExerciseSet.Strength> {
        val slot = session.exercises.firstOrNull { it.exerciseId == exerciseId && it.slotContext == ctx }
            ?: return emptyList()
        if (slot.isUntouched()) return emptyList()
        return slot.sets.filterIsInstance<ExerciseSet.Strength>().filter { it.isReal() }
    }

    private fun WorkoutSession.historyEntry(
        sets: List<ExerciseSet.Strength>,
    ): PreviousSession {
        val prevSets = sets.map { it.toPreviousSet() }
        return PreviousSession(
            date = dayKey(),
            sessionId = id,
            completedAt = orderKey(),
            sets = prevSets,
            tonnage = prevSets.sumOf { it.tonnage() },
        )
    }

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
            .sortedByDescending { it.orderKey() }
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
     *  - `pr` / `rmPr`: max of the old record and this session's best real set (with dates).
     *  - `previousHistory*`: this session's entry is prepended (or replaces the head on a
     *    same-session re-save) **iff** it has real performed data AND its full `completedAt`
     *    is >= the head's (a back-dated edit still counts for PR, never for history order).
     *    Trimmed to [ExerciseStats.HISTORY_LIMIT]. `previousSets` / `previousSessionDate` /
     *    `previousSessionId` mirror the new head.
     *  - `hasPriorRealTonnage`: OR-ed with "this session had a real performed set".
     *  - Skipped slots (`isUntouched()`) contribute nothing — not even a history entry.
     */
    fun merge(exerciseId: String, current: ExerciseStats?, session: WorkoutSession): ExerciseStats {
        if (session.completedAt.isBlank()) return current ?: ExerciseStats(exerciseId)

        val base = current
            ?.takeIf { it.schemaVersion == ExerciseStats.SCHEMA_VERSION && it.exerciseId == exerciseId }
            ?: ExerciseStats(exerciseId)

        val touchedContexts = session.exercises
            .filter { it.exerciseId == exerciseId }
            .map { it.slotContext }
            .toSet()

        val updated = base.perContext.toMutableMap()
        for (ctx in touchedContexts) {
            val realSets = performedSetsFor(exerciseId, ctx, session)
            val old = updated[ctx]

            val newBestThisSession = realSets.maxByOrNull { it.tonnage() }
            val prCandidates = listOfNotNull(
                old?.pr?.let { it to old.prSessionDate },
                newBestThisSession?.toPreviousSet()?.let { it to session.dayKey() },
            )
            val mergedPr = prCandidates.maxByOrNull { it.first.tonnage() }
            val newBestE1rmThisSession = realSets.maxByOrNull { it.e1rm() }
            val rmCandidates = listOfNotNull(
                old?.rmPr?.let { it to old.rmPrSessionDate },
                newBestE1rmThisSession?.toPreviousSet()?.let { it to session.dayKey() },
            )
            val mergedRmPr = rmCandidates.maxByOrNull { it.first.e1rm() }

            val hasReal = realSets.isNotEmpty()
            // This session's entry goes into exactly one of the three history lists
            // (bodyweight / assisted / manual load) — see ExerciseStats.SCHEMA_VERSION v5.
            // Ordered by full completedAt (not day-only): two sessions on the same day keep
            // time order. A same-session re-save replaces the head instead of duplicating it.
            val sessionMode: LoadMode? = if (hasReal) realSets.sessionLoadMode() else null
            val entry = if (hasReal) session.historyEntry(realSets) else null
            fun advance(oldHistory: List<PreviousSession>, forMode: LoadMode): List<PreviousSession> {
                if (entry == null || sessionMode != forMode) return oldHistory
                if (oldHistory.isEmpty()) return listOf(entry)
                val head = oldHistory.first()
                if (entry.sessionId.isNotBlank() && entry.sessionId == head.sessionId) {
                    return listOf(entry) + oldHistory.drop(1)
                }
                if (entry.completedAt >= head.completedAt) {
                    return (listOf(entry) + oldHistory).take(ExerciseStats.HISTORY_LIMIT)
                }
                return oldHistory
            }
            val newManual = advance(old?.previousHistory.orEmpty(), LoadMode.MANUAL)
            val newBw = advance(old?.previousHistoryBodyweight.orEmpty(), LoadMode.BODYWEIGHT)
            val newAssisted = advance(old?.previousHistoryAssisted.orEmpty(), LoadMode.ASSISTED)
            val headManual = newManual.firstOrNull()
            val headBw = newBw.firstOrNull()
            val headAssisted = newAssisted.firstOrNull()

            updated[ctx] = ContextStats(
                previousSets = headManual?.sets.orEmpty(),
                previousSessionDate = headManual?.date.orEmpty(),
                previousSetsBodyweight = headBw?.sets.orEmpty(),
                previousSessionDateBodyweight = headBw?.date.orEmpty(),
                previousSetsAssisted = headAssisted?.sets.orEmpty(),
                previousSessionDateAssisted = headAssisted?.date.orEmpty(),
                pr = mergedPr?.first,
                prSessionDate = mergedPr?.second ?: "",
                rmPr = mergedRmPr?.first,
                rmPrSessionDate = mergedRmPr?.second ?: "",
                hasPriorRealTonnage = (old?.hasPriorRealTonnage ?: false) || hasReal,
                previousHistory = newManual,
                previousHistoryBodyweight = newBw,
                previousHistoryAssisted = newAssisted,
                previousSessionId = headManual?.sessionId.orEmpty(),
                previousSessionIdBodyweight = headBw?.sessionId.orEmpty(),
                previousSessionIdAssisted = headAssisted?.sessionId.orEmpty(),
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
        var prDate = ""
        var rmPr: ExerciseSet.Strength? = null
        var rmPrE1rm = 0.0
        var rmPrDate = ""
        var hasReal = false
        // Recent real sessions per weighting approach (newest first, capped) — a manual↔
        // bodyweight↔assisted switch never compares across them (see SCHEMA_VERSION v5/v6).
        // Skipped slots (isUntouched) are invisible here: they persist the pre-fill for
        // walk-back continuity but must not advance "last performed".
        val histManual = mutableListOf<PreviousSession>()
        val histBw = mutableListOf<PreviousSession>()
        val histAssisted = mutableListOf<PreviousSession>()

        for (session in completedSessions) {
            val realSets = performedSetsFor(exerciseId, ctx, session)
            if (realSets.isEmpty()) continue
            hasReal = true

            val bestThisSession = realSets.maxByOrNull { it.tonnage() }
            if (bestThisSession != null && bestThisSession.tonnage() > prTonnage) {
                pr = bestThisSession
                prTonnage = bestThisSession.tonnage()
                prDate = session.dayKey()
            }

            val bestE1rmThisSession = realSets.maxByOrNull { it.e1rm() }
            if (bestE1rmThisSession != null && bestE1rmThisSession.e1rm() > rmPrE1rm) {
                rmPr = bestE1rmThisSession
                rmPrE1rm = bestE1rmThisSession.e1rm()
                rmPrDate = session.dayKey()
            }

            val entry = session.historyEntry(realSets)
            when (realSets.sessionLoadMode()) {
                LoadMode.BODYWEIGHT -> if (histBw.size < ExerciseStats.HISTORY_LIMIT) histBw += entry
                LoadMode.ASSISTED -> if (histAssisted.size < ExerciseStats.HISTORY_LIMIT) histAssisted += entry
                LoadMode.MANUAL -> if (histManual.size < ExerciseStats.HISTORY_LIMIT) histManual += entry
            }
        }

        if (!hasReal) return null
        val headManual = histManual.firstOrNull()
        val headBw = histBw.firstOrNull()
        val headAssisted = histAssisted.firstOrNull()
        return ContextStats(
            previousSets = headManual?.sets.orEmpty(),
            previousSessionDate = headManual?.date.orEmpty(),
            previousSetsBodyweight = headBw?.sets.orEmpty(),
            previousSessionDateBodyweight = headBw?.date.orEmpty(),
            previousSetsAssisted = headAssisted?.sets.orEmpty(),
            previousSessionDateAssisted = headAssisted?.date.orEmpty(),
            pr = pr?.toPreviousSet(),
            prSessionDate = prDate,
            rmPr = rmPr?.toPreviousSet(),
            rmPrSessionDate = rmPrDate,
            hasPriorRealTonnage = hasReal,
            previousHistory = histManual,
            previousHistoryBodyweight = histBw,
            previousHistoryAssisted = histAssisted,
            previousSessionId = headManual?.sessionId.orEmpty(),
            previousSessionIdBodyweight = headBw?.sessionId.orEmpty(),
            previousSessionIdAssisted = headAssisted?.sessionId.orEmpty(),
        )
    }
}
