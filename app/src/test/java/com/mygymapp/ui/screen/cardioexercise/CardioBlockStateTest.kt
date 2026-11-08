package com.mygymapp.ui.screen.cardioexercise

import com.mygymapp.data.model.ExerciseSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Pins the explicit cardio block lifecycle (ISM-19): [cardioButtonState],
 * [cardioCompletion], [closeOpenCardioBlocks], [exitSaveBlocks] and
 * [cardioBlockAverage]. Each row mirrors one branch of the ViewModel/Screen
 * paths they were extracted from — a drift in the skip-vs-performed line, the
 * kill-recovery close, or the exit-save guarantee fails here first.
 */
class CardioBlockStateTest {

    // ─── Button ───

    @Test
    fun `button priority is running over done over idle`() {
        assertEquals(CardioButtonState.RUNNING, cardioButtonState(true, true))
        assertEquals(CardioButtonState.RUNNING, cardioButtonState(true, false))
        assertEquals(CardioButtonState.DONE, cardioButtonState(false, true))
        assertEquals(CardioButtonState.IDLE, cardioButtonState(false, false))
    }

    // ─── Completion outcome ───

    @Test
    fun `only idle-with-nothing closes as skipped`() {
        assertEquals(CardioCompletion.SKIPPED, cardioCompletion(false, false))
        assertEquals(CardioCompletion.PERFORMED, cardioCompletion(true, false))
        assertEquals(CardioCompletion.PERFORMED, cardioCompletion(false, true))
        assertEquals(CardioCompletion.PERFORMED, cardioCompletion(true, true))
    }

    // ─── Kill-recovery ───

    private fun block(startedAt: String, endedAt: String) = ExerciseSet.Cardio(
        startedAt = startedAt,
        endedAt = endedAt,
        avgHr = 120,
        maxHr = 140,
    )

    @Test
    fun `open block is closed with now, everything else untouched`() {
        val blocks = listOf(
            block("2026-08-20T10:00:00", ""),
            block("2026-08-19T10:00:00", "2026-08-19T10:30:00"),
            block("", ""),
        )
        val result = closeOpenCardioBlocks(blocks, "2026-08-20T11:00:00")

        assertEquals("2026-08-20T11:00:00", result[0].endedAt)
        assertEquals(120, result[0].avgHr) // partial HR data kept, not zeroed
        assertEquals(blocks[1], result[1])
        assertEquals(blocks[2], result[2])
        assertTrue(closeOpenCardioBlocks(emptyList(), "2026-08-20T11:00:00").isEmpty())
    }

    // ─── Exit save ───

    @Test
    fun `back-navigation never force-closes the running block`() {
        val done = listOf(block("2026-08-20T09:00:00", "2026-08-20T09:30:00"))
        val start = LocalDateTime.parse("2026-08-20T10:00:00")
        val now = LocalDateTime.parse("2026-08-20T10:05:00")

        val result = exitSaveBlocks(true, done, start, now)
        assertEquals(2, result.size)
        assertEquals(done.single(), result[0])
        assertEquals("2026-08-20T10:00", result[1].startedAt)
        assertEquals("", result[1].endedAt)
        assertEquals(0, result[1].avgHr)
        assertEquals(0, result[1].maxHr)
    }

    @Test
    fun `exit save without a start timestamp falls back to now`() {
        val result = exitSaveBlocks(true, emptyList(), null, LocalDateTime.parse("2026-08-20T10:05:00"))
        assertEquals("2026-08-20T10:05", result.single().startedAt)
    }

    @Test
    fun `exit save at rest persists blocks as-is`() {
        val done = listOf(block("2026-08-20T09:00:00", "2026-08-20T09:30:00"))
        assertEquals(done, exitSaveBlocks(false, done, null, LocalDateTime.now()))
        assertTrue(exitSaveBlocks(false, emptyList(), null, LocalDateTime.now()).isEmpty())
    }

    // ─── Average ───

    @Test
    fun `block average is exact division, zero without samples`() {
        assertEquals(120, cardioBlockAverage(360L, 3))
        assertEquals(0, cardioBlockAverage(0L, 0))
    }
}
