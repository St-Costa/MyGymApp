package com.mygymapp.data.parser

import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutParserTest {

    private fun sampleSession(
        exerciseName: String = "Bench Press",
        routineName: String = "Push Day",
        notes: String = "",
    ) = WorkoutSession(
        id = "abcd1234",
        routineId = "rt-11112222",
        routineName = routineName,
        date = "2026-07-24",
        startedAt = "2026-07-24T09:15:00",
        completedAt = "2026-07-24T10:05:42",
        bodyWeightKg = 78.5,
        totalTonnage = 3200.0,
        tonnageByBodypart = mapOf("chest" to 3200.0),
        sessionCalories = 412.3,
        sessionTrimp = 87.5,
        vo2max = 48.2,
        readiness = "NORMAL",
        readinessLnRmssd = 3.8,
        hrrPerSet = listOf(24.0, 19.0, 22.0),
        ecgBeats = 5240,
        ecgAvgHr = 118.7,
        ecgSessionRmssd = 38.2,
        restingHr = 58,
        hrr60s = 21.7,
        exercises = listOf(
            WorkoutExercise(
                exerciseId = "ex-a1b2c3d4",
                exerciseName = exerciseName,
                bodypart = "chest",
                type = ExerciseType.FORZA,
                completed = true,
                sets = listOf(
                    ExerciseSet.Strength(reps = 8, weight = 80.0),
                    ExerciseSet.Strength(reps = 6, weight = 85.0),
                ),
            ),
        ),
        notes = notes,
    )

    @Test
    fun `round-trip preserves core fields`() {
        val original = sampleSession()
        val md = WorkoutParser.toMarkdown(original)
        val parsed = WorkoutParser.fromMarkdown(md)

        assertEquals(original.id, parsed.id)
        assertEquals(original.date, parsed.date)
        assertEquals(original.startedAt, parsed.startedAt)
        assertEquals(original.completedAt, parsed.completedAt)
        assertEquals(original.routineId, parsed.routineId)
        assertEquals(original.routineName, parsed.routineName)
        assertEquals(original.bodyWeightKg, parsed.bodyWeightKg, 0.01)
        assertEquals(original.totalTonnage, parsed.totalTonnage, 0.01)
        assertEquals(original.readiness, parsed.readiness)
        assertEquals(original.readinessLnRmssd, parsed.readinessLnRmssd, 0.01)
        assertEquals(original.hrrPerSet, parsed.hrrPerSet)
        // ecgAvgHr had regressed in an earlier commit — this locks the field in
        // as part of the round-trip.
        assertEquals(original.ecgAvgHr, parsed.ecgAvgHr, 0.01)
    }

    @Test
    fun `round-trip preserves exercises with strength sets`() {
        val original = sampleSession()
        val parsed = WorkoutParser.fromMarkdown(WorkoutParser.toMarkdown(original))
        assertEquals(1, parsed.exercises.size)
        val ex = parsed.exercises[0]
        assertEquals("ex-a1b2c3d4", ex.exerciseId)
        assertEquals(ExerciseType.FORZA, ex.type)
        assertTrue(ex.completed)
        assertEquals(2, ex.sets.size)
        val s0 = ex.sets[0] as ExerciseSet.Strength
        assertEquals(8, s0.reps)
        assertEquals(80.0, s0.weight, 0.001)
    }

    @Test
    fun `serialization escapes double quotes in string values`() {
        // Regression for the "invalid YAML on names containing quotes" bug.
        val original = sampleSession(exerciseName = """Bench "heavy"""")
        val md = WorkoutParser.toMarkdown(original)
        val parsed = WorkoutParser.fromMarkdown(md)
        assertEquals("""Bench "heavy"""", parsed.exercises[0].exerciseName)
    }

    @Test
    fun `serialization escapes backslashes in string values`() {
        val original = sampleSession(routineName = """C:\Program Files\gym""")
        val parsed = WorkoutParser.fromMarkdown(WorkoutParser.toMarkdown(original))
        assertEquals("""C:\Program Files\gym""", parsed.routineName)
    }

    @Test
    fun `zero-valued optional Polar fields are omitted from YAML`() {
        val session = WorkoutSession(
            id = "abcd1234",
            routineId = "rt-11112222",
            routineName = "Push Day",
            date = "2026-07-24",
        )
        val md = WorkoutParser.toMarkdown(session)
        // Only unconditional fields should appear
        assertTrue(md.contains("id: "))
        assertTrue(md.contains("routineId: "))
        assertTrue(md.contains("totalTonnage: 0.0"))
        // Optional Polar-derived fields shouldn't
        assertTrue(md.lines().none { it.startsWith("readiness:") })
        assertTrue(md.lines().none { it.startsWith("readinessLnRmssd:") })
        assertTrue(md.lines().none { it.startsWith("hrrPerSet:") })
        assertTrue(md.lines().none { it.startsWith("ecgAvgHr:") })
        assertTrue(md.lines().none { it.startsWith("restingHr:") })
    }

    @Test
    fun `parsing a file missing new fields keeps them at defaults`() {
        // Older file written before startedAt/bodyWeightKg/readiness existed.
        val legacy = """
            ---
            id: "old12345"
            routineId: "rt-legacy00"
            routineName: "Push Day"
            date: "2025-01-01"
            completedAt: "2025-01-01T10:00:00"
            totalTonnage: 1200.0
            tonnageByBodypart:
              chest: 1200.0
            sessionCalories: 200.0
            sessionTrimp: 40.0
            vo2max: 45.0
            exercises: []
            ---
        """.trimIndent()
        val parsed = WorkoutParser.fromMarkdown(legacy)
        assertEquals("old12345", parsed.id)
        assertEquals(1200.0, parsed.totalTonnage, 0.001)
        assertEquals("", parsed.startedAt)  // default when missing
        assertEquals(0.0, parsed.bodyWeightKg, 0.001)
        assertEquals("", parsed.readiness)
        assertTrue(parsed.hrrPerSet.isEmpty())
        assertNotNull(parsed.tonnageByBodypart["chest"])
    }
}
