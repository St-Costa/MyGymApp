package com.mygymapp.ui.screen.exercise

import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.screen.strengthexercise.StrengthSetUi
import com.mygymapp.ui.screen.strengthexercise.applyStrengthDraft
import com.mygymapp.ui.screen.strengthexercise.decodeStrengthDraft
import com.mygymapp.ui.screen.strengthexercise.encodeStrengthDraft
import com.mygymapp.ui.screen.stretchexercise.StretchSetUi
import com.mygymapp.ui.screen.stretchexercise.applyStretchDraft
import com.mygymapp.ui.screen.stretchexercise.decodeStretchDraft
import com.mygymapp.ui.screen.stretchexercise.encodeStretchDraft
import com.mygymapp.ui.screen.superset.SupersetSetUi
import com.mygymapp.ui.screen.superset.applySupersetDraft
import com.mygymapp.ui.screen.superset.decodeSupersetDraft
import com.mygymapp.ui.screen.superset.encodeSupersetDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sprint 9: process-death drafts for in-workout sets. Codecs round-trip, malformed rows
 * void the whole draft (never a half-applied one), and size mismatches keep the base —
 * previous-set references always reload fresh, never from the draft.
 */
class ExerciseSetDraftsTest {

    // ── Strength ─────────────────────────────────────────────────────────────

    @Test
    fun `strength draft round-trips values and touches`() {
        val sets = listOf(
            StrengthSetUi(reps = 10, weight = 80.0, previousReps = 8, previousWeight = 75.0),
            StrengthSetUi(reps = 0, weight = 0.0, repsTouched = true),
        )

        val decoded = decodeStrengthDraft(encodeStrengthDraft(sets))!!

        assertEquals(2, decoded.size)
        assertEquals(10, decoded[0].reps)
        assertEquals(80.0, decoded[0].weight, 0.0)
        assertEquals(false, decoded[0].repsTouched)
        assertEquals(true, decoded[1].repsTouched)
    }

    @Test
    fun `strength overlay restores values and recomputes modified flags`() {
        val base = listOf(
            StrengthSetUi(reps = 8, weight = 75.0, previousReps = 8, previousWeight = 75.0),
        )
        val draft = decodeStrengthDraft(listOf("10|80.0|true|false"))!!

        val restored = applyStrengthDraft(base, draft).single()

        assertEquals(10, restored.reps)
        assertEquals(80.0, restored.weight, 0.0)
        assertEquals(true, restored.repsTouched)
        assertEquals(false, restored.weightTouched)
        // Derived flags recompute against the fresh previous, like init does.
        assertEquals(true, restored.repsModified)
        assertEquals(true, restored.weightModified)
        // Untouched references survive the overlay.
        assertEquals(8, restored.previousReps)
    }

    @Test
    fun `strength malformed or mismatched drafts are ignored`() {
        val base = listOf(StrengthSetUi(reps = 8, weight = 75.0))
        assertNull(decodeStrengthDraft(null))
        assertNull(decodeStrengthDraft(listOf("10|80.0|true")))
        assertNull(decodeStrengthDraft(listOf("x|80.0|true|false")))
        assertEquals(base, applyStrengthDraft(base, null))
        assertEquals(
            base,
            applyStrengthDraft(base, decodeStrengthDraft(listOf("1|2.0|false|false", "3|4.0|false|false"))),
        )
    }

    // ── Stretch ──────────────────────────────────────────────────────────────

    @Test
    fun `stretch draft round-trips done flags only`() {
        val sets = listOf(StretchSetUi(timeSeconds = 60, done = true), StretchSetUi(timeSeconds = 60))

        val decoded = decodeStretchDraft(encodeStretchDraft(sets))!!

        assertEquals(listOf(true, false), decoded)
    }

    @Test
    fun `stretch overlay keeps durations and restores done`() {
        val base = listOf(StretchSetUi(timeSeconds = 90), StretchSetUi(timeSeconds = 90))

        val restored = applyStretchDraft(base, listOf(true, false))

        assertEquals(90, restored[0].timeSeconds)
        assertEquals(true, restored[0].done)
        assertEquals(false, restored[1].done)
    }

    @Test
    fun `stretch malformed or mismatched drafts are ignored`() {
        val base = listOf(StretchSetUi(timeSeconds = 60))
        assertNull(decodeStretchDraft(null))
        assertNull(decodeStretchDraft(listOf("yes")))
        assertEquals(base, applyStretchDraft(base, null))
        assertEquals(base, applyStretchDraft(base, listOf(true, false)))
    }

    // ── Superset ─────────────────────────────────────────────────────────────

    private fun supersetSet() = SupersetSetUi(
        exerciseIndex = 1,
        exerciseName = "Bench",
        exerciseType = ExerciseType.FORZA,
        setIndex = 2,
        reps = 8,
        weight = 60.0,
        previousReps = 8,
        previousWeight = 55.0,
    )

    @Test
    fun `superset draft round-trips the mutable fields`() {
        val decoded = decodeSupersetDraft(encodeSupersetDraft(listOf(supersetSet())))!!.single()

        assertEquals(1, decoded.exerciseIndex)
        assertEquals(2, decoded.setIndex)
        assertEquals(8, decoded.reps)
        assertEquals(60.0, decoded.weight, 0.0)
    }

    @Test
    fun `superset overlay restores values and keeps identity plus previous`() {
        val base = listOf(supersetSet())
        val draft = decodeSupersetDraft(listOf("1|2|10|70.0|true|true|0|false"))!!

        val restored = applySupersetDraft(base, draft).single()

        assertEquals(10, restored.reps)
        assertEquals(70.0, restored.weight, 0.0)
        assertEquals(true, restored.repsTouched)
        assertEquals(true, restored.weightTouched)
        assertEquals(true, restored.repsModified)
        assertEquals(1, restored.exerciseIndex)
        assertEquals(2, restored.setIndex)
        assertEquals("Bench", restored.exerciseName)
        assertEquals(8, restored.previousReps)
        assertEquals(ExerciseType.FORZA, restored.exerciseType)
    }

    @Test
    fun `superset malformed or mismatched drafts are ignored`() {
        val base = listOf(supersetSet())
        assertNull(decodeSupersetDraft(null))
        assertNull(decodeSupersetDraft(listOf("1|2|10")))
        assertEquals(base, applySupersetDraft(base, null))
        assertEquals(base, applySupersetDraft(base, decodeSupersetDraft(emptyList())))
    }
}
