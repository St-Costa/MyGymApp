package com.mygymapp.data.model

import kotlin.math.roundToInt

/** Epley formula: estimated 1-rep max from a single set's weight and reps. */
fun estimate1RM(weight: Double, reps: Int): Double = weight * (1 + reps / 30.0)

/** The four allowed [Exercise.bwLoadPercent] values. */
val BW_LOAD_PERCENTS = listOf(25, 50, 75, 100)

/** Default [Exercise.bwLoadPercent] for a bodyweight exercise with no value set (legacy files). */
const val DEFAULT_BW_LOAD_PERCENT = 75

/**
 * Materialized `weight` for one bodyweight set: [bwLoadPercent] percent of [bodyWeightKg],
 * rounded to the nearest 0.5 kg. Returns 0.0 when there is no usable body weight
 * ([bodyWeightKg] null or <= 0) — the caller then leaves `weight` at 0 and relies on
 * ExerciseSet.Strength.isBodyweight to keep the set from being treated as "empty".
 * Kept pure (no Context/IO) so it is unit-testable — see TonnageMathTest.
 */
fun materializeBodyweightWeight(bwLoadPercent: Int, bodyWeightKg: Double?): Double {
    if (bodyWeightKg == null || bodyWeightKg <= 0.0) return 0.0
    val raw = bwLoadPercent / 100.0 * bodyWeightKg
    return (raw * 2).roundToInt() / 2.0
}

/** Best (highest) estimated 1RM across a set of strength sets, or null if none. */
fun List<ExerciseSet.Strength>.bestEstimated1RM(): Double? =
    filter { it.reps > 0 && it.weight > 0 }
        .maxOfOrNull { estimate1RM(it.weight, it.reps) }
