package com.mygymapp.data.parser

import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip coverage for [WorkoutParser]: toMarkdown() -> fromMarkdown() must reproduce the
 * original session. This is the entire persistence layer for a session (see docs/STORAGE.md) —
 * a silent drift here corrupts real workout history on disk, not just an in-memory object.
 */
class WorkoutParserRoundTripTest {

    private fun roundTrip(session: WorkoutSession): WorkoutSession =
        WorkoutParser.fromMarkdown(WorkoutParser.toMarkdown(session))

    @Test
    fun `minimal session round-trips`() {
        val session = WorkoutSession(
            id = "abcd1234",
            routineId = "rt-abcd1234",
            routineName = "Push day",
            date = "2026-08-25",
        )

        val result = roundTrip(session)

        assertEquals(session.id, result.id)
        assertEquals(session.routineId, result.routineId)
        assertEquals(session.routineName, result.routineName)
        assertEquals(session.date, result.date)
        assertTrue(result.exercises.isEmpty())
    }

    @Test
    fun `strength exercise with bodyweight set round-trips`() {
        val session = WorkoutSession(
            id = "abcd1234",
            routineId = "rt-abcd1234",
            routineName = "Push day",
            date = "2026-08-25",
            totalTonnage = 1234.5,
            tonnageByBodypart = mapOf("chest" to 800.0, "triceps" to 434.5),
            exercises = listOf(
                WorkoutExercise(
                    exerciseId = "ex-11112222",
                    exerciseName = "Bench Press",
                    bodypart = "chest",
                    type = ExerciseType.FORZA,
                    completed = true,
                    sets = listOf(
                        ExerciseSet.Strength(reps = 8, weight = 80.0),
                        ExerciseSet.Strength(reps = 0, weight = 0.0, isBodyweight = true),
                        ExerciseSet.Strength(
                            reps = 12, weight = 55.0, isBodyweight = true,
                            bwLoadPercent = 75, bwBaseWeightKg = 73.3,
                        ),
                    ),
                ),
            ),
        )

        val result = roundTrip(session)

        assertEquals(session.totalTonnage, result.totalTonnage, 0.001)
        assertEquals(session.tonnageByBodypart, result.tonnageByBodypart)
        assertEquals(1, result.exercises.size)
        val ex = result.exercises[0]
        assertEquals("ex-11112222", ex.exerciseId)
        assertEquals(ExerciseType.FORZA, ex.type)
        assertTrue(ex.completed)
        assertEquals(3, ex.sets.size)
        val strength0 = ex.sets[0] as ExerciseSet.Strength
        assertEquals(8, strength0.reps)
        assertEquals(80.0, strength0.weight, 0.001)
        assertTrue((ex.sets[1] as ExerciseSet.Strength).isBodyweight)
        val materialized = ex.sets[2] as ExerciseSet.Strength
        assertTrue(materialized.isBodyweight)
        assertEquals(55.0, materialized.weight, 0.001)
        assertEquals(75, materialized.bwLoadPercent)
        assertEquals(73.3, materialized.bwBaseWeightKg, 0.001)
    }

    @Test
    fun `stretch and cardio sets round-trip`() {
        val session = WorkoutSession(
            id = "abcd1234",
            routineId = "rt-abcd1234",
            routineName = "Mobility",
            date = "2026-08-25",
            exercises = listOf(
                WorkoutExercise(
                    exerciseId = "ex-stretch1",
                    exerciseName = "Hamstring stretch",
                    bodypart = "legs",
                    type = ExerciseType.STRETCH,
                    sets = listOf(ExerciseSet.Stretch(timeSeconds = 60, done = true)),
                ),
                WorkoutExercise(
                    exerciseId = "ex-cardio01",
                    exerciseName = "Bike",
                    bodypart = "cardio",
                    type = ExerciseType.CARDIO,
                    sets = listOf(
                        ExerciseSet.Cardio(
                            startedAt = "2026-08-25T10:00:00",
                            endedAt = "2026-08-25T10:10:00",
                            avgHr = 130,
                            maxHr = 150,
                        ),
                    ),
                ),
            ),
        )

        val result = roundTrip(session)

        val stretch = result.exercises[0].sets[0] as ExerciseSet.Stretch
        assertEquals(60, stretch.timeSeconds)
        assertTrue(stretch.done)

        val cardio = result.exercises[1].sets[0] as ExerciseSet.Cardio
        assertEquals("2026-08-25T10:00:00", cardio.startedAt)
        assertEquals("2026-08-25T10:10:00", cardio.endedAt)
        assertEquals(130, cardio.avgHr)
        assertEquals(150, cardio.maxHr)
    }

