package com.mygymapp.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the result codecs (ISM-04): the superset list round-trips exactly
 * (single slot, comma-joined — including the empty-string edge, which yields
 * one safe no-op entry), and every key (plus the per-side picker keys) is
 * distinct, so a result can never land in another key's consumer.
 */
class NavResultsTest {

    @Test
    fun `superset ids round-trip through encode and parse`() {
        val ids = listOf("ex-aaaabbbb", "ex-ccccdddd", "ex-eeeeffff")
        assertEquals(ids, parseSupersetResult(encodeSupersetResult(ids)))
    }

    @Test
    fun `single-member chain round-trips`() {
        assertEquals(listOf("ex-aaaabbbb"), parseSupersetResult(encodeSupersetResult(listOf("ex-aaaabbbb"))))
    }

    @Test
    fun `empty payload decodes to one empty entry (safe no-op downstream)`() {
        // markExerciseCompleted("") bails on the reload check — pinned so a
        // future "filter blanks" change stays a conscious decision, not drift.
        assertEquals(listOf(""), parseSupersetResult(""))
    }

    @Test
    fun `result keys are all distinct`() {
        val keys = setOf(
            NavResultKeys.COMPLETED_EXERCISE_ID,
            NavResultKeys.COMPLETED_SUPERSET_IDS,
            NavResultKeys.SWITCHED_EXERCISE_IDS,
            NavResultKeys.PICKED_EXERCISE_ID,
            NavResultKeys.pickedSideKey(1),
            NavResultKeys.pickedSideKey(2),
            NavResultKeys.pickedSideKey(3),
        )
        assertEquals(7, keys.size)
    }

    @Test
    fun `per-side picker keys follow the 1-based convention`() {
        assertEquals("pickedExerciseIdSide1", NavResultKeys.pickedSideKey(1))
        assertTrue(NavResultKeys.pickedSideKey(2).endsWith("2"))
    }
}
