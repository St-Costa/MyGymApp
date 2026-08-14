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
) {
    /**
     * True when the lifter never touched a pre-filled value for this exercise AND never tapped
     * "Complete" either — identical to an exercise that was never opened. Distinct from
     * [completedEmpty], which is the same "no data" case but explicitly closed out by the user.
     */
    fun isUntouched(): Boolean = !completed && sets.isEmpty()
}
