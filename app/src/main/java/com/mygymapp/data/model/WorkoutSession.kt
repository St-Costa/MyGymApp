package com.mygymapp.data.model

data class WorkoutSession(
    val id: String,
    val routineId: String,
    val routineName: String,
    val date: String,
    val startedAt: String = "",
    val completedAt: String = "",
    val totalTonnage: Double = 0.0,
    val tonnageByBodypart: Map<String, Double> = emptyMap(),
    val sessionCalories: Double = 0.0,
    val sessionTrimp: Double = 0.0,
    val vo2max: Double = 0.0,
    // ECG-derived metrics (post-session analysis, Step B)
    val ecgBeats: Int = 0,
    val ecgDurationSec: Double = 0.0,
    val ecgAvgHr: Double = 0.0,
    val ecgSessionRmssd: Double = 0.0,
    val ecgPacCount: Int = 0,
    val ecgPauseCount: Int = 0,
    val ecgIrregularBeats: Int = 0,
    val cardiacDriftBpmMin: Double = 0.0,
    // Extended HRV + recovery + screening
    val restingHr: Int = 0,             // from readiness 60s
    val hrr60s: Double = 0.0,           // average HR drop 60s after peaks (BPM)
    val sdnn: Double = 0.0,             // overall HRV (ms)
    val pnn50: Double = 0.0,            // % of RR pairs with >50ms diff
    val poincareSd1: Double = 0.0,      // short-term (vagal) scatter (ms)
    val poincareSd2: Double = 0.0,      // long-term scatter (ms)
    val poincareRatio: Double = 0.0,    // SD2/SD1 sympathovagal balance
    val afibSuspicionEpisodes: Int = 0, // sustained irregular segments
    // Session-RPE (Foster method): subjective "how hard was this session" 0-9, asked right
    // after the session ends via a mandatory (non-skippable) prompt. Null only transiently,
    // before the prompt is answered — abandoned/ghost sessions never reach registration, so
    // no completed session persists without one. Complements totalTonnage (external load)
    // as the internal-load signal the server's ACWR monitoring uses to validate/enrich
    // itself. See docs/SYNC.md.
    val sessionRpe: Int? = null,
    // sessionRpe * session duration in minutes (Foster's session-load method). Null unless
    // both sessionRpe and a valid startedAt/completedAt pair are available.
    val sessionLoad: Float? = null,
    val exercises: List<WorkoutExercise> = emptyList(),
    val notes: String = "",
)

