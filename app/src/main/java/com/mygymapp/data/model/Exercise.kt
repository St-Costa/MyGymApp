package com.mygymapp.data.model

data class Exercise(
    val id: String,
    val name: String,
    val type: ExerciseType,
    val bodypart: String,
    val link: String = "",
    val notes: String = "",
    val defaultRepRangeMin: Int = 8,
    val defaultRepRangeMax: Int = 12,
    val created: String = "",
    val updated: String = "",
    // FORZA only: no external weight by design (e.g. plank, push-ups, mobility work).
    // Distinguishes "genuinely bodyweight, weight=0 is correct" from "set never touched,
    // weight=0 by default" — without this, tonnage/PR/e1RM analysis (phone-side and
    // server-side, see ANALYSIS_SPEC.md §1.2-1.5) silently drops all bodyweight work
    // because it filters on weight > 0. Propagated onto each WorkoutExercise's sets when
    // a session is built from this exercise — see WorkoutExercise/ExerciseSet.Strength.
    val isBodyweight: Boolean = false,
    // How much of the lifter's body weight this movement actually loads, as a percent —
    // one of 25 / 50 / 75 / 100 (squat ≈ 100, plank/push-up ≈ 75, reverse sit-up ≈ 50,
    // tibialis raise ≈ 25). Only meaningful when isBodyweight is true, and then it is
    // mandatory: a bodyweight exercise always carries one of the four values (legacy files
    // with no value migrate to 75 in ExerciseParser). At exercise-completion time each set's
    // `weight` is materialized to bwLoadPercent/100 * bodyWeightKg so all downstream tonnage/
    // PR/e1RM math (phone and server) keeps working unchanged on `reps * weight`.
    val bwLoadPercent: Int = 0,
)

enum class ExerciseType {
    FORZA,
    STRETCH,
    CARDIO;

    companion object {
        fun fromString(value: String): ExerciseType =
            when (value.lowercase()) {
                "stretch" -> STRETCH
                "cardio" -> CARDIO
                else -> FORZA
            }
    }

    fun toFileString(): String = when (this) {
        FORZA -> "forza"
        STRETCH -> "stretch"
        CARDIO -> "cardio"
    }
}
