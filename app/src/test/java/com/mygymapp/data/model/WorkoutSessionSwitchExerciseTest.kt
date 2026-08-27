package com.mygymapp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the "Switch exercise" mutation (see docs/CONVENTIONS.md#switch-exercise,
 * commit c871f09) and its eligibility guards. This logic is shared by ActiveRoutineViewModel
 * and three exercise ViewModels, so a regression here silently breaks mid-session substitution
 * everywhere at once.
 */
class WorkoutSessionSwitchExerciseTest {

    private val untouchedSlot = WorkoutExercise(
        exerciseId = "ex-original",
        exerciseName = "Bench Press",
        bodypart = "chest",
        type = ExerciseType.FORZA,
        sets = listOf(ExerciseSet.Strength()),
    )

    private val newExercise = Exercise(
        id = "ex-newone01",
        name = "Incline Press",
        type = ExerciseType.FORZA,
        bodypart = "chest",
    )

    @Test
    fun `switching an untouched slot replaces id, name, bodypart, type and records substitutedFor`() {
        val session = WorkoutSession(
            id = "s1", routineId = "rt-1", routineName = "Push", date = "2026-08-25",
            exercises = listOf(untouchedSlot),
        )

        val result = session.withExerciseSwitched("ex-original", newExercise)

        val slot = result.exercises.single()
        assertEquals("ex-newone01", slot.exerciseId)
        assertEquals("Incline Press", slot.exerciseName)
        assertEquals("chest", slot.bodypart)
        assertEquals(ExerciseType.FORZA, slot.type)
        assertEquals("ex-original", slot.substitutedFor)
    }

    @Test
    fun `switching regenerates empty sets matching the new exercise count and type`() {
        val slot = untouchedSlot.copy(
            sets = listOf(ExerciseSet.Strength(), ExerciseSet.Strength(), ExerciseSet.Strength()),
        )
        val session = WorkoutSession(
            id = "s1", routineId = "rt-1", routineName = "Push", date = "2026-08-25",
            exercises = listOf(slot),
        )

        val result = session.withExerciseSwitched("ex-original", newExercise)

        val sets = result.exercises.single().sets
        assertEquals(3, sets.size)
        assertTrue(sets.all { it == ExerciseSet.Strength() })
    }

    @Test
    fun `switching to a bodyweight exercise marks the regenerated sets bodyweight`() {
        val bodyweightExercise = newExercise.copy(isBodyweight = true)
        val session = WorkoutSession(
            id = "s1", routineId = "rt-1", routineName = "Push", date = "2026-08-25",
            exercises = listOf(untouchedSlot),
        )

        val result = session.withExerciseSwitched("ex-original", bodyweightExercise)

        val set = result.exercises.single().sets.single() as ExerciseSet.Strength
        assertTrue(set.isBodyweight)
    }

    @Test
    fun `already-completed slot is not eligible and session is returned unchanged`() {
        val completedSlot = untouchedSlot.copy(completed = true)
        val session = WorkoutSession(
            id = "s1", routineId = "rt-1", routineName = "Push", date = "2026-08-25",
            exercises = listOf(completedSlot),
        )

        val result = session.withExerciseSwitched("ex-original", newExercise)

        assertSame(session, result)
    }

    @Test
    fun `slot with recorded data is not eligible and session is returned unchanged`() {
        val touchedSlot = untouchedSlot.copy(sets = listOf(ExerciseSet.Strength(reps = 5, weight = 40.0)))
        val session = WorkoutSession(
            id = "s1", routineId = "rt-1", routineName = "Push", date = "2026-08-25",
            exercises = listOf(touchedSlot),
        )

        val result = session.withExerciseSwitched("ex-original", newExercise)

        assertSame(session, result)
    }

    @Test
    fun `slot already switched once cannot be switched again`() {
        val alreadySwitched = untouchedSlot.copy(substitutedFor = "ex-evenolder")
        val session = WorkoutSession(
            id = "s1", routineId = "rt-1", routineName = "Push", date = "2026-08-25",
            exercises = listOf(alreadySwitched),
        )

        val result = session.withExerciseSwitched("ex-original", newExercise)

        assertSame(session, result)
    }

    @Test
    fun `switching a nonexistent slot id returns session unchanged`() {
        val session = WorkoutSession(
            id = "s1", routineId = "rt-1", routineName = "Push", date = "2026-08-25",
            exercises = listOf(untouchedSlot),
        )

        val result = session.withExerciseSwitched("ex-does-not-exist", newExercise)

        assertSame(session, result)
    }

    @Test
    fun `hasNoRecordedSets is false for a strength set with reps but zero weight`() {
        val slot = untouchedSlot.copy(sets = listOf(ExerciseSet.Strength(reps = 5, weight = 0.0)))
        assertFalse(slot.hasNoRecordedSets())
    }

    @Test
    fun `hasNoRecordedSets is true for an empty strength set list`() {
        val slot = untouchedSlot.copy(sets = listOf(ExerciseSet.Strength()))
        assertTrue(slot.hasNoRecordedSets())
    }

    @Test
    fun `isUntouched keys off completed only, ignoring set contents`() {
        // Since the untouched-exercise guard change, "Complete without touching" saves
        // completed = false but keeps the grey pre-fill in `sets`. isUntouched() must still
        // report such an exercise as not-performed — it keys off `completed` alone, not on
        // whether `sets` is empty or carries pre-filled numbers.
        val neverOpened = untouchedSlot.copy(sets = emptyList())
        assertTrue(neverOpened.isUntouched())

        val completedWithoutTouching = untouchedSlot.copy(
            completed = false,
            sets = listOf(ExerciseSet.Strength(reps = 13, weight = 16.0)),
        )
        assertTrue(completedWithoutTouching.isUntouched())

        val completedEmptySlot = untouchedSlot.copy(completed = true, completedEmpty = true, sets = emptyList())
        assertFalse(completedEmptySlot.isUntouched())

        val reallyDone = untouchedSlot.copy(
            completed = true,
            sets = listOf(ExerciseSet.Strength(reps = 13, weight = 16.0)),
        )
        assertFalse(reallyDone.isUntouched())
    }

    @Test
    fun `switch stretch exercise defaults regenerated sets to 60 seconds`() {
        val stretchNew = newExercise.copy(type = ExerciseType.STRETCH)
        val slot = untouchedSlot.copy(
            type = ExerciseType.STRETCH,
            sets = listOf(ExerciseSet.Stretch(timeSeconds = 30)),
        )
        val session = WorkoutSession(
            id = "s1", routineId = "rt-1", routineName = "Mobility", date = "2026-08-25",
            exercises = listOf(slot),
        )

        val result = session.withExerciseSwitched("ex-original", stretchNew)

        val stretch = result.exercises.single().sets.single() as ExerciseSet.Stretch
        assertEquals(60, stretch.timeSeconds)
        assertFalse(stretch.done)
    }

    @Test
    fun `switch to cardio exercise clears sets entirely`() {
        val cardioNew = newExercise.copy(type = ExerciseType.CARDIO)
        val session = WorkoutSession(
            id = "s1", routineId = "rt-1", routineName = "Push", date = "2026-08-25",
            exercises = listOf(untouchedSlot),
        )

        val result = session.withExerciseSwitched("ex-original", cardioNew)

        assertTrue(result.exercises.single().sets.isEmpty())
    }
}
