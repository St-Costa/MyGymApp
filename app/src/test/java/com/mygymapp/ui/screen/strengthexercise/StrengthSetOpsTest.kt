package com.mygymapp.ui.screen.strengthexercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the explicit per-set rules (ISM-20): [applyRepsUpdate],
 * [applyWeightUpdate], [confirmRepsUpdate]/[confirmWeightUpdate],
 * [anyTouched] and [allSetsFilled] — including the purple-≠-performed
 * invariant and the tonnage-anchor lifecycle.
 */
class StrengthSetOpsTest {

    private fun set(
        reps: Int = 10,
        weight: Double = 50.0,
        repsModified: Boolean = false,
        weightModified: Boolean = false,
        repsTouched: Boolean = false,
        weightTouched: Boolean = false,
        tonnageAnchor: Double = 0.0,
    ) = StrengthSetUi(
        reps = reps,
        weight = weight,
        repsModified = repsModified,
        weightModified = weightModified,
        repsTouched = repsTouched,
        weightTouched = weightTouched,
        tonnageAnchor = tonnageAnchor,
    )

    // ─── Reps update ───

    @Test
    fun `manual reps are the new truth and drop the anchor`() {
        val result = applyRepsUpdate(set(tonnageAnchor = 500.0), 12)
        assertEquals(12, result.reps)
        assertTrue(result.repsModified)
        assertTrue(result.repsTouched)
        assertEquals(0.0, result.tonnageAnchor, 0.0)
        assertEquals(0, applyRepsUpdate(set(), -3).reps)
    }

    // ─── Weight update ───

    @Test
    fun `touched reps make the weight move alone`() {
        val result = applyWeightUpdate(set(reps = 10, weight = 50.0, repsTouched = true, tonnageAnchor = 500.0), 60.0, manualLoad = true)
        assertEquals(10, result.reps)
        assertEquals(60.0, result.weight, 0.0)
        assertTrue(result.weightModified)
        assertTrue(result.weightTouched)
        assertEquals(0.0, result.tonnageAnchor, 0.0)
    }

    @Test
    fun `untouched manual load recalculates reps up at constant tonnage`() {
        // 50×10 anchored; 60 kg → ceil(500/60) = 9 reps, still grey.
        val result = applyWeightUpdate(set(reps = 10, weight = 50.0, tonnageAnchor = 500.0), 60.0, manualLoad = true)
        assertEquals(9, result.reps)
        assertFalse(result.repsModified)
        assertTrue(result.weightModified)
        assertTrue(result.weightTouched)
        assertEquals(500.0, result.tonnageAnchor, 0.0)
    }

    @Test
    fun `untouched manual load without an anchor anchors on screen tonnage`() {
        // No anchor yet: 50×10 = 500 becomes the anchor.
        val result = applyWeightUpdate(set(reps = 10, weight = 50.0), 60.0, manualLoad = true)
        assertEquals(9, result.reps)
        assertEquals(500.0, result.tonnageAnchor, 0.0)
    }

    @Test
    fun `zero tonnage anchors nothing and keeps reps`() {
        val result = applyWeightUpdate(set(reps = 0, weight = 0.0), 60.0, manualLoad = true)
        assertEquals(0, result.reps)
        assertEquals(0.0, result.tonnageAnchor, 0.0)
    }

    @Test
    fun `non-manual load never rewrites reps and clears the anchor`() {
        val result = applyWeightUpdate(
            set(reps = 10, weight = 20.0, repsModified = true, tonnageAnchor = 500.0),
            25.0,
            manualLoad = false,
        )
        assertEquals(10, result.reps)
        assertTrue(result.repsModified) // purple stays purple — the toggle didn't touch reps
        assertEquals(0.0, result.tonnageAnchor, 0.0)
    }

    // ─── Confirm ───

    @Test
    fun `confirms promote to touched and clear the anchor`() {
        assertTrue(confirmRepsUpdate(set()).let { it.repsModified && it.repsTouched && it.tonnageAnchor == 0.0 })
        assertTrue(confirmWeightUpdate(set()).let { it.weightModified && it.weightTouched && it.tonnageAnchor == 0.0 })
    }

    // ─── anyTouched / allSetsFilled ───

    @Test
    fun `any touch on any set marks the visit performed`() {
        assertFalse(listOf(set(), set()).anyTouched())
        assertTrue(listOf(set(), set(weightTouched = true)).anyTouched())
        assertTrue(listOf(set(repsTouched = true)).anyTouched())
    }

    @Test
    fun `manual load needs weight, bodyweight does not`() {
        assertTrue(allSetsFilled(listOf(set(reps = 8, weight = 30.0)), weightOptional = false))
        assertFalse(allSetsFilled(listOf(set(reps = 8, weight = 0.0)), weightOptional = false))
        assertTrue(allSetsFilled(listOf(set(reps = 8, weight = 0.0)), weightOptional = true))
        assertFalse(allSetsFilled(listOf(set(reps = 0, weight = 30.0)), weightOptional = true))
    }
}
