package com.mygymapp.data.parser

import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip coverage for [ExerciseParser]: toMarkdown() -> fromMarkdown() must reproduce the
 * original exercise, including the [ExerciseType] file-string mapping and the isBodyweight flag
 * (omitted from the YAML when false — see [ExerciseParser.toMarkdown]). */
class ExerciseParserRoundTripTest {

    private fun roundTrip(exercise: Exercise): Exercise =
        ExerciseParser.fromMarkdown(ExerciseParser.toMarkdown(exercise))

    @Test
    fun `forza exercise round-trips`() {
        val exercise = Exercise(
            id = "ex-11112222",
            name = "Bench Press",
            type = ExerciseType.FORZA,
            bodypart = "chest",
            defaultRepRangeMin = 6,
            defaultRepRangeMax = 10,
        )

        val result = roundTrip(exercise)

        assertEquals(exercise.id, result.id)
        assertEquals(exercise.name, result.name)
        assertEquals(ExerciseType.FORZA, result.type)
        assertEquals(exercise.bodypart, result.bodypart)
        assertEquals(6, result.defaultRepRangeMin)
        assertEquals(10, result.defaultRepRangeMax)
        assertFalse(result.isBodyweight)
    }

    @Test
    fun `stretch and cardio types round-trip through toFileString-fromString`() {
        val stretch = Exercise(id = "ex-s", name = "Hamstring stretch", type = ExerciseType.STRETCH, bodypart = "legs")
        val cardio = Exercise(id = "ex-c", name = "Bike", type = ExerciseType.CARDIO, bodypart = "")

        assertEquals(ExerciseType.STRETCH, roundTrip(stretch).type)
        assertEquals(ExerciseType.CARDIO, roundTrip(cardio).type)
    }

    @Test
    fun `isBodyweight true round-trips and defaults to false when absent`() {
        val bodyweight = Exercise(id = "ex-bw", name = "Push-up", type = ExerciseType.FORZA, bodypart = "chest", isBodyweight = true, bwLoadPercent = 75)

        assertTrue(roundTrip(bodyweight).isBodyweight)

        val markdown = ExerciseParser.toMarkdown(Exercise(id = "ex-x", name = "Squat", type = ExerciseType.FORZA, bodypart = "legs"))
        assertFalse("isBodyweight=false should be omitted to keep the file lean", markdown.contains("isBodyweight"))
    }

    @Test
    fun `bwLoadPercent round-trips for a bodyweight exercise and is omitted otherwise`() {
        val bw = Exercise(id = "ex-bw", name = "Reverse sit-up", type = ExerciseType.FORZA, bodypart = "core", isBodyweight = true, bwLoadPercent = 50)
        assertEquals(50, roundTrip(bw).bwLoadPercent)

        val plain = Exercise(id = "ex-x", name = "Squat", type = ExerciseType.FORZA, bodypart = "legs")
        assertEquals(0, roundTrip(plain).bwLoadPercent)
        assertFalse(ExerciseParser.toMarkdown(plain).contains("bwLoadPercent"))
    }

    @Test
    fun `a bodyweight exercise with no bwLoadPercent in the file migrates to 75`() {
        val legacy = """
            ---
            id: "ex-legacy"
            name: "Plank"
            type: "forza"
            bodypart: "core"
            isBodyweight: true
            ---
        """.trimIndent()

        assertEquals(75, ExerciseParser.fromMarkdown(legacy).bwLoadPercent)
    }

    @Test
    fun `notes body round-trips`() {
        val exercise = Exercise(
            id = "ex-11112222",
            name = "Bench Press",
            type = ExerciseType.FORZA,
            bodypart = "chest",
            notes = "Keep shoulder blades retracted.",
        )

        assertEquals(exercise.notes, roundTrip(exercise).notes)
    }

    @Test
    fun `fromMarkdown defaults an unknown type string to FORZA`() {
        val malformed = """
            ---
            id: "ex-11112222"
            name: "Mystery move"
            type: "not-a-real-type"
            bodypart: "chest"
            ---
        """.trimIndent()

        val result = ExerciseParser.fromMarkdown(malformed)

        assertEquals(ExerciseType.FORZA, result.type)
    }

    @Test
    fun `fromMarkdown on empty content returns empty defaults without throwing`() {
        val result = ExerciseParser.fromMarkdown("")

        assertEquals("", result.id)
        assertEquals(8, result.defaultRepRangeMin)
        assertEquals(12, result.defaultRepRangeMax)
    }
}
