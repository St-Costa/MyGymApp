package com.mygymapp.ui.screen.exercise

import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [mergeExitSets] — the back-navigation set merge behind "Switch exercise"
 * eligibility (see docs/CONVENTIONS.md#switch-exercise).
 *
 * Regression: exercise screens pre-fill every set with the previous session's numbers, and the
 * old exit path persisted those pre-fills verbatim even on a zero-touch visit. That fabricated
 * recorded data (`reps > 0 || weight > 0`), so re-entering the slot showed no swap icon — always
 * on the first uncompleted exercise, the one the lifter peeks into.
 */
class ExerciseSessionExitSetsTest {

    private val freshEntry = ExerciseSet.Strength(reps = 0, weight = 0.0)
    private val prefillBuilt = ExerciseSet.Strength(reps = 10, weight = 80.0)

    @Test
    fun `untouched back-out on a fresh slot keeps the zero entry`() {
        val result = mergeExitSets(
            built = prefillBuilt,
            touched = false,
            completed = false,
            entry = freshEntry,
        )

        assertEquals(0, result.reps)
        assertEquals(0.0, result.weight, 0.0)
    }

    @Test
    fun `untouched back-out preserves switch eligibility end to end`() {
        val exitSet = mergeExitSets(
            built = prefillBuilt,
            touched = false,
            completed = false,
            entry = freshEntry,
        )
        val slot = WorkoutExercise(
            exerciseId = "ex-1",
            exerciseName = "Reverse Situp",
            bodypart = "abs",
            type = ExerciseType.FORZA,
            sets = listOf(exitSet),
        )

        assertTrue(slot.hasNoRecordedSets())
        assertTrue(slot.isSwitchEligible())
    }

    @Test
    fun `touched back-out persists the on-screen value`() {
        val result = mergeExitSets(
            built = prefillBuilt,
            touched = true,
            completed = false,
            entry = freshEntry,
        )

        assertEquals(10, result.reps)
        assertEquals(80.0, result.weight, 0.0)
    }

    @Test
    fun `explicit completion persists pre-fills even when untouched`() {
        // Completions intentionally keep the shown numbers (completed-empty guard) — only the
        // back-out path restores the entry value.
        val result = mergeExitSets(
            built = prefillBuilt,
            touched = false,
            completed = true,
            entry = freshEntry,
        )

        assertEquals(10, result.reps)
        assertEquals(80.0, result.weight, 0.0)
    }

    @Test
    fun `recorded entry data is never wiped by an untouched visit`() {
        val recorded = ExerciseSet.Strength(reps = 8, weight = 70.0)
        val result = mergeExitSets(
            built = prefillBuilt,
            touched = false,
            completed = false,
            entry = recorded,
        )

        assertEquals(8, result.reps)
        assertEquals(70.0, result.weight, 0.0)
    }

    @Test
    fun `missing entry falls back to the on-screen value`() {
        val result = mergeExitSets(
            built = prefillBuilt,
            touched = false,
            completed = false,
            entry = null,
        )

        assertEquals(10, result.reps)
    }

    @Test
    fun `mismatched entry type falls back to the on-screen value`() {
        val result = mergeExitSets(
            built = prefillBuilt,
            touched = false,
            completed = false,
            entry = ExerciseSet.Stretch(timeSeconds = 60, done = false),
        )

        assertEquals(10, result.reps)
        assertEquals(80.0, result.weight, 0.0)
    }

    @Test
    fun `stretch done flag restores the undone entry`() {
        val entry = ExerciseSet.Stretch(timeSeconds = 60, done = false)
        val result = mergeExitSets(
            built = ExerciseSet.Stretch(timeSeconds = 45, done = false),
            touched = false,
            completed = false,
            entry = entry,
        )

        assertEquals(60, result.timeSeconds)
    }
}
