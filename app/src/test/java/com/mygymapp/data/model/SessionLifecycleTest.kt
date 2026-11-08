package com.mygymapp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the explicit session/slot lifecycles (ISM-01/ISM-02):
 * [WorkoutSession.lifecycle] + [WorkoutExercise.slotLifecycle] and their
 * transition guards. The disk format is unchanged — these are pure views over
 * the existing fields — so every test below must hold for sessions read from
 * real `.md` files, not just constructed ones.
 */
class SessionLifecycleTest {

    private fun session(
        completedAt: String = "",
        sessionRpe: Int? = null,
        exercises: List<WorkoutExercise> = emptyList(),
    ) = WorkoutSession(
        id = "s1",
        routineId = "rt-1",
        routineName = "Push",
        date = "2026-08-25",
        completedAt = completedAt,
        sessionRpe = sessionRpe,
        exercises = exercises,
    )

    private fun slot(
        completed: Boolean = false,
        completedEmpty: Boolean = false,
        substitutedFor: String? = null,
        sets: List<ExerciseSet> = listOf(ExerciseSet.Strength()),
    ) = WorkoutExercise(
        exerciseId = "ex-aaaabbbb",
        exerciseName = "Bench press",
        bodypart = "chest",
        type = ExerciseType.FORZA,
        completed = completed,
        completedEmpty = completedEmpty,
        substitutedFor = substitutedFor,
        sets = sets,
    )

    // ─── Session envelope ───

    @Test
    fun `blank completedAt is Ghost no matter how much was logged`() {
        // Explicit product decision (Phase 97): completed exercises do not rescue
        // an unfinalized session.
        val withWork = session(
            exercises = listOf(slot(completed = true, sets = listOf(ExerciseSet.Strength(reps = 5, weight = 40.0)))),
        )
        assertTrue(withWork.lifecycle() is SessionLifecycle.Ghost)
        assertTrue(withWork.lifecycle().isGhost)
    }

    @Test
    fun `stamped completedAt without RPE is FinalizedUnregistered`() {
        val state = session(completedAt = "2026-08-25T10:00:00").lifecycle()
        assertEquals(SessionLifecycle.FinalizedUnregistered, state)
        assertTrue(state.isFinalizedOnDisk)
    }

    @Test
    fun `stamped completedAt with RPE is Registered carrying the rating`() {
        val state = session(completedAt = "2026-08-25T10:00:00", sessionRpe = 7).lifecycle()
        assertEquals(SessionLifecycle.Registered(7), state)
        assertTrue(state.isFinalizedOnDisk)
    }

    @Test
    fun `only Ghost can finalize, only FinalizedUnregistered can register`() {
        assertTrue(session().lifecycle().canFinalize())
        assertFalse(session(completedAt = "2026-08-25T10:00:00").lifecycle().canFinalize())
        assertFalse(session(completedAt = "2026-08-25T10:00:00", sessionRpe = 7).lifecycle().canFinalize())

        assertTrue(session(completedAt = "2026-08-25T10:00:00").lifecycle().canRegister())
        assertFalse(session().lifecycle().canRegister())
        assertFalse(session(completedAt = "2026-08-25T10:00:00", sessionRpe = 7).lifecycle().canRegister())
    }

    // ─── Slot states ───

    @Test
    fun `open performed skipped map from the completion flags`() {
        assertTrue(slot().slotLifecycle() is SlotLifecycle.Open)
        assertTrue(slot(completed = true).slotLifecycle() is SlotLifecycle.Performed)
        assertTrue(slot(completed = true, completedEmpty = true).slotLifecycle() is SlotLifecycle.Skipped)
    }

    @Test
    fun `illegal completed-false plus completedEmpty-true degrades to Open`() {
        // Invariant "empty ⇒ completed" violated on disk: treat as open, like
        // every previous reader did (isUntouched was true for it).
        val illegal = slot(completed = false, completedEmpty = true)
        assertTrue(illegal.slotLifecycle() is SlotLifecycle.Open)
        assertTrue(illegal.isUntouched())
    }

