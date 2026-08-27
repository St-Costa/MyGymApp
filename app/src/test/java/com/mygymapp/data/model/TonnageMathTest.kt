package com.mygymapp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TonnageMathTest {

    @Test
    fun `estimate1RM applies the Epley formula`() {
        // 100kg x 5 reps -> 100 * (1 + 5/30) = 116.666...
        assertEquals(116.666, estimate1RM(100.0, 5), 0.001)
    }

    @Test
    fun `estimate1RM at 1 rep returns the weight lifted plus one thirtieth`() {
        assertEquals(100.0 * (1 + 1 / 30.0), estimate1RM(100.0, 1), 0.001)
    }

    @Test
    fun `bestEstimated1RM picks the highest estimate across sets`() {
        val sets = listOf(
            ExerciseSet.Strength(reps = 10, weight = 60.0),
            ExerciseSet.Strength(reps = 5, weight = 80.0),
            ExerciseSet.Strength(reps = 3, weight = 85.0),
        )

        val expected = sets.maxOf { estimate1RM(it.weight, it.reps) }
        assertEquals(expected, sets.bestEstimated1RM()!!, 0.001)
    }

    @Test
    fun `bestEstimated1RM excludes untouched sets with zero reps or weight`() {
        val sets = listOf(
            ExerciseSet.Strength(reps = 0, weight = 0.0),
            ExerciseSet.Strength(reps = 8, weight = 50.0),
        )

        assertEquals(estimate1RM(50.0, 8), sets.bestEstimated1RM()!!, 0.001)
    }

    @Test
    fun `bestEstimated1RM excludes bodyweight sets with zero external weight`() {
        val sets = listOf(
            ExerciseSet.Strength(reps = 12, weight = 0.0, isBodyweight = true),
        )

        assertNull(sets.bestEstimated1RM())
    }

    @Test
    fun `bestEstimated1RM on an empty list is null`() {
        assertNull(emptyList<ExerciseSet.Strength>().bestEstimated1RM())
    }

    @Test
    fun `materializeBodyweightWeight takes the percent of body weight rounded to half a kilo`() {
        // 75% of 73.3 = 54.975 -> rounds to 55.0
        assertEquals(55.0, materializeBodyweightWeight(75, 73.3), 0.0001)
        // 50% of 81.2 = 40.6 -> nearest 0.5 is 40.5
        assertEquals(40.5, materializeBodyweightWeight(50, 81.2), 0.0001)
        // 100% passes the body weight straight through (already on a 0.5 grid)
        assertEquals(80.0, materializeBodyweightWeight(100, 80.0), 0.0001)
        // 25% of 84.0 = 21.0
        assertEquals(21.0, materializeBodyweightWeight(25, 84.0), 0.0001)
    }

    @Test
    fun `materializeBodyweightWeight returns zero when there is no usable body weight`() {
        assertEquals(0.0, materializeBodyweightWeight(75, null), 0.0)
        assertEquals(0.0, materializeBodyweightWeight(75, 0.0), 0.0)
        assertEquals(0.0, materializeBodyweightWeight(75, -1.0), 0.0)
    }

    @Test
    fun `a materialized bodyweight set now contributes to tonnage and e1RM`() {
        val bw = materializeBodyweightWeight(75, 73.3) // 55.0
        val set = ExerciseSet.Strength(
            reps = 12, weight = bw, isBodyweight = true, bwLoadPercent = 75, bwBaseWeightKg = 73.3,
        )
        assertEquals(12 * 55.0, set.reps * set.weight, 0.0001)
        assertEquals(estimate1RM(55.0, 12), listOf(set).bestEstimated1RM()!!, 0.0001)
    }
}
