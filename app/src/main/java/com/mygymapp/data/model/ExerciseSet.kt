package com.mygymapp.data.model

sealed class ExerciseSet {
    data class Strength(
        val reps: Int = 0,
        val weight: Double = 0.0,
        // Copied from Exercise.isBodyweight when the set is built (see
        // ActiveRoutineViewModel/StrengthExerciseViewModel/SupersetViewModel). Marks a
        // set as genuinely bodyweight work (weight=0 by nature, e.g. plank) rather than
        // an untouched/never-filled set (weight=0 by default) — analysis that filters on
        // weight > 0 (tonnage, PR, e1RM) must check this before excluding a set.
        val isBodyweight: Boolean = false,
    ) : ExerciseSet()

    data class Stretch(
        val timeSeconds: Int = 0,
        val done: Boolean = false,
    ) : ExerciseSet()
}
