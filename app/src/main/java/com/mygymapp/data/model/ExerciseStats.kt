package com.mygymapp.data.model

/**
 * Pre-computed, per-exercise derived data — a "materialized view" over that exercise's
 * session history, kept in `history/_stats/{exerciseId}.yaml` and refreshed by
 * [WorkoutRepository][com.mygymapp.data.repository.WorkoutRepository] whenever a completed
 * session is saved or deleted.
 *
 * Purpose: the strength / stretch / superset exercise screens, and the active-routine open,
 * need only two things from history to render — the most recent real set data ("previous"
 * grey pre-fill) and the all-time tonnage PR. Computing those meant reading and parsing every
 * session file that contains the exercise (dozens of Markdown parses per screen open, ×N for
 * a superset). This sidecar turns that into a single small read.
 *
 * ## Consistency
 * The sidecar is a cache derived from the `.md` files; those remain the source of truth.
 * - **Save**: [WorkoutRepository.save] merges the just-saved session into the sidecar
 *   (PR compare-and-set, previous replaced only if the new sets carry real data).
 * - **Delete / switch / ghost cleanup**: the affected exercises' sidecars are rebuilt from a
 *   full scan of that one exercise's history (the slow path, but correct, and rare).
 * - **Schema change**: bump [SCHEMA_VERSION]. A sidecar whose `schemaVersion` doesn't match
 *   is ignored on read and regenerated — no explicit migration.
 * A reader that finds no sidecar, or a stale one, must fall back to the history scan and
 * write a fresh sidecar.
 */
data class ExerciseStats(
    val exerciseId: String,
    val schemaVersion: Int = SCHEMA_VERSION,
    /** One entry per slot context that has any recorded history. Missing key ⇒ no history. */
    val perContext: Map<SlotContext, ContextStats> = emptyMap(),
) {
    fun forContext(ctx: SlotContext): ContextStats? = perContext[ctx]

    companion object {
        /**
         * Bump when the meaning of any stored field changes (how "previous" is chosen, how the
         * PR is defined, added fields that old sidecars can't supply). Forces lazy regeneration.
         *
         * v1: `previousSets` = sets of the most recent session (by completedAt) where this
         *     exercise, in this slot context, had at least one set with reps>0 or weight>0.
         *     `pr` = the single Strength set with the highest reps*weight ever recorded for this
         *     exercise in this slot context (bodyweight-materialized weight included).
         *     `hasPriorRealTonnage` = any such PR-eligible set exists at all.
         *
         * v2: `previousSets[].bwBaseWeightKg` / `pr.bwBaseWeightKg` added — the lifter's body
         *     weight at the time a bodyweight set was recorded (0.0 for non-bodyweight sets, or
         *     legacy sets logged with no scale weigh-in). Bodyweight exercise screens show the
         *     PR as `reps x bwBaseWeightKg` ("peso corpo in quel momento") instead of the
         *     materialized `weight` (= bwLoadPercent% of that). PR selection is unchanged — still
         *     by materialized reps*weight. Old sidecars can't supply the field ⇒ lazy rebuild.
         *
         * v3: `rmPr` added — the single set with the highest estimated 1RM (Epley,
         *     `weight * (1 + reps/30)`) ever recorded for this exercise+context. Separate from
         *     `pr` (highest reps*weight): a heavy low-rep set can win one and not the other. The
         *     exercise screens show both badges, e1RM on top. Old sidecars can't supply it ⇒
         *     lazy rebuild.
         *
         * v4: `previousSets` is now split by weighting approach. The plain `previousSets` /
         *     `previousSessionDate` fields carry the most recent real session whose sets were
         *     **non-bodyweight**; the new `previousSetsBodyweight` / `previousSessionDateBodyweight`
         *     carry the most recent real session whose sets were **bodyweight** (materialized
         *     `weight` = bwLoadPercent% of body weight). `PreviousSet.isBodyweight` records which
         *     kind each stored set was. Reason: when an exercise is switched from manual-load to
         *     bodyweight (or back), comparing today's ~59 kg materialized set against a legacy
         *     `weight: 1.0` placeholder produced +5000% change badges in the active-routine list.
         *     The active-routine badge now picks the previous matching the exercise's *current*
         *     `isBodyweight` and shows "primo dato" when there's no like-with-like history. `pr` /
         *     `rmPr` / `hasPriorRealTonnage` stay all-time across both approaches. Old sidecars
         *     can't supply the split ⇒ lazy rebuild.
         */
        const val SCHEMA_VERSION = 4
    }
}