    @Test
    fun `switch-exercise and daily flags round-trip`() {
        val session = WorkoutSession(
            id = "abcd1234",
            routineId = "rt-abcd1234",
            routineName = "Push day",
            date = "2026-08-25",
            exercises = listOf(
                WorkoutExercise(
                    exerciseId = "ex-newexerc",
                    exerciseName = "Incline Press",
                    bodypart = "chest",
                    type = ExerciseType.FORZA,
                    completed = true,
                    // completedEmpty always implies completed: the lifter tapped "Complete"
                    // without touching a value. The pre-fill on `sets` is still persisted.
                    completedEmpty = true,
                    sets = listOf(ExerciseSet.Strength(reps = 8, weight = 40.0)),
                    excludeFromTonnage = true,
                    isDaily = true,
                    substitutedFor = "ex-oldexerc",
                ),
            ),
        )

        val result = roundTrip(session)

        val ex = result.exercises[0]
        assertTrue(ex.excludeFromTonnage)
        assertTrue(ex.isDaily)
        assertTrue(ex.completed)
        assertTrue(ex.completedEmpty)
        assertEquals(1, ex.sets.size)
        assertEquals("ex-oldexerc", ex.substitutedFor)
    }

    @Test
    fun `HRV and ECG fields round-trip when present`() {
        val session = WorkoutSession(
            id = "abcd1234",
            routineId = "rt-abcd1234",
            routineName = "Push day",
            date = "2026-08-25",
            ecgBeats = 540,
            ecgDurationSec = 62.5,
            ecgAvgHr = 128.4,
            ecgSessionRmssd = 34.2,
            ecgPacCount = 2,
            ecgPauseCount = 0,
            ecgIrregularBeats = 1,
            cardiacDriftBpmMin = 0.8,
            restingHr = 58,
            hrr60s = 24.3,
            sdnn = 45.1,
            pnn50 = 12.6,
            poincareSd1 = 20.0,
            poincareSd2 = 60.0,
            poincareRatio = 3.0,
            afibSuspicionEpisodes = 0,
            sessionRpe = 7,
            sessionLoad = 350.0f,
        )

        val result = roundTrip(session)

        assertEquals(session.ecgBeats, result.ecgBeats)
        assertEquals(session.ecgDurationSec, result.ecgDurationSec, 0.01)
        assertEquals(session.ecgAvgHr, result.ecgAvgHr, 0.01)
        assertEquals(session.ecgSessionRmssd, result.ecgSessionRmssd, 0.01)
        assertEquals(session.restingHr, result.restingHr)
        assertEquals(session.hrr60s, result.hrr60s, 0.01)
        assertEquals(session.sessionRpe, result.sessionRpe)
        assertEquals(session.sessionLoad, result.sessionLoad)
    }

    @Test
    fun `zero-value HRV fields are omitted on write but read back as defaults`() {
        // WorkoutParser.toMarkdown() deliberately omits zero-value ECG/HRV fields to keep the
        // YAML lean (see the `if (session.xxx > 0) put(...)` guards). Confirm the omission
        // doesn't break re-parsing: a never-computed field must come back as its default, not
        // throw or become null.
        val session = WorkoutSession(
            id = "abcd1234",
            routineId = "rt-abcd1234",
            routineName = "Push day",
            date = "2026-08-25",
        )

        val markdown = WorkoutParser.toMarkdown(session)
        assertTrue("hrr60s should be omitted when zero", !markdown.contains("hrr60s"))

        val result = WorkoutParser.fromMarkdown(markdown)
        assertEquals(0.0, result.hrr60s, 0.0)
        assertEquals(0, result.restingHr)
    }

    @Test
    fun `notes body round-trips`() {
        val session = WorkoutSession(
            id = "abcd1234",
            routineId = "rt-abcd1234",
            routineName = "Push day",
            date = "2026-08-25",
            notes = "Felt strong today, PR on bench.",
        )

        val result = roundTrip(session)

        assertEquals(session.notes, result.notes)
    }

    @Test
    fun `fromMarkdown on empty content returns empty defaults without throwing`() {
        val result = WorkoutParser.fromMarkdown("")

        assertEquals("", result.id)
        assertTrue(result.exercises.isEmpty())
    }
}
