package com.mygymapp.ui.screen.superset

import com.mygymapp.data.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [supersetCountdownDuration], the shared countdown duration of the superset
 * stretch timer (see docs/CONVENTIONS.md — stretch countdown section).
 */
class SupersetCountdownTest {

    private fun set(
        type: ExerciseType,
        timeSeconds: Int = 0,
        exerciseIndex: Int = 0,
    ) = SupersetSetUi(
        exerciseIndex = exerciseIndex,
        exerciseName = "x",
        exerciseType = type,
        setIndex = 0,
        timeSeconds = timeSeconds,
    )

    @Test
    fun `uses the first stretch set's duration`() {
        val sets = listOf(
            set(ExerciseType.FORZA),
            set(ExerciseType.STRETCH, timeSeconds = 45, exerciseIndex = 1),
            set(ExerciseType.STRETCH, timeSeconds = 90, exerciseIndex = 2),
        )
        assertEquals(45, supersetCountdownDuration(sets))
    }

    @Test
    fun `coerces a non-positive duration to 1`() {
        val sets = listOf(set(ExerciseType.STRETCH, timeSeconds = 0))
        assertEquals(1, supersetCountdownDuration(sets))
    }

    @Test
    fun `falls back to 60 when the chain has no stretch member`() {
        val sets = listOf(set(ExerciseType.FORZA), set(ExerciseType.FORZA))
        assertEquals(60, supersetCountdownDuration(sets))
        assertEquals(60, supersetCountdownDuration(emptyList()))
    }
}
