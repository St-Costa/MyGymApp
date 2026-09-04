package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [HrZoneCalculator]: the on-device %HRR (Karvonen) classifier that must stay in
 * lockstep with the sync server's `compute_hr_zone_minutes` (see the class kdoc). A drift here
 * means the live in-session zone disagrees with the post-sync dashboard.
 */
class HrZoneCalculatorTest {

    @Test
    fun `classify with resting HR uses Karvonen boundaries`() {
        // maxHr=190, restingHr=60 -> HRR=130. Z1 boundary (50%) = 60 + 0.5*130 = 125.
        val calculator = HrZoneCalculator(maxHr = 190, restingHr = 60)

        assertEquals(HrZone.BELOW_Z1, calculator.classify(120))
        assertEquals(HrZone.Z1, calculator.classify(125))
        assertEquals(HrZone.Z5, calculator.classify(190))
    }

    @Test
    fun `classify without resting HR falls back to percent of HRmax`() {
        // maxHr=200, no restingHr -> Z1 boundary (50%) = 100.
        val calculator = HrZoneCalculator(maxHr = 200, restingHr = null)

        assertEquals(HrZone.BELOW_Z1, calculator.classify(99))
        assertEquals(HrZone.Z1, calculator.classify(100))
    }

    @Test
    fun `percent with resting HR is anchored to HRR not HRmax`() {
        // maxHr=190, restingHr=60 -> HRR=130. bpm=125 -> (125-60)/130 = 50%.
        val calculator = HrZoneCalculator(maxHr = 190, restingHr = 60)

        assertEquals(50, calculator.percent(125))
    }

    @Test
    fun `percent without resting HR is plain percent of HRmax`() {
        val calculator = HrZoneCalculator(maxHr = 200, restingHr = null)

        assertEquals(75, calculator.percent(150))
    }

    @Test
    fun `percent in zone is relative to zone and rounded to tens`() {
        val calculator = HrZoneCalculator(maxHr = 190, restingHr = 60)

        // Z1 spans 125..138 BPM: 131 is about halfway through the zone.
        assertEquals(HrZone.Z1, calculator.classify(131))
        assertEquals(50, calculator.percentInZone(131))
        assertEquals(0, calculator.percentInZone(125))
        assertEquals(100, calculator.percentInZone(138, HrZone.Z1))
    }

    @Test
    fun `boundaries list has exactly 5 cutoffs for 6 zones`() {
        val calculator = HrZoneCalculator(maxHr = 190, restingHr = 60)

        assertEquals(5, calculator.boundaries.size)
    }

    @Test
    fun `estimatedMaxHr is the unweighted mean of Fox, Tanaka and Gulati`() {
        val age = 30
        val fox = 220 - age
        val tanaka = 208 - 0.7 * age
        val gulati = 206 - 0.88 * age
        val expected = ((fox + tanaka + gulati) / 3.0).toInt()

        assertEquals(expected, HrZoneCalculator.estimatedMaxHr(age))
    }

    @Test
    fun `resting HR equal to max HR does not divide by zero in percent`() {
        // Degenerate but plausible with bad readiness data — HRR coerced to at least 1.
        val calculator = HrZoneCalculator(maxHr = 60, restingHr = 60)

        val percent = calculator.percent(60)

        assertEquals(0, percent)
    }
}
