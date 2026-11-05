package com.mygymapp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ExerciseTimingTest {

    private fun slot(
        id: String = "ex-1",
        type: ExerciseType = ExerciseType.FORZA,
        completed: Boolean = true,
        completedEmpty: Boolean = false,
        startedAt: String = "",
        completedAt: String = "",
        sets: List<ExerciseSet> = emptyList(),
    ) = WorkoutExercise(
        exerciseId = id,
        exerciseName = id,
        bodypart = "b",
        type = type,
        completed = completed,
        completedEmpty = completedEmpty,
        startedAt = startedAt,
        completedAt = completedAt,
        sets = sets,
    )

    @Test
    fun `measured duration uses the wall-clock exercise window`() {
        val ex = slot(
            startedAt = "2026-11-05T10:00:00",
            completedAt = "2026-11-05T10:02:30",
        )
        assertEquals(150, ex.measuredDurationSeconds())
    }

    @Test
    fun `measured duration includes the rest between sets by construction`() {
        // Two 60s holds separated by a 2-minute recovery pause: the window reports 240s, not
        // the 120s sum of the configured holds.
        val stretch = slot(
            type = ExerciseType.STRETCH,
            startedAt = "2026-11-05T18:00:00",
            completedAt = "2026-11-05T18:04:00",
            sets = listOf(
                ExerciseSet.Stretch(timeSeconds = 60, done = true),
                ExerciseSet.Stretch(timeSeconds = 60, done = true),
            ),
        )
        assertEquals(240, stretch.measuredDurationSeconds())
    }

    @Test
    fun `measured duration is null for skipped or still-open slots`() {
        val window = "2026-11-05T10:00:00" to "2026-11-05T10:10:00"
        assertNull(slot(completed = false, startedAt = window.first, completedAt = window.second).measuredDurationSeconds())
        assertNull(slot(completedEmpty = true, startedAt = window.first, completedAt = window.second).measuredDurationSeconds())
    }

    @Test
    fun `measured duration rejects missing invalid or reversed timestamps`() {
        assertNull(slot(startedAt = "", completedAt = "2026-11-05T10:00:00").measuredDurationSeconds())
        assertNull(slot(startedAt = "not-a-date", completedAt = "also-bad").measuredDurationSeconds())
        assertNull(
            slot(
                startedAt = "2026-11-05T10:10:00",
                completedAt = "2026-11-05T10:00:00",
            ).measuredDurationSeconds(),
        )
        assertNull(
            slot(
                startedAt = "2026-11-05T10:00:00",
                completedAt = "2026-11-05T10:00:00",
            ).measuredDurationSeconds(),
        )
    }

    @Test
    fun `cardio prefers its closed blocks over the screen window`() {
        val cardio = slot(
            type = ExerciseType.CARDIO,
            startedAt = "2026-11-05T09:50:00",
            completedAt = "2026-11-05T10:20:00", // 30 min at the screen
            sets = listOf(
                ExerciseSet.Cardio(
                    startedAt = "2026-11-05T10:00:00",
                    endedAt = "2026-11-05T10:10:00",
                ),
                ExerciseSet.Cardio(
                    startedAt = "2026-11-05T10:12:00",
                    endedAt = "2026-11-05T10:17:00",
                ),
            ),
        )
        assertEquals(900, cardio.measuredDurationSeconds())
    }

    @Test
    fun `measured session duration uses the whole session envelope`() {
        val session = WorkoutSession(
            id = "s", routineId = "rt", routineName = "R", date = "2026-11-05",
            startedAt = "2026-11-05T09:00:00",
            completedAt = "2026-11-05T09:45:30",
        )
        assertEquals(2730, session.measuredSessionDurationSeconds())
    }

    @Test
    fun `measured session duration rejects missing invalid or reversed envelopes`() {
        val base = WorkoutSession(id = "s", routineId = "rt", routineName = "R", date = "2026-11-05")
        assertNull(base.measuredSessionDurationSeconds())
        assertNull(base.copy(startedAt = "2026-11-05T09:00:00").measuredSessionDurationSeconds())
        assertNull(
            base.copy(
                startedAt = "2026-11-05T09:10:00",
                completedAt = "2026-11-05T09:00:00",
            ).measuredSessionDurationSeconds(),
        )
        assertNull(
            base.copy(
                startedAt = "not-a-date",
                completedAt = "also-bad",
            ).measuredSessionDurationSeconds(),
        )
    }

    @Test
    fun `starting a visit restarts the window and clears a stale completion`() {
        val session = WorkoutSession(
            id = "s", routineId = "rt", routineName = "R", date = "2026-11-05",
            exercises = listOf(
                slot(id = "open", completed = false, startedAt = "2026-11-05T09:00:00"),
                slot(id = "skipped", completedEmpty = true, startedAt = "2026-11-05T09:00:00", completedAt = "2026-11-05T09:05:00"),
                slot(id = "done", startedAt = "2026-11-05T08:00:00", completedAt = "2026-11-05T08:30:00"),
            ),
        )

        val result = session.withExercisesStarted(
            listOf("open", "skipped", "done"),
            "2026-11-05T11:00:00",
        )

        val open = result.exercises.first { it.exerciseId == "open" }
        assertEquals("2026-11-05T11:00:00", open.startedAt)
        assertEquals("", open.completedAt)

        val skipped = result.exercises.first { it.exerciseId == "skipped" }
        assertEquals("2026-11-05T11:00:00", skipped.startedAt)
        assertEquals("", skipped.completedAt)

        // A performed exercise is never reopened; its original window must survive defensively.
        val done = result.exercises.first { it.exerciseId == "done" }
        assertEquals("2026-11-05T08:00:00", done.startedAt)
        assertEquals("2026-11-05T08:30:00", done.completedAt)
    }

    @Test
    fun `starting an unknown id returns the same session`() {
        val session = WorkoutSession(id = "s", routineId = "rt", routineName = "R", date = "2026-11-05")
        assertSame(session, session.withExerciseStarted("ex-missing", "2026-11-05T11:00:00"))
    }
}
