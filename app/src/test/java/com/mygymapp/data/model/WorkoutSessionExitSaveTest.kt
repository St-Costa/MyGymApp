package com.mygymapp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [WorkoutSession.withExerciseSetsOnExit], the back-navigation write that
 * replaces a slot's sets while preserving its entry completion flags (see
 * docs/CONVENTIONS.md "Re-entering a skipped exercise").
 */
class WorkoutSessionExitSaveTest {

    private fun slot(completed: Boolean = false, completedEmpty: Boolean = false) = WorkoutExercise(
        exerciseId = "ex-aaaabbbb",
        exerciseName = "Bench press",
        bodypart = "chest",
        type = ExerciseType.FORZA,
        completed = completed,
        completedEmpty = completedEmpty,
        sets = listOf(ExerciseSet.Strength(reps = 8, weight = 30.0)),
    )

    private fun session(vararg exercises: WorkoutExercise) = WorkoutSession(
        id = "s1",
        routineId = "rt-11223344",
        routineName = "Push",
        date = "2026-08-25",
        exercises = exercises.toList(),
    )

    @Test
    fun `an open slot stays open and gets the new sets`() {
        val result = session(slot()).withExerciseSetsOnExit(
            "ex-aaaabbbb",
            listOf(ExerciseSet.Strength(reps = 10, weight = 40.0)),
        )

        val updated = result.exercises.single()
        assertFalse(updated.completed)
        assertFalse(updated.completedEmpty)
        assertEquals(listOf(ExerciseSet.Strength(reps = 10, weight = 40.0)), updated.sets)
    }

    @Test
    fun `a skipped slot stays skipped and gets the new sets`() {
        val result = session(slot(completed = true, completedEmpty = true)).withExerciseSetsOnExit(
            "ex-aaaabbbb",
            listOf(ExerciseSet.Strength(reps = 8, weight = 35.0)),
        )

        val updated = result.exercises.single()
        assertTrue(updated.completed)
        assertTrue(updated.completedEmpty)
        assertEquals(listOf(ExerciseSet.Strength(reps = 8, weight = 35.0)), updated.sets)
    }

    @Test
    fun `unknown exercise id returns the same session instance`() {
        val original = session(slot())
        assertSame(original, original.withExerciseSetsOnExit("ex-zzzz9999", emptyList()))
    }
}
