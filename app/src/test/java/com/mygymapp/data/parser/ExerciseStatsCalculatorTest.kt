package com.mygymapp.data.parser

import com.mygymapp.data.model.ContextStats
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseStats
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.PreviousSet
import com.mygymapp.data.model.SlotContext
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calculator must reproduce, from the sidecar, exactly what StrengthExerciseViewModel /
 * SupersetViewModel computed inline before: "previous" = the most recent (by completedAt)
 * matching-context session with a real set; PR = the single highest reps*weight set ever, in
 * that context. And [ExerciseStatsCalculator.merge] must agree with
 * [ExerciseStatsCalculator.rebuild] fed the same sessions.
 */
class ExerciseStatsCalculatorTest {

    private val EX = "ex-11112222"

    private fun strengthSession(
        completedAt: String,
        sets: List<Pair<Int, Double>>,
        ctx: SlotContext = SlotContext.NORMAL,
        exerciseId: String = EX,
        date: String = completedAt.take(10).ifBlank { "2026-01-01" },
    ) = WorkoutSession(
        id = completedAt.ifBlank { "x" },
        routineId = "rt-aaaa1111",
        routineName = "R",
        date = date,
        completedAt = completedAt,
        exercises = listOf(
            WorkoutExercise(
                exerciseId = exerciseId,
                exerciseName = "Bench",
                bodypart = "chest",
                type = ExerciseType.FORZA,
                completed = true,
                excludeFromTonnage = ctx != SlotContext.NORMAL,
                isDaily = ctx == SlotContext.DAILY,
                sets = sets.map { (r, w) -> ExerciseSet.Strength(reps = r, weight = w) },
            ),
        ),
    )

    @Test
    fun `no sessions yields empty stats`() {
        val stats = ExerciseStatsCalculator.rebuild(EX, emptyList())
        assertEquals(emptyMap<SlotContext, Any>(), stats.perContext)
    }

    @Test
    fun `non-completed sessions are ignored`() {
        val open = strengthSession("", listOf(10 to 50.0))
        val stats = ExerciseStatsCalculator.rebuild(EX, listOf(open))
        assertNull(stats.forContext(SlotContext.NORMAL))
    }

    @Test
    fun `previous is the most recent session with real data`() {
        val sessions = listOf(
            strengthSession("2026-08-01T10:00:00", listOf(8 to 60.0, 8 to 60.0)),
            strengthSession("2026-08-10T10:00:00", listOf(8 to 65.0, 8 to 65.0)),
            // most recent, but all-zero → must be skipped for "previous"
            strengthSession("2026-08-20T10:00:00", listOf(0 to 0.0, 0 to 0.0)),
        )
        val ctx = ExerciseStatsCalculator.rebuild(EX, sessions).forContext(SlotContext.NORMAL)!!
        // Stored day-only (see ExerciseStatsCalculator.dayKey) so merge/rebuild compare like-for-like.
        assertEquals("2026-08-10", ctx.previousSessionDate)
        assertEquals(listOf(PreviousSet(8, 65.0), PreviousSet(8, 65.0)), ctx.previousSets)
    }

    @Test
    fun `pr is the single highest tonnage set across all history`() {
        val sessions = listOf(
            strengthSession("2026-08-01T10:00:00", listOf(10 to 40.0, 5 to 80.0)), // best 400
            strengthSession("2026-08-10T10:00:00", listOf(8 to 60.0)),             // best 480
            strengthSession("2026-08-20T10:00:00", listOf(6 to 70.0)),             // best 420
        )
        val ctx = ExerciseStatsCalculator.rebuild(EX, sessions).forContext(SlotContext.NORMAL)!!
        assertEquals(PreviousSet(8, 60.0), ctx.pr)
        assertTrue(ctx.hasPriorRealTonnage)
    }

    @Test
    fun `rmPr is the highest estimated-1RM set, which can differ from the tonnage PR`() {
        val sessions = listOf(
            // tonnage 480 (best by tonnage), e1RM = 60*(1+8/30) = 76
            strengthSession("2026-08-01T10:00:00", listOf(8 to 60.0)),
            // tonnage 400 (lower), e1RM = 100*(1+2/30) ≈ 106.7 (best by e1RM)
            strengthSession("2026-08-10T10:00:00", listOf(2 to 100.0)),
        )
        val ctx = ExerciseStatsCalculator.rebuild(EX, sessions).forContext(SlotContext.NORMAL)!!
        assertEquals(PreviousSet(8, 60.0), ctx.pr)
        assertEquals(PreviousSet(2, 100.0), ctx.rmPr)
    }

    @Test
    fun `merge tracks rmPr independently of the tonnage PR`() {
        val base = ExerciseStatsCalculator.rebuild(
            EX,
            listOf(strengthSession("2026-08-01T10:00:00", listOf(10 to 80.0))), // tonnage 800, e1RM ≈ 106.7
        )
        // tonnage 500 < 800 so pr is unchanged, but e1RM 130*(1+1/30) ≈ 134.3 > 106.7 → new rmPr
        val newSession = strengthSession("2026-08-05T10:00:00", listOf(1 to 130.0))
        val ctx = ExerciseStatsCalculator.merge(EX, base, newSession).forContext(SlotContext.NORMAL)!!
        assertEquals(PreviousSet(10, 80.0), ctx.pr)
        assertEquals(PreviousSet(1, 130.0), ctx.rmPr)
    }

