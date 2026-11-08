package com.mygymapp.data.repository

import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Pins the explicit maintenance verdict (ISM-29):
 * [decideMaintenanceAction]. Each row mirrors one path of the old inline
 * `isPruneCandidate || isGhost` loop in `WorkoutRepository.runMaintenance`,
 * so any divergence from historical behavior fails here first.
 */
class MaintenanceActionTest {

    private val cutoff = LocalDate.parse("2026-08-01")
    private val oldDate = LocalDate.parse("2026-05-10")
    private val newDate = LocalDate.parse("2026-08-20")

    private fun slot(id: String) = WorkoutExercise(
        exerciseId = id,
        exerciseName = "Bench press",
        bodypart = "chest",
        type = ExerciseType.FORZA,
        sets = listOf(ExerciseSet.Strength(reps = 8, weight = 30.0)),
    )

    private fun session(completedAt: String) = WorkoutSession(
        id = "s1",
        routineId = "rt-1",
        routineName = "Push",
        date = "2026-08-20",
        completedAt = completedAt,
        exercises = listOf(slot("ex-aaaabbbb"), slot("ex-ccccdddd")),
    )

    @Test
    fun `unparseable filename date is discarded outright whatever the content`() {
        for (s in listOf(null, session(""), session("2026-08-20T10:00:00"))) {
            assertEquals(
                MaintenanceAction.DeleteMalformedName,
                decideMaintenanceAction(null, s, cutoff),
            )
        }
    }

    @Test
    fun `ghost is deleted on any date and never affects stats`() {
        for (date in listOf(oldDate, newDate, cutoff)) {
            val action = decideMaintenanceAction(date, session(""), cutoff)
            assertTrue(action is MaintenanceAction.Delete)
            action as MaintenanceAction.Delete
            assertEquals(MaintenanceDeleteKind.GHOST, action.kind)
            assertFalse(action.affectsStats)
            assertEquals(setOf("ex-aaaabbbb", "ex-ccccdddd"), action.exerciseIds)
        }
    }

    @Test
    fun `unparseable content is pruned only when older than cutoff, never affecting stats`() {
        val pruned = decideMaintenanceAction(oldDate, null, cutoff)
        assertTrue(pruned is MaintenanceAction.Delete)
        pruned as MaintenanceAction.Delete
        assertEquals(MaintenanceDeleteKind.PRUNED, pruned.kind)
        assertFalse(pruned.affectsStats)
        assertTrue(pruned.exerciseIds.isEmpty())

        assertEquals(MaintenanceAction.Keep, decideMaintenanceAction(newDate, null, cutoff))
    }

    @Test
    fun `completed session is pruned only when older than cutoff, affecting stats`() {
        val done = session("2026-05-10T10:00:00")
        val pruned = decideMaintenanceAction(oldDate, done, cutoff)
        assertTrue(pruned is MaintenanceAction.Delete)
        pruned as MaintenanceAction.Delete
        assertEquals(MaintenanceDeleteKind.PRUNED, pruned.kind)
        assertTrue(pruned.affectsStats)
        assertEquals(setOf("ex-aaaabbbb", "ex-ccccdddd"), pruned.exerciseIds)

        assertEquals(MaintenanceAction.Keep, decideMaintenanceAction(newDate, done, cutoff))
    }

    @Test
    fun `cutoff day itself is not pruned`() {
        // isBefore is exclusive: a session dated exactly the cutoff stays.
        assertEquals(
            MaintenanceAction.Keep,
            decideMaintenanceAction(cutoff, session("2026-05-10T10:00:00"), cutoff),
        )
    }
}
