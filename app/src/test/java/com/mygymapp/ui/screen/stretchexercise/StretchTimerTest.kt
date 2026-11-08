package com.mygymapp.ui.screen.stretchexercise

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the shared display-timer transitions (ISM-17/18): [startStretchTimer],
 * [stopStretchTimer] and [tickStretchTimer] — the exact start/stop/tick
 * both exercise ViewModels run, so the two copies can't drift apart again.
 */
class StretchTimerTest {

    @Test
    fun `start shows the full normalized duration at loop one`() {
        assertEquals(
            StretchTimerSnapshot(60, 60, 1),
            startStretchTimer(60),
        )
    }

    @Test
    fun `start normalizes a stale zero duration instead of showing zero`() {
        assertEquals(
            StretchTimerSnapshot(1, 1, 1),
            startStretchTimer(0),
        )
    }

    @Test
    fun `stop returns to the full current duration at loop one`() {
        assertEquals(
            StretchTimerSnapshot(60, 60, 1),
            stopStretchTimer(60),
        )
        assertEquals(
            StretchTimerSnapshot(23, 23, 1),
            stopStretchTimer(23),
        )
    }

    @Test
    fun `tick decrements and wraps with a loop bump`() {
        assertEquals(
            StretchTimerSnapshot(60, 59, 1),
            tickStretchTimer(60, 60, 1),
        )
        assertEquals(
            StretchTimerSnapshot(60, 60, 2),
            tickStretchTimer(60, 1, 1),
        )
    }

    @Test
    fun `a full start-to-wrap loop matches the countdown math`() {
        var state = startStretchTimer(3)
        state = tickStretchTimer(state.countdownDuration, state.remainingSeconds, state.loopCount)
        assertEquals(StretchTimerSnapshot(3, 2, 1), state)
        state = tickStretchTimer(state.countdownDuration, state.remainingSeconds, state.loopCount)
        assertEquals(StretchTimerSnapshot(3, 1, 1), state)
        state = tickStretchTimer(state.countdownDuration, state.remainingSeconds, state.loopCount)
        assertEquals(StretchTimerSnapshot(3, 3, 2), state)
    }
}
