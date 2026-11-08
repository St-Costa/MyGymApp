package com.mygymapp.ui.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the explicit stopwatch mode + tick (ISM-15): [stopwatchModeFor] and
 * [advanceCountdown]. Each row mirrors one branch of the service paths they
 * were extracted from — including the loop-end wrap and the near-end periodic
 * suppression.
 */
class StopwatchStateTest {

    // ─── Mode ───

    @Test
    fun `positive extra selects a countdown, anything else is count-up`() {
        assertEquals(StopwatchMode.Countdown(60), stopwatchModeFor(60))
        assertEquals(StopwatchMode.CountUp, stopwatchModeFor(0))
        assertEquals(StopwatchMode.CountUp, stopwatchModeFor(-5))
    }

    // ─── Tick ───

    private fun state(startMs: Long = 0L, loop: Int = 1, lastVib: Int = 0) =
        CountdownState(startMs, loop, lastVib)

    @Test
    fun `loop end wraps state, buzzes triple, shows the full duration`() {
        val advanced = advanceCountdown(state(0L, 1, 0), nowMs = 60_000L, durationSeconds = 60)
        assertEquals(StopwatchBuzz.LOOP_END, advanced.buzz)
        assertEquals(CountdownState(60_000L, 2, 0), advanced.state)
        assertEquals(60, advanced.displayRemainingSeconds)
    }

    @Test
    fun `periodic records its second and shows the remainder`() {
        val advanced = advanceCountdown(state(0L, 2, 0), nowMs = 30_000L, durationSeconds = 90)
        assertEquals(StopwatchBuzz.PERIODIC, advanced.buzz)
        assertEquals(CountdownState(0L, 2, 30), advanced.state)
        assertEquals(60, advanced.displayRemainingSeconds)
    }

    @Test
    fun `quiet tick changes nothing and counts down the display`() {
        val before = state(0L, 1, 0)
        val advanced = advanceCountdown(before, nowMs = 10_000L, durationSeconds = 60)
        assertEquals(StopwatchBuzz.NONE, advanced.buzz)
        assertEquals(before, advanced.state)
        assertEquals(50, advanced.displayRemainingSeconds)
    }

    @Test
    fun `periodic overlapping the loop end is skipped`() {
        // 30s buzz inside a 31s loop would still be running at the wrap —
        // only the triple fires, display shows the last second.
        val advanced = advanceCountdown(state(0L, 1, 0), nowMs = 30_000L, durationSeconds = 31)
        assertEquals(StopwatchBuzz.NONE, advanced.buzz)
        assertEquals(CountdownState(0L, 1, 0), advanced.state)
        assertEquals(1, advanced.displayRemainingSeconds)
    }

    @Test
    fun `second loop wraps on its own anchor`() {
        val advanced = advanceCountdown(state(60_000L, 2, 0), nowMs = 120_000L, durationSeconds = 60)
        assertEquals(StopwatchBuzz.LOOP_END, advanced.buzz)
        assertEquals(3, advanced.state.loopCount)
        assertTrue(advanced.state.loopStartElapsed == 120_000L)
    }
}
