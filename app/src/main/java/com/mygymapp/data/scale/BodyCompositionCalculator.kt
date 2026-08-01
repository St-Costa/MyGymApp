package com.mygymapp.data.scale

import kotlin.math.max
import kotlin.math.min

data class BodyComposition(
    val bmi: Double,
    val bodyFatPercent: Double,
    val leanMassPercent: Double,
)

/**
 * Public-domain BIA regression formulas (Deurenberg-family), not VitaFit's own —
 * VitaFit's body-fat calc is a closed native library we can't read. These give
 * a same-ballpark estimate; only the day-to-day trend should be trusted, not
 * the absolute number. Ported from the community etekcity_esf551_ble project
 * (MIT), itself a from-scratch implementation of the well-known formula family.
 */
object BodyCompositionCalculator {
    fun calculate(
        weightKg: Double,
        heightCm: Int,
        age: Int,
        isMale: Boolean,
        impedanceOhm: Int,
    ): BodyComposition {
        val heightM = heightCm / 100.0
        val bmi = weightKg / (heightM * heightM)

        val sexIndex = if (isMale) 0 else 1
        val ageFactor = doubleArrayOf(0.103, 0.097)[sexIndex]
        val bmiFactor = doubleArrayOf(1.524, 1.545)[sexIndex]
        val constant = doubleArrayOf(22.0, 12.7)[sexIndex]

        val rawFatPercent = ageFactor * age + bmiFactor * bmi - 500.0 / impedanceOhm - constant
        val bodyFatPercent = max(5.0, min(75.0, rawFatPercent))

        return BodyComposition(
            bmi = bmi,
            bodyFatPercent = bodyFatPercent,
            leanMassPercent = 100.0 - bodyFatPercent,
        )
    }
}
