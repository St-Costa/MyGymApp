package com.mygymapp.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 3: `switchedExerciseIds` is a queue (`"old,new;old,new"`), not a single slot —
 * two switches before returning to the list must not overwrite each other — and every
 * `savedStateHandle` result is read reactively (`getStateFlow`, covered by construction
 * in `AppNavigation`, not unit-testable here).
 */
class SwitchResultQueueTest {

    @Test
    fun `first switch seeds the queue`() {
        assertEquals("old1,new1", appendSwitchResult(null, "old1", "new1"))
        assertEquals("old1,new1", appendSwitchResult("", "old1", "new1"))
    }

    @Test
    fun `second switch appends instead of overwriting`() {
        val queued = appendSwitchResult(appendSwitchResult(null, "old1", "new1"), "old2", "new2")

        assertEquals("old1,new1;old2,new2", queued)
        assertEquals(
            listOf("old1" to "new1", "old2" to "new2"),
            parseSwitchResults(queued),
        )
    }

    @Test
    fun `single switch round-trips`() {
        assertEquals(listOf("a" to "b"), parseSwitchResults("a,b"))
    }

    @Test
    fun `malformed pairs are skipped, not crashed on`() {
        assertTrue(parseSwitchResults("").isEmpty())
        assertEquals(listOf("a" to "b"), parseSwitchResults("junk;a,b;,;c,"))
    }
}
