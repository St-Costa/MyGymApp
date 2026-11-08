package com.mygymapp.ui.screen.routineedit

import com.mygymapp.data.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the explicit routine-editor rules (ISM-27): [toggleSupersetLink],
 * [removeExerciseAt], [moveWarmupLineDown]/[moveWarmupLineUp],
 * [snapWarmupToBoundary] and [buildRoutine]. Each row mirrors one branch of
 * the ViewModel handlers they were extracted from — including the
 * cross-line refusal, the re-link-vs-clear repair, and the transient
 * mid-segment warmup value.
 */
class RoutineEditOpsTest {

    private fun ex(linked: Boolean = false) = RoutineExerciseUi(
        exerciseId = "e-${linked}",
        exerciseName = "e",
        exerciseType = ExerciseType.FORZA,
        sets = 3,
        supersetWithNext = linked,
    )

    private fun flags(list: List<RoutineExerciseUi>) = list.map { it.supersetWithNext }

    // ─── Toggle ───

    @Test
    fun `toggle flips the flag both ways`() {
        assertEquals(listOf(true, false), flags(toggleSupersetLink(listOf(ex(false), ex(false)), 0, 0)))
        assertEquals(listOf(false, false), flags(toggleSupersetLink(listOf(ex(true), ex(false)), 0, 0)))
    }

    @Test
    fun `toggle across the warmup line is refused`() {
        val list = listOf(ex(false), ex(false))
        assertEquals(list, toggleSupersetLink(list, 0, 1))
    }

    @Test
    fun `toggle refusing the cap and out-of-range indices are no-ops`() {
        // [A-B-C] D -> linking C(2) to D would make 4.
        val capped = listOf(ex(true), ex(true), ex(false), ex(false))
        assertEquals(capped, toggleSupersetLink(capped, 2, 0))
        val list = listOf(ex(false), ex(false))
        assertEquals(list, toggleSupersetLink(list, 5, 0))
        assertEquals(list, toggleSupersetLink(list, -1, 0))
    }

    // ─── Remove ───

    @Test
    fun `removing the middle of a 3-chain re-links predecessor to successor`() {
        val removal = removeExerciseAt(listOf(ex(true), ex(true), ex(false)), 1, 0)
        assertEquals(listOf(true, false), flags(removal.exercises))
        assertEquals(0, removal.warmupCount)
    }

    @Test
    fun `removing the last member clears the dangling predecessor flag`() {
        val removal = removeExerciseAt(listOf(ex(true), ex(true), ex(false)), 2, 0)
        assertEquals(listOf(true, false), flags(removal.exercises))
    }

    @Test
    fun `removing the first member shifts the chain up untouched`() {
        val removal = removeExerciseAt(listOf(ex(true), ex(true), ex(false)), 0, 0)
        assertEquals(listOf(true, false), flags(removal.exercises))
    }

    @Test
    fun `removal above the line shifts the line up`() {
        val removal = removeExerciseAt(listOf(ex(false), ex(false), ex(false)), 0, 2)
        assertEquals(2, removal.exercises.size)
        assertEquals(1, removal.warmupCount)
    }

    @Test
    fun `removal below the line leaves the line alone`() {
        val removal = removeExerciseAt(listOf(ex(false), ex(false), ex(false)), 2, 1)
        assertEquals(1, removal.warmupCount)
    }

    @Test
    fun `out-of-range removal returns the input unchanged`() {
        val list = listOf(ex(false))
        val removal = removeExerciseAt(list, 7, 1)
        assertTrue(removal.exercises === list)
        assertEquals(1, removal.warmupCount)
    }

    // ─── Warmup line ───

    @Test
    fun `line moves whole segments`() {
        val list = listOf(ex(true), ex(false), ex(false))
        assertEquals(2, moveWarmupLineDown(list, 0))
        assertEquals(3, moveWarmupLineDown(list, 2))
        assertEquals(3, moveWarmupLineDown(list, 3))
        assertEquals(0, moveWarmupLineUp(list, 2))
        assertEquals(2, moveWarmupLineUp(list, 3))
        assertEquals(0, moveWarmupLineUp(list, 0))
    }

    @Test
    fun `snap never splits a pair`() {
        val list = listOf(ex(true), ex(false), ex(false))
        assertEquals(0, snapWarmupToBoundary(list, 1))
        assertEquals(0, snapWarmupToBoundary(list, 0))
        assertEquals(2, snapWarmupToBoundary(list, 2))
        assertEquals(3, snapWarmupToBoundary(list, 9))
    }

    // ─── Build ───

    private fun state(
        warmupCount: Int = 0,
        isFixedDaily: Boolean = false,
        name: String = " Push ",
    ) = RoutineEditUiState(
        id = "rt-1",
        name = name,
        day = "monday",
        exercises = listOf(ex(true), ex(false)),
        isNew = false,
        isFixedDaily = isFixedDaily,
        warmupCount = warmupCount,
    )

    @Test
    fun `leading prefix is warmup except in the fixed-daily container`() {
        val warm = buildRoutine(state(warmupCount = 1)).exercises
        assertTrue(warm[0].isWarmup)
        assertFalse(warm[1].isWarmup)

        val daily = buildRoutine(state(warmupCount = 1, isFixedDaily = true)).exercises
        assertTrue(daily.none { it.isWarmup })
    }

    @Test
    fun `superset flags ride through and the name is trimmed`() {
        val routine = buildRoutine(state())
        assertEquals(listOf(true, false), routine.exercises.map { it.supersetWithNext })
        assertEquals("Push", routine.name)
        assertEquals("monday", routine.day)
        assertEquals("rt-1", routine.id)
    }
}