/** Derived data for one (exercise, slot context) pair. */
data class ContextStats(
    /**
     * The strength sets of the most recent session that had real **non-bodyweight** data for
     * this exercise in this context — the grey "previous" pre-fill source and the source of the
     * active-routine change badge for a manual-load exercise. Empty for a stretch-only history,
     * or when every real session for this context was bodyweight. Never holds an all-zero
     * session's sets. See [previousSetsBodyweight] for the bodyweight counterpart.
     */
    val previousSets: List<PreviousSet> = emptyList(),
    /** `completedAt` (or `date` fallback) of the session [previousSets] came from. */
    val previousSessionDate: String = "",
    /**
     * Same as [previousSets] but restricted to sessions whose sets were **bodyweight**
     * (materialized `weight`). Used for the active-routine change badge when the exercise is
     * currently configured as bodyweight, so a manual-load ↔ bodyweight switch never compares
     * across approaches. Empty when no real bodyweight session exists for this context.
     */
    val previousSetsBodyweight: List<PreviousSet> = emptyList(),
    /** `completedAt` (or `date` fallback) of the session [previousSetsBodyweight] came from. */
    val previousSessionDateBodyweight: String = "",
    /** All-time best tonnage (reps × weight) Strength set for this exercise+context. */
    val pr: PreviousSet? = null,
    /**
     * All-time best estimated-1RM (Epley: `weight * (1 + reps/30)`) Strength set for this
     * exercise+context. May be a different set than [pr] — a heavy low-rep single has a high
     * e1RM but low tonnage. Null when no PR-eligible set exists (same condition as [pr]).
     */
    val rmPr: PreviousSet? = null,
    /**
     * True once any PR-eligible Strength set (reps>0 && weight>0, bodyweight-materialized
     * included) has ever been recorded for this exercise+context. Drives the "primo dato"
     * badge in the active-routine list.
     */
    val hasPriorRealTonnage: Boolean = false,
) {
    /**
     * The "previous" set list matching a weighting approach — [previousSetsBodyweight] when
     * [bodyweight] is true, [previousSets] otherwise (schema v4). Callers that compare today's
     * work against history (grey pre-fill, active-routine change badge) must use this so a
     * manual↔bodyweight switch never mixes approaches.
     */
    fun previousSetsFor(bodyweight: Boolean): List<PreviousSet> =
        if (bodyweight) previousSetsBodyweight else previousSets

    /** [previousSessionDate] / [previousSessionDateBodyweight], matching [previousSetsFor]. */
    fun previousSessionDateFor(bodyweight: Boolean): String =
        if (bodyweight) previousSessionDateBodyweight else previousSessionDate
}

/**
 * A single recorded strength set, flattened to just what the pre-fill / PR need. Bodyweight
 * sets are stored with their materialized `weight` already applied, so `reps * weight` works
 * with no special-casing — same as everywhere else (see TonnageMath).
 *
 * [bwBaseWeightKg] is the lifter's body weight (from the most recent scale weigh-in on or
 * before the session) when this set was a bodyweight set; 0.0 for non-bodyweight sets and for
 * bodyweight sets logged before any weigh-in existed. Bodyweight exercise screens display the
 * PR / previous as `reps x bwBaseWeightKg` rather than the materialized `weight`.
 */
data class PreviousSet(
    val reps: Int = 0,
    val weight: Double = 0.0,
    val bwBaseWeightKg: Double = 0.0,
    /**
     * True when this set was recorded as bodyweight work (its `weight` is the materialized
     * bwLoadPercent% of body weight), false for a manual-load set. Drives which `previousSets`
     * list a set lands in during a rebuild — see [ExerciseStats.SCHEMA_VERSION] v4.
     */
    val isBodyweight: Boolean = false,
)