    @Test
    fun `contexts are kept separate`() {
        val sessions = listOf(
            strengthSession("2026-08-01T10:00:00", listOf(8 to 60.0), ctx = SlotContext.NORMAL),
            strengthSession("2026-08-02T10:00:00", listOf(15 to 20.0), ctx = SlotContext.DAILY),
            strengthSession("2026-08-03T10:00:00", listOf(12 to 30.0), ctx = SlotContext.WARMUP),
        )
        val stats = ExerciseStatsCalculator.rebuild(EX, sessions)
        assertEquals(PreviousSet(8, 60.0), stats.forContext(SlotContext.NORMAL)!!.pr)
        assertEquals(PreviousSet(15, 20.0), stats.forContext(SlotContext.DAILY)!!.pr)
        assertEquals(PreviousSet(12, 30.0), stats.forContext(SlotContext.WARMUP)!!.pr)
    }

    @Test
    fun `merge of a new PR session updates pr but keeps older previous when new session weaker per-set ordering`() {
        val base = ExerciseStatsCalculator.rebuild(
            EX,
            listOf(strengthSession("2026-08-01T10:00:00", listOf(8 to 60.0, 8 to 60.0))),
        )
        val newSession = strengthSession("2026-08-05T10:00:00", listOf(3 to 200.0)) // huge PR
        val merged = ExerciseStatsCalculator.merge(EX, base, newSession)
        val ctx = merged.forContext(SlotContext.NORMAL)!!
        assertEquals(PreviousSet(3, 200.0), ctx.pr)
        // newer session has real data → it also becomes "previous"
        assertEquals("2026-08-05", ctx.previousSessionDate)
        assertEquals(listOf(PreviousSet(3, 200.0)), ctx.previousSets)
    }

    @Test
    fun `merge of an all-zero session does not touch previous or pr`() {
        val base = ExerciseStatsCalculator.rebuild(
            EX,
            listOf(strengthSession("2026-08-01T10:00:00", listOf(8 to 60.0))),
        )
        val emptySession = strengthSession("2026-08-05T10:00:00", listOf(0 to 0.0, 0 to 0.0))
        val merged = ExerciseStatsCalculator.merge(EX, base, emptySession)
        val ctx = merged.forContext(SlotContext.NORMAL)!!
        assertEquals("2026-08-01", ctx.previousSessionDate)
        assertEquals(PreviousSet(8, 60.0), ctx.pr)
    }

    @Test
    fun `merge of a same-day re-save replaces previous with the latest set data`() {
        // First save of the day.
        val first = strengthSession("2026-08-05T10:00:00", listOf(8 to 60.0, 8 to 60.0))
        var stats = ExerciseStatsCalculator.merge(EX, null, first)
        assertEquals(
            listOf(PreviousSet(8, 60.0), PreviousSet(8, 60.0)),
            stats.forContext(SlotContext.NORMAL)!!.previousSets,
        )
        // Same day, later time, edited numbers — must overwrite "previous" (>= guard).
        val resave = strengthSession("2026-08-05T18:30:00", listOf(8 to 62.5, 8 to 62.5))
        stats = ExerciseStatsCalculator.merge(EX, stats, resave)
        val ctx = stats.forContext(SlotContext.NORMAL)!!
        assertEquals("2026-08-05", ctx.previousSessionDate)
        assertEquals(listOf(PreviousSet(8, 62.5), PreviousSet(8, 62.5)), ctx.previousSets)
        assertEquals(PreviousSet(8, 62.5), ctx.pr)
    }

    @Test
    fun `merge tolerates a stored previousSessionDate that is date-only vs an incoming datetime`() {
        // Simulate a sidecar whose previousSessionDate was written date-only (older data / a
        // session with a blank completedAt that fell back to date).
        val stale = ExerciseStats(
            exerciseId = EX,
            perContext = mapOf(
                SlotContext.NORMAL to ContextStats(
                    previousSets = listOf(PreviousSet(5, 100.0)),
                    previousSessionDate = "2026-08-04",
                    pr = PreviousSet(5, 100.0),
                    hasPriorRealTonnage = true,
                ),
            ),
        )
        val newer = strengthSession("2026-08-05T09:00:00", listOf(6 to 100.0))
        val ctx = ExerciseStatsCalculator.merge(EX, stale, newer).forContext(SlotContext.NORMAL)!!
        assertEquals("2026-08-05", ctx.previousSessionDate)
        assertEquals(listOf(PreviousSet(6, 100.0)), ctx.previousSets)
    }

    @Test
    fun `merge onto null current is same as rebuild from that one session`() {
        val session = strengthSession("2026-08-05T10:00:00", listOf(5 to 100.0, 4 to 110.0))
        val merged = ExerciseStatsCalculator.merge(EX, null, session)
        val rebuilt = ExerciseStatsCalculator.rebuild(EX, listOf(session))
        assertEquals(rebuilt.perContext, merged.perContext)
    }

