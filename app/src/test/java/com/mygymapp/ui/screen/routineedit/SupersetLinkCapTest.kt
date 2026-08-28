package com.mygymapp.ui.screen.routineedit

import com.mygymapp.data.model.ExerciseType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [canEnableSupersetLink] — the rule that stops the routine editor building a superset
 * chain longer than [com.mygymapp.ui.util.MAX_SUPERSET_SIZE] (3). See CHANGELOG "Supersets up
 * to 3".
 */
class SupersetLinkCapTest {

    private fun ex(paired: Boolean) = RoutineExerciseUi(
        exerciseId = "e", exerciseName = "e", exerciseType = ExerciseType.FORZA,
        sets = 3, supersetWithNext = paired,
    )

    private fun canLink(flags: List<Boolean>, index: Int): Boolean {
        val list = flags.map { ex(it) }
        return canEnableSupersetLink(buildExerciseSegments(list), index, list.size)
    }

    @Test
    fun `linking two standalone exercises is allowed`() {
        assertTrue(canLink(listOf(false, false, false), index = 0))
    }

    @Test
    fun `linking a single onto an existing pair (makes a chain of 3) is allowed`() {
        // [A-B] C  ->  link B(index 1) to C: 2 + 1 = 3, ok.
        assertTrue(canLink(listOf(true, false, false), index = 1))
    }

    @Test
    fun `linking a pair onto a pair (would be 4) is rejected`() {
        // [A-B] [C-D]  ->  link B(index 1) to C: 2 + 2 = 4 > 3.
        assertFalse(canLink(listOf(true, false, true, false), index = 1))
    }

    @Test
    fun `linking a single onto an existing chain of three is rejected`() {
        // [A-B-C] D  ->  link C(index 2) to D: 3 + 1 = 4 > 3.
        assertFalse(canLink(listOf(true, true, false, false), index = 2))
    }

    @Test
    fun `linking the last element forward is rejected (nothing after it)`() {
        assertFalse(canLink(listOf(false, false, false), index = 2))
    }

    @Test
    fun `out-of-range index is rejected`() {
        assertFalse(canLink(listOf(false, false), index = 5))
        assertFalse(canLink(listOf(false, false), index = -1))
    }
}
