package com.mygymapp.data.model

/** Epley formula: estimated 1-rep max from a single set's weight and reps. */
fun estimate1RM(weight: Double, reps: Int): Double = weight * (1 + reps / 30.0)

/** Best (highest) estimated 1RM across a set of strength sets, or null if none. */
fun List<ExerciseSet.Strength>.bestEstimated1RM(): Double? =
    filter { it.reps > 0 && it.weight > 0 }
        .maxOfOrNull { estimate1RM(it.weight, it.reps) }
