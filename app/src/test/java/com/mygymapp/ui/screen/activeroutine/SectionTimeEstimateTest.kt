package com.mygymapp.ui.screen.activeroutine

import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionTimeEstimateTest {

    private fun active(
        id: String,
        category: SessionExerciseCategory,
        type: ExerciseType = ExerciseType.FORZA,
        setCount: Int = 3,
        timePerSetSeconds: Int = 0,
        supersetWithNext: Boolean = false,
    ) = ActiveExerciseUi(
        exerciseId = id,
        exerciseName = id,
        type = type,
        bodypart = "b",
        setCount = setCount,
        timePerSetSeconds = timePerSetSeconds,
        category = category,
        supersetWithNext = supersetWithNext,
    )

    private fun performed(
        id: String,
        startedAt: String,
        completedAt: String,
        type: ExerciseType = ExerciseType.FORZA,
        sets: List<ExerciseSet> = emptyList(),
    ) = WorkoutExercise(
        exerciseId = id,
        exerciseName = id,
        bodypart = "b",
        type = type,
        completed = true,
        startedAt = startedAt,
        completedAt = completedAt,
        sets = sets,
    )

    private fun previous(
        vararg exercises: WorkoutExercise,
        startedAt: String = "",
        completedAt: String = "2026-11-04T12:00:00",
    ) = WorkoutSession(
        id = "prev",
        routineId = "rt",
        routineName = "R",
        date = "2026-11-04",
        startedAt = startedAt,
        completedAt = completedAt,
        exercises = exercises.toList(),
    )

    @Test
    fun `previous measured windows produce one estimate per section`() {
        val current = listOf(
            active("daily", SessionExerciseCategory.DAILY),
            active("warmup", SessionExerciseCategory.WARMUP),
            active("n1", SessionExerciseCategory.NORMAL),
            active("n2", SessionExerciseCategory.NORMAL, ExerciseType.STRETCH),
        )
        val prior = previous(
            performed("daily", "2026-11-04T08:00:00", "2026-11-04T08:05:00"),
            performed("warmup", "2026-11-04T08:05:00", "2026-11-04T08:07:00"),
            performed("n1", "2026-11-04T08:07:00", "2026-11-04T08:17:00"),
            performed("n2", "2026-11-04T08:17:00", "2026-11-04T08:22:00", ExerciseType.STRETCH),
        )

        val estimate = estimateSectionSeconds(current, prior)

        assertEquals(300, estimate[SessionExerciseCategory.DAILY])
        assertEquals(120, estimate[SessionExerciseCategory.WARMUP])
        assertEquals(900, estimate[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `stretch estimate counts holds plus recovery rest, not the configured hold sum`() {
        val current = listOf(
            active("stretch", SessionExerciseCategory.NORMAL, ExerciseType.STRETCH, setCount = 3, timePerSetSeconds = 90),
        )
        // Three 90s holds total 4m30s, but the real window is 10 minutes: the estimate must
        // show the wall-clock time actually spent, recovery pauses included.
        val prior = previous(
            performed(
                "stretch", "2026-11-04T18:00:00", "2026-11-04T18:10:00", ExerciseType.STRETCH,
                sets = List(3) { ExerciseSet.Stretch(timeSeconds = 90, done = true) },
            ),
        )

        assertEquals(600, estimateSectionSeconds(current, prior)[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `configured durations are the fallback when there is no timed history`() {
        val current = listOf(
            active("strength", SessionExerciseCategory.NORMAL, ExerciseType.FORZA, setCount = 4),
            active("stretch", SessionExerciseCategory.NORMAL, ExerciseType.STRETCH, setCount = 3, timePerSetSeconds = 90),
            active("bike", SessionExerciseCategory.NORMAL, ExerciseType.CARDIO, timePerSetSeconds = 600),
        )

        // Strength has no configured duration; stretch 3x90 + cardio 10m fill the estimate.
        assertEquals(270 + 600, estimateSectionSeconds(current, null)[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `a strength-only section has no estimate until a timed session exists`() {
        val current = listOf(
            active("bench", SessionExerciseCategory.NORMAL, ExerciseType.FORZA, setCount = 4),
        )

        assertNull(estimateSectionSeconds(current, null)[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `session envelope tops up undercounted slot windows proportionally`() {
        val current = listOf(
            active("bench", SessionExerciseCategory.NORMAL),
            active("row", SessionExerciseCategory.NORMAL),
        )
        // Each screen was opened after 5 minutes of work and closed 5 minutes later: the sum
        // of the measured windows is 10 minutes, but the session really lasted 20.
        val prior = previous(
            performed("bench", "2026-11-04T10:00:00", "2026-11-04T10:05:00"),
            performed("row", "2026-11-04T10:05:00", "2026-11-04T10:10:00"),
            startedAt = "2026-11-04T10:00:00",
            completedAt = "2026-11-04T10:20:00",
        )

        // The 10 unaccounted minutes are split evenly because the two groups have equal weight.
        assertEquals(1200, estimateSectionSeconds(current, prior)[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `session envelope also scales configured fallbacks`() {
        val current = listOf(
            active("bench", SessionExerciseCategory.NORMAL),
            active("stretch", SessionExerciseCategory.DAILY, ExerciseType.STRETCH, setCount = 3, timePerSetSeconds = 90),
        )
        val prior = previous(
            performed("bench", "2026-11-04T10:00:00", "2026-11-04T10:10:00"),
            startedAt = "2026-11-04T10:00:00",
            completedAt = "2026-11-04T10:20:00",
        )

        // Base: 600 measured + 270 configured = 870. The 20-minute session scales both by
        // 1200/870, so the two section estimates still add up to the real duration.
        val estimate = estimateSectionSeconds(current, prior)
        assertEquals(828, estimate[SessionExerciseCategory.NORMAL])
        assertEquals(372, estimate[SessionExerciseCategory.DAILY])
        assertEquals(1200, estimate.values.sum())
    }

    @Test
    fun `a wildly longer session envelope is capped at double`() {
        val current = listOf(active("bench", SessionExerciseCategory.NORMAL))
        val prior = previous(
            performed("bench", "2026-11-04T10:00:00", "2026-11-04T10:10:00"),
            startedAt = "2026-11-04T10:00:00",
            completedAt = "2026-11-04T16:00:00",
        )

        // A session left open for six hours must not turn a 10-minute slot into a 6-hour
        // estimate: the reconciliation stops at 2× the measured sum.
        assertEquals(1200, estimateSectionSeconds(current, prior)[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `a shorter session envelope never shrinks the measured sum`() {
        val current = listOf(active("bench", SessionExerciseCategory.NORMAL))
        val prior = previous(
            performed("bench", "2026-11-04T10:00:00", "2026-11-04T10:10:00"),
            startedAt = "2026-11-04T10:00:00",
            completedAt = "2026-11-04T10:05:00",
        )

        assertEquals(600, estimateSectionSeconds(current, prior)[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `superset members share their window and are counted once`() {
        val current = listOf(
            active("a", SessionExerciseCategory.NORMAL, supersetWithNext = true),
            active("b", SessionExerciseCategory.NORMAL),
        )
        val prior = previous(
            performed("a", "2026-11-04T09:00:00", "2026-11-04T09:10:00"),
            performed("b", "2026-11-04T09:00:00", "2026-11-04T09:10:00"),
        )

        assertEquals(600, estimateSectionSeconds(current, prior)[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `exercise ids in supersets covers every member but no single`() {
        val current = listOf(
            active("a", SessionExerciseCategory.NORMAL, supersetWithNext = true),
            active("b", SessionExerciseCategory.NORMAL),
            active("c", SessionExerciseCategory.NORMAL),
            active("d", SessionExerciseCategory.NORMAL, supersetWithNext = true),
            active("e", SessionExerciseCategory.NORMAL),
        )

        val ids = exerciseIdsInSupersets(current)

        assertEquals(setOf("a", "b", "d", "e"), ids)
        assertTrue("c" !in ids)
    }
}
