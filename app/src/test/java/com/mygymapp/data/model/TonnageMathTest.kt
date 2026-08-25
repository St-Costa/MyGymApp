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
}
