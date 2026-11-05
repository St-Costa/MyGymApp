package com.mygymapp.data.model

import kotlin.math.ceil
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

/**
 * Reps that keep [tonnage] constant at [weight], always rounded **up** to the next whole rep
 * and floored at 1. Returns null when there is nothing to preserve or the new weight cannot
 * carry any load (zero/negative tonnage or weight) — callers then leave reps alone.
 *
 * Used by the strength/superset weight pickers: 50 kg × 10 reps = 500 kg; stepping the weight
 * to 60 kg yields 9 reps (540 kg) instead of silently dropping to 480 kg. Reps are always
 * whole numbers — never fractional. Pure, unit-tested.
 */
fun repsForSameTonnage(tonnage: Double, weight: Double): Int? {
    if (tonnage <= 0.0 || weight <= 0.0) return null
    return ceil(tonnage / weight).toInt().coerceAtLeast(1)
}

/**
 * Reps to perform at [currentWeight] to *beat* a record set of [prReps] reps at [prWeight]
 * (strictly greater tonnage). Always a whole number, rounded up: the lifter sees `10 x 77`
 * and `[11]` means "11 reps at today's weight beats it".
 *
 * Used for the bodyweight `[N]` badge suffix: the record reads `reps x body-weight-then`,
 * the target is computed against the latest weigh-in. Returns null when any input is
 * unusable — callers then show no suffix.
 */
fun repsToBeatTonnage(prReps: Int, prWeight: Double, currentWeight: Double): Int? {
    if (prReps <= 0 || prWeight <= 0.0 || currentWeight <= 0.0) return null
    return ((prReps * prWeight) / currentWeight).toInt() + 1
}

/** Best (highest) estimated 1RM across a set of strength sets, or null if none. */
fun List<ExerciseSet.Strength>.bestEstimated1RM(): Double? =
    filter { it.reps > 0 && it.weight > 0 }
        .maxOfOrNull { estimate1RM(it.weight, it.reps) }

/**
 * Materialized `weight` for one assisted-machine set: [bodyWeightKg] minus [assistOffsetKg] (the
 * number set on the assist machine — more assistance selected means *less* real load), rounded
 * to the nearest 0.5 kg and clamped to 0 (an assist offset at or above body weight means no net
 * load, e.g. full-assist warm-up reps). Returns 0.0 when there is no usable body weight
 * ([bodyWeightKg] null or <= 0) — the caller then leaves `weight` at 0, same fallback as
 * [materializeBodyweightWeight]. Kept pure (no Context/IO) so it is unit-testable — see
 * TonnageMathTest.
 */
fun materializeAssistedWeight(bodyWeightKg: Double?, assistOffsetKg: Double): Double {
    if (bodyWeightKg == null || bodyWeightKg <= 0.0) return 0.0
    val raw = (bodyWeightKg - assistOffsetKg).coerceAtLeast(0.0)
    return (raw * 2).roundToInt() / 2.0
}
