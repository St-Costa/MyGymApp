package com.mygymapp.data.parser

import com.mygymapp.data.model.ContextStats
import com.mygymapp.data.model.ExerciseStats
import com.mygymapp.data.model.PreviousSet
import com.mygymapp.data.model.SlotContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseStatsParserTest {

    private fun roundTrip(stats: ExerciseStats): ExerciseStats {
        val yaml = ExerciseStatsParser.toYaml(stats)
        return ExerciseStatsParser.fromYaml(yaml) ?: error("round-trip returned null for:\n$yaml")
    }

    @Test
    fun `empty stats round-trips`() {
        val stats = ExerciseStats(exerciseId = "ex-11112222")
        val result = roundTrip(stats)
        assertEquals("ex-11112222", result.exerciseId)
        assertEquals(ExerciseStats.SCHEMA_VERSION, result.schemaVersion)
        assertEquals(emptyMap<SlotContext, ContextStats>(), result.perContext)
    }

    @Test
    fun `single context with pr and previous sets round-trips`() {
        val stats = ExerciseStats(
            exerciseId = "ex-3e4195a9",
            perContext = mapOf(
                SlotContext.NORMAL to ContextStats(
                    previousSets = listOf(
                        PreviousSet(8, 80.0),
                        PreviousSet(8, 80.0),
                        PreviousSet(6, 82.5),
                    ),
                    previousSessionDate = "2026-08-25T19:04:11",
                    pr = PreviousSet(6, 90.0),
                    rmPr = PreviousSet(3, 100.0),
                    hasPriorRealTonnage = true,
                ),
            ),
        )

        val result = roundTrip(stats)

        val ctx = result.forContext(SlotContext.NORMAL)
        assertNotNull(ctx)
        assertEquals(listOf(PreviousSet(8, 80.0), PreviousSet(8, 80.0), PreviousSet(6, 82.5)), ctx!!.previousSets)
        assertEquals("2026-08-25T19:04:11", ctx.previousSessionDate)
        assertEquals(PreviousSet(6, 90.0), ctx.pr)
        assertEquals(PreviousSet(3, 100.0), ctx.rmPr)
        assertEquals(true, ctx.hasPriorRealTonnage)
    }

    @Test
    fun `rmPr absent round-trips as null`() {
        val stats = ExerciseStats(
            exerciseId = "ex-3e4195a9",
            perContext = mapOf(
                SlotContext.NORMAL to ContextStats(
                    previousSets = listOf(PreviousSet(8, 80.0)),
                    previousSessionDate = "2026-08-25",
                    pr = PreviousSet(6, 90.0),
                    rmPr = null,
                    hasPriorRealTonnage = true,
                ),
            ),
        )

        val ctx = roundTrip(stats).forContext(SlotContext.NORMAL)!!
        assertNull(ctx.rmPr)
        assertEquals(PreviousSet(6, 90.0), ctx.pr)
    }

    @Test
    fun `all three contexts round-trip independently`() {
        val stats = ExerciseStats(
            exerciseId = "ex-abcdabcd",
            perContext = mapOf(
                SlotContext.NORMAL to ContextStats(
                    previousSets = listOf(PreviousSet(5, 100.0)),
                    previousSessionDate = "2026-08-01",
                    pr = PreviousSet(3, 120.0),
                    hasPriorRealTonnage = true,
                ),
                SlotContext.WARMUP to ContextStats(
                    previousSets = listOf(PreviousSet(12, 40.0)),
                    previousSessionDate = "2026-08-02",
                    pr = PreviousSet(12, 40.0),
                    hasPriorRealTonnage = true,
                ),
                SlotContext.DAILY to ContextStats(
                    previousSets = listOf(PreviousSet(15, 20.0)),
                    previousSessionDate = "2026-08-03",
                    pr = PreviousSet(16, 20.0),
                    hasPriorRealTonnage = true,
                ),
            ),
        )

        val result = roundTrip(stats)

        assertEquals(3, result.perContext.size)
        assertEquals(PreviousSet(3, 120.0), result.forContext(SlotContext.NORMAL)!!.pr)
        assertEquals(PreviousSet(12, 40.0), result.forContext(SlotContext.WARMUP)!!.pr)
        assertEquals("2026-08-03", result.forContext(SlotContext.DAILY)!!.previousSessionDate)
    }

    @Test
    fun `context with no pr round-trips`() {
        val stats = ExerciseStats(
            exerciseId = "ex-nopr0000",
            perContext = mapOf(
                SlotContext.NORMAL to ContextStats(
                    previousSets = emptyList(),
                    previousSessionDate = "",
                    pr = null,
                    hasPriorRealTonnage = false,
                ),
            ),
        )

        val result = roundTrip(stats)
        val ctx = result.forContext(SlotContext.NORMAL)!!
        assertNull(ctx.pr)
        assertEquals(emptyList<PreviousSet>(), ctx.previousSets)
        assertEquals(false, ctx.hasPriorRealTonnage)
    }

    @Test
    fun `bodyweight base weight on pr and previous sets round-trips`() {
        val stats = ExerciseStats(
            exerciseId = "ex-bw000001",
            perContext = mapOf(
                SlotContext.NORMAL to ContextStats(
                    previousSets = listOf(
                        PreviousSet(reps = 12, weight = 60.0, bwBaseWeightKg = 80.0),
                        PreviousSet(reps = 10, weight = 60.0, bwBaseWeightKg = 80.0),
                    ),
                    previousSessionDate = "2026-08-25",
                    pr = PreviousSet(reps = 15, weight = 61.5, bwBaseWeightKg = 82.0),
                    hasPriorRealTonnage = true,
                ),
            ),
        )

        val result = roundTrip(stats)
        val ctx = result.forContext(SlotContext.NORMAL)!!
        assertEquals(82.0, ctx.pr!!.bwBaseWeightKg, 0.0)
        assertEquals(15, ctx.pr!!.reps)
        assertEquals(80.0, ctx.previousSets[0].bwBaseWeightKg, 0.0)
        assertEquals(80.0, ctx.previousSets[1].bwBaseWeightKg, 0.0)
    }

    @Test
    fun `non-bodyweight sets omit the bwBaseWeightKg key`() {
        val stats = ExerciseStats(
            exerciseId = "ex-nobw0001",
            perContext = mapOf(
                SlotContext.NORMAL to ContextStats(
                    previousSets = listOf(PreviousSet(8, 80.0)),
                    previousSessionDate = "2026-08-25",
                    pr = PreviousSet(6, 90.0),
                    hasPriorRealTonnage = true,
                ),
            ),
        )
        val yaml = ExerciseStatsParser.toYaml(stats)
        assertEquals(false, yaml.contains("bwBaseWeightKg"))
        // And it still reads back with a zero default.
        val ctx = ExerciseStatsParser.fromYaml(yaml)!!.forContext(SlotContext.NORMAL)!!
        assertEquals(0.0, ctx.pr!!.bwBaseWeightKg, 0.0)
    }

    @Test
    fun `garbage content returns null`() {
        assertNull(ExerciseStatsParser.fromYaml(""))
        assertNull(ExerciseStatsParser.fromYaml("not yaml at all"))
    }

    @Test
    fun `old schema version parses but is flagged by version field`() {
        val yaml = """
            ---
            exerciseId: "ex-old00000"
            schemaVersion: 0
            contexts:
            ---
        """.trimIndent()
        val result = ExerciseStatsParser.fromYaml(yaml)
        assertNotNull(result)
        assertEquals(0, result!!.schemaVersion)
    }
}