    @Test
    fun `switch origin survives the mapping`() {
        assertEquals("ex-old", slot(substitutedFor = "ex-old").slotLifecycle().switchedFrom)
        assertEquals("ex-old", slot(completed = true, substitutedFor = "ex-old").slotLifecycle().switchedFrom)
    }

    @Test
    fun `only Performed represents performed work`() {
        assertFalse(slot().slotLifecycle().representsPerformedWork)
        assertFalse(slot(completed = true, completedEmpty = true).slotLifecycle().representsPerformedWork)
        assertTrue(slot(completed = true).slotLifecycle().representsPerformedWork)
    }

    @Test
    fun `complete is allowed from Open and Skipped, never from Performed`() {
        assertTrue(slot().slotLifecycle().canComplete())
        assertTrue(slot(completed = true, completedEmpty = true).slotLifecycle().canComplete())
        assertFalse(slot(completed = true).slotLifecycle().canComplete())
    }

    @Test
    fun `switch needs Open plus unswitched plus no recorded sets`() {
        val open = slot().slotLifecycle()
        assertTrue(open.canSwitch(hasNoRecordedSets = true))
        assertFalse(open.canSwitch(hasNoRecordedSets = false))

        assertFalse(slot(substitutedFor = "ex-old").slotLifecycle().canSwitch(hasNoRecordedSets = true))
        assertFalse(slot(completed = true).slotLifecycle().canSwitch(hasNoRecordedSets = true))
        assertFalse(
            slot(completed = true, completedEmpty = true).slotLifecycle().canSwitch(hasNoRecordedSets = true),
        )
    }

    @Test
    fun `isSwitchEligible still matches the lifecycle guard`() {
        assertTrue(slot().isSwitchEligible())
        assertFalse(
            slot(sets = listOf(ExerciseSet.Strength(reps = 5, weight = 40.0))).isSwitchEligible(),
        )
        assertFalse(slot(completed = true).isSwitchEligible())
        assertFalse(slot(substitutedFor = "ex-old").isSwitchEligible())
        assertFalse(slot(completed = true, completedEmpty = true).isSwitchEligible())
    }

    // ─── Session-level close rule ───

    @Test
    fun `isAllClosed counts performed and skipped, not open`() {
        assertTrue(session(exercises = emptyList()).isAllClosed())
        assertTrue(
            session(exercises = listOf(slot(completed = true), slot(completed = true, completedEmpty = true))).isAllClosed(),
        )
        assertFalse(
            session(exercises = listOf(slot(completed = true), slot())).isAllClosed(),
        )
    }

    @Test
    fun `withExercisesStarted reopens Skipped but never Performed`() {
        val original = session(
            exercises = listOf(
                slot(completed = true).copy(exerciseId = "ex-done"),
                slot(completed = true, completedEmpty = true).copy(exerciseId = "ex-skip"),
                slot().copy(exerciseId = "ex-open"),
            ),
        )
        val result = original.withExercisesStarted(listOf("ex-done", "ex-skip", "ex-open"), "2026-08-25T10:00:00")

        val done = result.exercises.single { it.exerciseId == "ex-done" }
        assertTrue(done.completed)
        assertTrue(done.startedAt.isBlank())

        val skipped = result.exercises.single { it.exerciseId == "ex-skip" }
        assertEquals("2026-08-25T10:00:00", skipped.startedAt)
        assertTrue(skipped.completedAt.isBlank())

        val open = result.exercises.single { it.exerciseId == "ex-open" }
        assertEquals("2026-08-25T10:00:00", open.startedAt)
    }

    @Test
    fun `switched slot lifecycle is preserved through withExerciseSwitched guard`() {
        val alreadySwitched = session(exercises = listOf(slot(substitutedFor = "ex-evenolder")))
        val newExercise = Exercise(id = "ex-new", name = "New", type = ExerciseType.FORZA, bodypart = "chest")
        val result = alreadySwitched.withExerciseSwitched("ex-aaaabbbb", newExercise, allowDiscardRecordedSets = true)
        // Guarded by SlotLifecycle.Open + unswitched: already-switched is a no-op returning the same instance.
        assertTrue(result === alreadySwitched)
    }
}
