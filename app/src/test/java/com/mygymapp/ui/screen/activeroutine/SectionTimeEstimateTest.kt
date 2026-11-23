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
    fun `session envelope surplus goes to strength work, never to a fixed stretch block`() {
        val current = listOf(
            active("bench", SessionExerciseCategory.NORMAL),
            active("stretch", SessionExerciseCategory.DAILY, ExerciseType.STRETCH, setCount = 3, timePerSetSeconds = 90),
        )
        val prior = previous(
            performed("bench", "2026-11-04T10:00:00", "2026-11-04T10:10:00"),
            startedAt = "2026-11-04T10:00:00",
            completedAt = "2026-11-04T10:20:00",
        )

        // Base: 600 measured strength + 270 configured stretch = 870. The 330s surplus is
        // strength work/rest, so the WORKOUT section absorbs it and the fixed DAILY stretch
        // stays exactly at its configured 3×90s.
        val estimate = estimateSectionSeconds(current, prior)
        assertEquals(930, estimate[SessionExerciseCategory.NORMAL])
        assertEquals(270, estimate[SessionExerciseCategory.DAILY])
        assertEquals(1200, estimate.values.sum())
    }

    @Test
    fun `a fixed cardio block is never inflated by a longer session envelope`() {
        val current = listOf(
            active("bike", SessionExerciseCategory.WARMUP, ExerciseType.CARDIO, timePerSetSeconds = 600),
        )
        val prior = previous(
            performed("bike", "2026-11-04T10:00:00", "2026-11-04T10:10:00", ExerciseType.CARDIO),
            startedAt = "2026-11-04T10:00:00",
            completedAt = "2026-11-04T11:00:00",
        )

        // The session lasted an hour, but the cardio block is fixed at 10 minutes: the extra
        // 50 minutes are rest/idle time and must not turn the WARMUP header into ~20m or ~60m.
        assertEquals(600, estimateSectionSeconds(current, prior)[SessionExerciseCategory.WARMUP])
    }

    @Test
    fun `a fixed stretch estimate never drops below its configured execution time`() {
        val current = listOf(
            active("stretch", SessionExerciseCategory.NORMAL, ExerciseType.STRETCH, setCount = 3, timePerSetSeconds = 90),
        )
        val prior = previous(
            performed(
                "stretch", "2026-11-04T10:00:00", "2026-11-04T10:01:00", ExerciseType.STRETCH,
                sets = List(3) { ExerciseSet.Stretch(timeSeconds = 90, done = true) },
            ),
        )

        // The measured window is only one minute (the screen was closed early), but three
        // 90-second holds take 4m30s: configured execution time is the floor.
        assertEquals(270, estimateSectionSeconds(current, prior)[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `performed strength slots without timestamps are estimated from the session envelope`() {
        val current = listOf(
            active("daily1", SessionExerciseCategory.DAILY, setCount = 3),
            active("daily2", SessionExerciseCategory.DAILY, setCount = 3),
            active("warmup", SessionExerciseCategory.WARMUP, ExerciseType.CARDIO, timePerSetSeconds = 600),
            active("normal", SessionExerciseCategory.NORMAL, setCount = 6),
        )
        val prior = previous(
            // Legacy session: completed work, but no per-slot startedAt/completedAt.
            performed("daily1", "", ""),
            performed("daily2", "", ""),
            performed("warmup", "", "", ExerciseType.CARDIO),
            performed("normal", "", ""),
            startedAt = "2026-11-04T10:00:00",
            completedAt = "2026-11-04T11:00:00",
        )

        val estimate = estimateSectionSeconds(current, prior)

        // 60-minute session − 10-minute fixed cardio = 50 minutes of strength work over 12
        // performed sets. The old code gave every strength slot 0 and inflated the cardio to
        // absorb the whole hour; now DAILY and WORKOUT split the real strength time evenly.
        assertEquals(600, estimate[SessionExerciseCategory.WARMUP])
        assertEquals(1500, estimate[SessionExerciseCategory.DAILY])
        assertEquals(1500, estimate[SessionExerciseCategory.NORMAL])
        assertEquals(3600, estimate.values.sum())
    }

    @Test
    fun `the same routine shape gets its own estimate from its own previous session`() {
        val current = listOf(active("bench", SessionExerciseCategory.NORMAL, setCount = 3))
        val short = previous(
            performed("bench", "2026-11-04T10:00:00", "2026-11-04T10:10:00"),
        )
        val long = previous(
            performed("bench", "2026-11-04T10:00:00", "2026-11-04T10:20:00"),
        )

        // Per-routine isolation: the estimate is driven by the routine's own previous session,
        // not by a global average shared across routines.
        assertEquals(600, estimateSectionSeconds(current, short)[SessionExerciseCategory.NORMAL])
        assertEquals(1200, estimateSectionSeconds(current, long)[SessionExerciseCategory.NORMAL])
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

    @Test
    fun `a strength slot skipped in the previous session contributes no time`() {
        val current = listOf(active("bench", SessionExerciseCategory.NORMAL))
        // completedEmpty=true: lo slot porta il pre-fill grigio ma nessun lavoro eseguito —
        // measuredDurationSeconds è null, e la forza non ha fallback configurato.
        val prior = previous(
            performed("bench", "2026-11-04T10:00:00", "2026-11-04T10:10:00").copy(completedEmpty = true),
        )

        assertNull(estimateSectionSeconds(current, prior)[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `a stretch slot skipped in the previous session falls back to the configured hold`() {
        val current = listOf(
            active("stretch", SessionExerciseCategory.NORMAL, ExerciseType.STRETCH, setCount = 3, timePerSetSeconds = 90),
        )
        // Skippato: nessuna finestra misurata, ma la stima resta "se lo fai, dura 3x90".
        val prior = previous(
            performed(
                "stretch", "2026-11-04T18:00:00", "2026-11-04T18:10:00", ExerciseType.STRETCH,
                sets = List(3) { ExerciseSet.Stretch(timeSeconds = 90, done = false) },
            ).copy(completedEmpty = true),
        )

        assertEquals(270, estimateSectionSeconds(current, prior)[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `daily estimate reflects only the daily slots present under this routine`() {
        // La routine ha filtrato via "mobility" (già presente nel workout): la stima DAILY
        // conta solo "breath" effettivamente in sezione, anche se la precedente conteneva entrambi.
        val current = listOf(
            active("breath", SessionExerciseCategory.DAILY, ExerciseType.STRETCH, setCount = 2, timePerSetSeconds = 60),
            active("bench", SessionExerciseCategory.NORMAL),
        )
        val prior = previous(
            performed("breath", "2026-11-04T08:00:00", "2026-11-04T08:04:00", ExerciseType.STRETCH),
            performed("mobility", "2026-11-04T08:04:00", "2026-11-04T08:09:00", ExerciseType.STRETCH),
            performed("bench", "2026-11-04T08:09:00", "2026-11-04T08:19:00"),
        )

        val estimate = estimateSectionSeconds(current, prior)

        assertEquals(240, estimate[SessionExerciseCategory.DAILY])
        assertEquals(600, estimate[SessionExerciseCategory.NORMAL])
    }

    @Test
    fun `per-exercise estimate matches the section total for singles`() {
        val current = listOf(
            active("bench", SessionExerciseCategory.NORMAL),
            active("row", SessionExerciseCategory.NORMAL),
        )
        val prior = previous(
            performed("bench", "2026-11-04T10:00:00", "2026-11-04T10:10:00"),
            performed("row", "2026-11-04T10:10:00", "2026-11-04T10:15:00"),
        )

        val perExercise = estimateExerciseSeconds(current, prior)

        assertEquals(600, perExercise["bench"])
        assertEquals(300, perExercise["row"])
        assertEquals(
            estimateSectionSeconds(current, prior)[SessionExerciseCategory.NORMAL],
            perExercise.values.sum(),
        )
    }

    @Test
    fun `per-exercise superset shares add up to the shared window`() {
        val current = listOf(
            active("a", SessionExerciseCategory.NORMAL, supersetWithNext = true),
            active("b", SessionExerciseCategory.NORMAL),
        )
        val prior = previous(
            performed("a", "2026-11-04T09:00:00", "2026-11-04T09:10:00"),
            performed("b", "2026-11-04T09:00:00", "2026-11-04T09:10:00"),
        )

        val perExercise = estimateExerciseSeconds(current, prior)

        // Equal set counts: the 10-minute shared window splits evenly.
        assertEquals(300, perExercise["a"])
        assertEquals(300, perExercise["b"])
        assertEquals(
            estimateSectionSeconds(current, prior)[SessionExerciseCategory.NORMAL],
            perExercise.values.sum(),
        )
    }

    @Test
    fun `per-exercise mixed superset keeps fixed time plus proportional strength`() {
        val current = listOf(
            active("bench", SessionExerciseCategory.NORMAL, setCount = 3, supersetWithNext = true),
            active(
                "stretch", SessionExerciseCategory.NORMAL, ExerciseType.STRETCH,
                setCount = 3, timePerSetSeconds = 90,
            ),
        )
        val prior = previous(
            performed("bench", "2026-11-04T09:00:00", "2026-11-04T09:10:00"),
            performed("stretch", "2026-11-04T09:00:00", "2026-11-04T09:10:00", ExerciseType.STRETCH),
        )

        val perExercise = estimateExerciseSeconds(current, prior)

        // 600s window, 270s of it fixed stretch holds: stretch keeps 270, strength gets 330.
        assertEquals(330, perExercise["bench"])
        assertEquals(270, perExercise["stretch"])
    }

    @Test
    fun `per-exercise estimate omits strength with no usable history`() {
        val current = listOf(
            active("bench", SessionExerciseCategory.NORMAL, setCount = 4),
            active(
                "stretch", SessionExerciseCategory.NORMAL, ExerciseType.STRETCH,
                setCount = 3, timePerSetSeconds = 90,
            ),
        )

        val perExercise = estimateExerciseSeconds(current, null)

        // Strength has no configured duration and no previous session: no number to show.
        // Stretch falls back to its configured holds.
        assertNull(perExercise["bench"])
        assertEquals(270, perExercise["stretch"])
    }

    @Test
    fun `per-exercise estimate omits a strength slot skipped last time`() {
        val current = listOf(active("bench", SessionExerciseCategory.NORMAL))
        val prior = previous(
            performed("bench", "2026-11-04T10:00:00", "2026-11-04T10:10:00").copy(completedEmpty = true),
        )

        assertNull(estimateExerciseSeconds(current, prior)["bench"])
    }
}