data class WorkoutExercise(
    val exerciseId: String,
    val exerciseName: String,
    val bodypart: String,
    val type: ExerciseType,
    val completed: Boolean = false,
    val sets: List<ExerciseSet> = emptyList(),
    /** Warmup or fixed-daily exercise: still recorded, but excluded from all tonnage math. */
    val excludeFromTonnage: Boolean = false,
    /**
     * True when this exercise was performed as a fixed-daily exercise in this session.
     * Used so daily progress is compared only against prior sessions where it was also
     * daily (and normal progress only against prior normal sessions).
     */
    val isDaily: Boolean = false,
    /**
     * True when the lifter tapped "Complete" without ever touching a pre-filled value (see
     * docs/CONVENTIONS.md "Untouched-exercise guard"). Unlike [isUntouched], this exercise IS
     * marked completed — it closes out of the active list like any other — but carries no set
     * data, so the UI flags it (red border, warning icon) instead of showing a tonnage change.
     */
    val completedEmpty: Boolean = false,
    /**
     * Set when this slot was swapped mid-session for a different exercise of the same
     * bodypart+type via "Switch exercise" (see docs/CONVENTIONS.md#switch-exercise). Holds the
     * originally-planned exerciseId — the slot itself now carries the NEW exercise's id/name/
     * bodypart/type/sets. This is purely a per-session record: the routine on disk is never
     * touched, so the next session from the same routine proposes the original exercise again.
     * Null for every exercise that was never switched (the overwhelming majority).
     */
    val substitutedFor: String? = null,
) {
    /**
     * True when this exercise does not represent performed work: the lifter never tapped
     * "Complete" for it (or tapped it without touching any value, which no longer marks it
     * completed — see docs/CONVENTIONS.md "Untouched-exercise guard"). Its `sets` may be
     * non-empty — they carry the grey pre-fill that was on screen, persisted so re-entry
     * shows the same numbers — but a non-completed exercise is still "not done", so tonnage
     * comparisons and the like must skip it. Distinct from [completedEmpty], where the user
     * did close the exercise out (completed = true) but recorded no data.
     */
    fun isUntouched(): Boolean = !completed

    /**
     * True when no set in this slot carries any real data yet — same "did the lifter actually
     * start this" check used by the ghost-session guard (see
     * docs/CONVENTIONS.md#ghost-session-prevention) and reused here as the switch-exercise
     * eligibility guard, so both stay in sync by construction instead of by two copies of the
     * same per-set-type logic drifting apart.
     */
    fun hasNoRecordedSets(): Boolean = sets.none { set ->
        when (set) {
            is ExerciseSet.Strength -> set.reps > 0 || set.weight > 0.0
            is ExerciseSet.Stretch -> set.done
            is ExerciseSet.Cardio -> set.startedAt.isNotBlank()
        }
    }

    /**
     * "Switch exercise" is allowed only before the lifter has recorded anything in this slot,
     * and only once per slot per session — once switched (or once real data exists), the slot
     * is locked for the rest of this session. See docs/CONVENTIONS.md#switch-exercise.
     */
    fun isSwitchEligible(): Boolean = substitutedFor == null && hasNoRecordedSets() && !completed
}

/**
 * Replaces the slot currently holding [oldExerciseId] with [newExercise] — see
 * docs/CONVENTIONS.md#switch-exercise. Returns `this` unchanged if the slot doesn't exist or
 * isn't eligible (see [WorkoutExercise.isSwitchEligible]), so callers can check the result
 * for "did anything change" instead of duplicating the eligibility check themselves.
 *
 * The slot keeps its position in [WorkoutSession.exercises] and its warmup/daily/tonnage
 * flags (switch is only ever offered for plain NORMAL slots to begin with) — only
 * exerciseId/exerciseName/bodypart/type/sets change, plus `substitutedFor` recording the
 * original id for history/sync. Shared by ActiveRoutineViewModel and the three exercise
 * ViewModels so the mutation logic lives in exactly one place.
 */
fun WorkoutSession.withExerciseSwitched(oldExerciseId: String, newExercise: Exercise): WorkoutSession {
    val slot = exercises.find { it.exerciseId == oldExerciseId } ?: return this
    if (!slot.isSwitchEligible()) return this
    // Regenerate empty sets matching the new exercise's type (same shape the session-creation
    // path in ActiveRoutineViewModel.init builds for a freshly-started slot). STRETCH defaults
    // to 60s per set — mirrors StretchExerciseViewModel's own fallback when no sets exist yet
    // — rather than 0s, which would show a stopwatch target of zero.
    val newSets = when (newExercise.type) {
        ExerciseType.FORZA -> slot.sets.map { ExerciseSet.Strength(isBodyweight = newExercise.isBodyweight) }
        ExerciseType.STRETCH -> slot.sets.map { ExerciseSet.Stretch(timeSeconds = 60) }
        ExerciseType.CARDIO -> emptyList()
    }
    val newSlot = slot.copy(
        exerciseId = newExercise.id,
        exerciseName = newExercise.name,
        bodypart = newExercise.bodypart,
        type = newExercise.type,
        sets = newSets,
        substitutedFor = oldExerciseId,
    )
    return copy(exercises = exercises.map { if (it.exerciseId == oldExerciseId) newSlot else it })
}
