package com.mygymapp.data.parser

import com.mygymapp.data.model.Routine
import com.mygymapp.data.model.RoutineExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip coverage for [RoutineParser]: toMarkdown() -> fromMarkdown() must reproduce the
 * original routine. Mirrors [WorkoutParserRoundTripTest]'s shape for the sibling parser.
 */
class RoutineParserRoundTripTest {

    private fun roundTrip(routine: Routine): Routine =
        RoutineParser.fromMarkdown(RoutineParser.toMarkdown(routine))

    @Test
    fun `minimal routine round-trips`() {
        val routine = Routine(id = "rt-abcd1234", name = "Push day", day = "Monday")

        val result = roundTrip(routine)

        assertEquals(routine.id, result.id)
        assertEquals(routine.name, result.name)
        assertEquals(routine.day, result.day)
        assertTrue(result.enabled)
        assertTrue(result.exercises.isEmpty())
    }

    @Test
    fun `exercise with rep range, superset and warmup flags round-trips`() {
        val routine = Routine(
            id = "rt-abcd1234",
            name = "Push day",
            enabled = false,
            exercises = listOf(
                RoutineExercise(
                    exerciseId = "ex-11112222",
                    sets = 4,
                    repRangeMin = 8,
                    repRangeMax = 12,
                    supersetWithNext = true,
                    isWarmup = true,
                ),
                RoutineExercise(exerciseId = "ex-33334444", sets = 3),
            ),
        )

        val result = roundTrip(routine)

        assertFalse(result.enabled)
        assertEquals(2, result.exercises.size)
        val first = result.exercises[0]
        assertEquals("ex-11112222", first.exerciseId)
        assertEquals(4, first.sets)
        assertEquals(8, first.repRangeMin)
        assertEquals(12, first.repRangeMax)
        assertTrue(first.supersetWithNext)
        assertTrue(first.isWarmup)

        val second = result.exercises[1]
        assertEquals(0, second.repRangeMin)
        assertEquals(0, second.repRangeMax)
        assertFalse(second.supersetWithNext)
        assertFalse(second.isWarmup)
    }

    @Test
    fun `cardio duration stored in timePerSetSeconds round-trips`() {
        val routine = Routine(
            id = "rt-abcd1234",
            name = "Cardio day",
            exercises = listOf(
                RoutineExercise(exerciseId = "ex-cardio01", sets = 1, timePerSetSeconds = 600),
            ),
        )

        val result = roundTrip(routine)

        assertEquals(600, result.exercises[0].timePerSetSeconds)
    }

    @Test
    fun `notes body round-trips`() {
        val routine = Routine(id = "rt-abcd1234", name = "Push day", notes = "Focus on tempo.")

        val result = roundTrip(routine)

        assertEquals(routine.notes, result.notes)
    }

    @Test
    fun `fromMarkdown on empty content returns empty defaults without throwing`() {
        val result = RoutineParser.fromMarkdown("")

        assertEquals("", result.id)
        assertTrue(result.exercises.isEmpty())
    }

    @Test
    fun `exercise entries missing exerciseId are dropped instead of throwing`() {
        val malformed = """
            ---
            id: "rt-abcd1234"
            name: "Push day"
            day: ""
            enabled: true
            created: ""
            updated: ""
            exercises:
              - sets: 3
              - exerciseId: "ex-11112222"
                sets: 4
            ---
        """.trimIndent()

        val result = RoutineParser.fromMarkdown(malformed)

        assertEquals(1, result.exercises.size)
        assertEquals("ex-11112222", result.exercises[0].exerciseId)
    }
}
