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
