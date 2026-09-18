package com.mygymapp.data.parser

import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.LoadMode
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
    fun `loadMode ASSISTED round-trips and defaults to MANUAL when absent`() {
        val assisted = Exercise(
            id = "ex-lat", name = "Assisted pull-up", type = ExerciseType.FORZA,
            bodypart = "back", loadMode = LoadMode.ASSISTED,
        )
        val result = roundTrip(assisted)
        assertEquals(LoadMode.ASSISTED, result.loadMode)
        assertFalse("assisted is not bodyweight", result.isBodyweight)

        val plain = Exercise(id = "ex-x", name = "Squat", type = ExerciseType.FORZA, bodypart = "legs")
        assertEquals(LoadMode.MANUAL, roundTrip(plain).loadMode)
        assertFalse(ExerciseParser.toMarkdown(plain).contains("loadMode"))
    }

    @Test
    fun `loadMode BODYWEIGHT round-trips consistently with isBodyweight`() {
        val bw = Exercise(
            id = "ex-bw", name = "Push-up", type = ExerciseType.FORZA, bodypart = "chest",
            isBodyweight = true, bwLoadPercent = 75, loadMode = LoadMode.BODYWEIGHT,
        )
        val result = roundTrip(bw)
        assertEquals(LoadMode.BODYWEIGHT, result.loadMode)
        assertTrue(result.isBodyweight)
    }

    @Test
    fun `a legacy isBodyweight file with no loadMode key still resolves loadMode to BODYWEIGHT`() {
        val legacy = """
            ---
            id: "ex-legacy"
            name: "Plank"
            type: "forza"
            bodypart: "core"
            isBodyweight: true
            bwLoadPercent: 75
            ---
        """.trimIndent()

        assertEquals(LoadMode.BODYWEIGHT, ExerciseParser.fromMarkdown(legacy).loadMode)
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
