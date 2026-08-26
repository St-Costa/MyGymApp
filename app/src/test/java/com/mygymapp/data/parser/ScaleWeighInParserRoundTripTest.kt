package com.mygymapp.data.parser

import com.mygymapp.data.model.ScaleWeighIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Round-trip coverage for [ScaleWeighInParser], including its zero-value omit-on-write rule
 * for bmi/bodyFatPercent/leanMassPercent (mirrors [WorkoutParser]'s ECG/HRV field omission). */
class ScaleWeighInParserRoundTripTest {

    private fun roundTrip(weighIn: ScaleWeighIn): ScaleWeighIn =
        ScaleWeighInParser.fromMarkdown(ScaleWeighInParser.toMarkdown(weighIn))

    @Test
    fun `full weigh-in round-trips`() {
        val weighIn = ScaleWeighIn(
            id = "abcd1234",
            date = "2026-08-25",
            recordedAt = "2026-08-25T07:30:00",
            weightKg = 82.4,
            bmi = 25.1,
            bodyFatPercent = 18.6,
            leanMassPercent = 81.4,
        )

        val result = roundTrip(weighIn)

        assertEquals(weighIn.id, result.id)
        assertEquals(weighIn.date, result.date)
        assertEquals(weighIn.recordedAt, result.recordedAt)
        assertEquals(weighIn.weightKg, result.weightKg, 0.001)
        assertEquals(weighIn.bmi, result.bmi, 0.001)
        assertEquals(weighIn.bodyFatPercent, result.bodyFatPercent, 0.001)
        assertEquals(weighIn.leanMassPercent, result.leanMassPercent, 0.001)
    }

    @Test
    fun `zero-value composition fields are omitted on write but read back as zero`() {
        val weighIn = ScaleWeighIn(
            id = "abcd1234",
            date = "2026-08-25",
            recordedAt = "2026-08-25T07:30:00",
            weightKg = 82.4,
            bmi = 0.0,
            bodyFatPercent = 0.0,
            leanMassPercent = 0.0,
        )

        val markdown = ScaleWeighInParser.toMarkdown(weighIn)
        assertFalse(markdown.contains("bmi"))
        assertFalse(markdown.contains("bodyFatPercent"))
        assertFalse(markdown.contains("leanMassPercent"))

        val result = ScaleWeighInParser.fromMarkdown(markdown)
        assertEquals(0.0, result.bmi, 0.0)
        assertEquals(0.0, result.bodyFatPercent, 0.0)
        assertEquals(0.0, result.leanMassPercent, 0.0)
    }

    @Test
    fun `fromMarkdown on empty content returns empty defaults without throwing`() {
        val result = ScaleWeighInParser.fromMarkdown("")

        assertEquals("", result.id)
        assertEquals(0.0, result.weightKg, 0.0)
    }
}
