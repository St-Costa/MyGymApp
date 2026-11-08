package com.mygymapp.ui.screen.main

import com.mygymapp.data.model.Routine
import com.mygymapp.data.model.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Pins the explicit home-load decisions (ISM-23): [refreshCommand],
 * [shouldEmitScaffold], [scheduleCellSession] and [openRoutineForColumn].
 */
class HomeLoadDecisionsTest {

    // ─── Refresh coalescing ───

    @Test
    fun `force always cancels and restarts, even with nothing running`() {
        assertEquals(RefreshCommand.CANCEL_AND_RESTART, refreshCommand(false, false, force = true))
        assertEquals(RefreshCommand.CANCEL_AND_RESTART, refreshCommand(true, true, force = true))
        assertEquals(RefreshCommand.CANCEL_AND_RESTART, refreshCommand(true, false, force = true))
    }

    @Test
    fun `plain call joins a live load, starts otherwise`() {
        assertEquals(RefreshCommand.JOIN_RUNNING, refreshCommand(true, true, force = false))
        assertEquals(RefreshCommand.START, refreshCommand(true, false, force = false))
        assertEquals(RefreshCommand.START, refreshCommand(false, false, force = false))
    }

    // ─── Scaffold gate ───

    @Test
    fun `scaffold emits only before the first complete state`() {
        assertTrue(shouldEmitScaffold(false))
        assertEquals(false, shouldEmitScaffold(true))
    }

    // ─── Schedule cells ───

    private val monday = LocalDate.parse("2026-08-24")

    private fun session(id: String, date: String) = WorkoutSession(
        id = id,
        routineId = "rt-1",
        routineName = "Push",
        date = date,
    )

    @Test
    fun `today column never reads the per-date map`() {
        val byDate = mapOf(monday.toString() to session("s-today", monday.toString()))
        // Column 0 is Monday = today here: null even with a session filed that day.
        assertNull(scheduleCellSession(0, 0, monday, byDate))
    }

    @Test
    fun `other columns read their own date`() {
        val tue = monday.plusDays(1).toString()
        val byDate = mapOf(tue to session("s-tue", tue))
        assertEquals("s-tue", scheduleCellSession(1, 0, monday, byDate)?.id)
        assertNull(scheduleCellSession(2, 0, monday, byDate))
    }

    @Test
    fun `open routine is the first not completed today, else the first`() {
        val routines = listOf(
            Routine(id = "rt-1", name = "Push"),
            Routine(id = "rt-2", name = "Pull"),
        )
        assertEquals("rt-1", openRoutineForColumn(routines, emptySet())?.id)
        assertEquals("rt-2", openRoutineForColumn(routines, setOf("rt-1"))?.id)
        assertEquals("rt-1", openRoutineForColumn(routines, setOf("rt-1", "rt-2"))?.id)
        assertNull(openRoutineForColumn(emptyList(), emptySet()))
    }
}