    @Test
    fun `merge onto stale schema version rebuilds from just this session`() {
        val stale = ExerciseStats(exerciseId = EX, schemaVersion = 0)
        val session = strengthSession("2026-08-05T10:00:00", listOf(5 to 100.0))
        val merged = ExerciseStatsCalculator.merge(EX, stale, session)
        assertEquals(ExerciseStats.SCHEMA_VERSION, merged.schemaVersion)
        assertEquals(PreviousSet(5, 100.0), merged.forContext(SlotContext.NORMAL)!!.pr)
    }

    @Test
    fun `incremental merge chain agrees with full rebuild`() {
        val sessions = listOf(
            strengthSession("2026-08-01T10:00:00", listOf(8 to 60.0, 8 to 60.0)),
            strengthSession("2026-08-05T10:00:00", listOf(3 to 200.0)),               // PR
            strengthSession("2026-08-10T10:00:00", listOf(0 to 0.0)),                 // empty
            strengthSession("2026-08-15T10:00:00", listOf(10 to 50.0, 10 to 52.5)),   // latest real
            strengthSession("2026-08-20T10:00:00", listOf(6 to 62.0), ctx = SlotContext.WARMUP),
        )

        var incremental: ExerciseStats? = null
        for (s in sessions) incremental = ExerciseStatsCalculator.merge(EX, incremental, s)

        val full = ExerciseStatsCalculator.rebuild(EX, sessions)

        assertEquals(full.forContext(SlotContext.NORMAL), incremental!!.forContext(SlotContext.NORMAL))
        assertEquals(full.forContext(SlotContext.WARMUP), incremental.forContext(SlotContext.WARMUP))
    }

    @Test
    fun `bodyweight materialized weight is used as-is`() {
        // A materialized bodyweight set arrives with weight already filled in.
        val session = WorkoutSession(
            id = "bw", routineId = "rt-a", routineName = "R", date = "2026-08-01",
            completedAt = "2026-08-01T10:00:00",
            exercises = listOf(
                WorkoutExercise(
                    exerciseId = EX, exerciseName = "Pull-up", bodypart = "back",
                    type = ExerciseType.FORZA, completed = true,
                    sets = listOf(
                        ExerciseSet.Strength(reps = 10, weight = 75.0, isBodyweight = true, bwLoadPercent = 100),
                    ),
                ),
            ),
        )
        val ctx = ExerciseStatsCalculator.rebuild(EX, listOf(session)).forContext(SlotContext.NORMAL)!!
        assertEquals(PreviousSet(10, 75.0), ctx.pr)
        assertEquals(listOf(PreviousSet(10, 75.0)), ctx.previousSets)
    }

    @Test
    fun `bodyweight base weight is carried into pr and previous sets`() {
        // bwLoadPercent 75 of an 80 kg body weight → materialized weight 60; the 80 must
        // survive as bwBaseWeightKg so the screen can show "reps x peso corpo".
        val session = WorkoutSession(
            id = "bw2", routineId = "rt-a", routineName = "R", date = "2026-08-01",
            completedAt = "2026-08-01T10:00:00",
            exercises = listOf(
                WorkoutExercise(
                    exerciseId = EX, exerciseName = "Pull-up", bodypart = "back",
                    type = ExerciseType.FORZA, completed = true,
                    sets = listOf(
                        ExerciseSet.Strength(
                            reps = 12, weight = 60.0, isBodyweight = true,
                            bwLoadPercent = 75, bwBaseWeightKg = 80.0,
                        ),
                    ),
                ),
            ),
        )
        val ctx = ExerciseStatsCalculator.rebuild(EX, listOf(session)).forContext(SlotContext.NORMAL)!!
        assertEquals(80.0, ctx.pr!!.bwBaseWeightKg, 0.0)
        assertEquals(60.0, ctx.pr!!.weight, 0.0)
        assertEquals(80.0, ctx.previousSets.single().bwBaseWeightKg, 0.0)

        // And the incremental path must agree.
        val merged = ExerciseStatsCalculator.merge(EX, null, session).forContext(SlotContext.NORMAL)!!
        assertEquals(80.0, merged.pr!!.bwBaseWeightKg, 0.0)
    }

    @Test
    fun `stretch-only history yields no context stats`() {
        val session = WorkoutSession(
            id = "s", routineId = "rt-a", routineName = "R", date = "2026-08-01",
            completedAt = "2026-08-01T10:00:00",
            exercises = listOf(
                WorkoutExercise(
                    exerciseId = EX, exerciseName = "Hamstring stretch", bodypart = "legs",
                    type = ExerciseType.STRETCH, completed = true,
                    sets = listOf(ExerciseSet.Stretch(timeSeconds = 60, done = true)),
                ),
            ),
        )
        val stats = ExerciseStatsCalculator.rebuild(EX, listOf(session))
        assertNull(stats.forContext(SlotContext.NORMAL))
        assertFalse(stats.perContext.containsKey(SlotContext.NORMAL))
    }
}
