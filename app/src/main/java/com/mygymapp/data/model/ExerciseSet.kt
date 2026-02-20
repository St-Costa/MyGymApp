package com.mygymapp.data.model

sealed class ExerciseSet {
    data class Strength(
        val reps: Int = 0,
        val weight: Double = 0.0,
    ) : ExerciseSet()

    data class Stretch(
        val timeSeconds: Int = 0,
        val done: Boolean = false,
    ) : ExerciseSet()
}
