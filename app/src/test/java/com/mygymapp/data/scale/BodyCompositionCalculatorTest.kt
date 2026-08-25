package com.mygymapp.data.scale

import org.junit.Assert.assertEquals
import org.junit.Test

class BodyCompositionCalculatorTest {

    @Test
    fun `bmi is computed from weight and height regardless of sex or impedance`() {
        val result = BodyCompositionCalculator.calculate(
            weightKg = 80.0, heightCm = 180, age = 30, isMale = true, impedanceOhm = 500,
        )

        // 80 / 1.8^2 = 24.691...
        assertEquals(24.691, result.bmi, 0.01)
    }

    @Test
    fun `leanMassPercent always complements bodyFatPercent to 100`() {
        val result = BodyCompositionCalculator.calculate(
            weightKg = 70.0, heightCm = 170, age = 40, isMale = false, impedanceOhm = 450,
        )

        assertEquals(100.0, result.bodyFatPercent + result.leanMassPercent, 0.0001)
    }

    @Test
    fun `bodyFatPercent is clamped to the 5 to 75 plausible range`() {
        // Extreme inputs designed to blow the raw formula past both ends.
        val veryLow = BodyCompositionCalculator.calculate(
            weightKg = 40.0, heightCm = 200, age = 15, isMale = true, impedanceOhm = 2000,
        )
        val veryHigh = BodyCompositionCalculator.calculate(
            weightKg = 200.0, heightCm = 140, age = 90, isMale = false, impedanceOhm = 50,
        )

        assertEquals(5.0, veryLow.bodyFatPercent, 0.0001)
        assertEquals(75.0, veryHigh.bodyFatPercent, 0.0001)
    }

    @Test
    fun `male and female use different regression constants`() {
        val male = BodyCompositionCalculator.calculate(
            weightKg = 80.0, heightCm = 180, age = 30, isMale = true, impedanceOhm = 500,
        )
        val female = BodyCompositionCalculator.calculate(
            weightKg = 80.0, heightCm = 180, age = 30, isMale = false, impedanceOhm = 500,
        )

        assertEquals(male.bmi, female.bmi, 0.0001) // same physical inputs -> same BMI
        assert(male.bodyFatPercent != female.bodyFatPercent) // but different BIA formula branch
    }
}
