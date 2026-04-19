package com.mygymapp.data.model

data class WorkoutSession(
    val id: String,
    val routineId: String,
    val routineName: String,
    val date: String,
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
)
