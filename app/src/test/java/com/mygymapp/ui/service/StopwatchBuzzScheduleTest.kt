package com.mygymapp.ui.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drives [StopwatchBuzzSchedule] the way `StopwatchService.tickRunnable` does (one call per
 * second, `lastPeriodicAt` reset to 0 on wrap) and records which second fired what.
 */
class StopwatchBuzzScheduleTest {

    private fun simulateCountdown(duration: Int, seconds: Int): List<Pair<Int, StopwatchBuzz>> {
        val fired = mutableListOf<Pair<Int, StopwatchBuzz>>()
        var loopStart = 0
        var last = 0
        for (t in 1..seconds) {
            val buzz = StopwatchBuzzSchedule.countdown(t - loopStart, duration, last)
            when (buzz) {
                StopwatchBuzz.LOOP_END -> { loopStart = t; last = 0 }
                StopwatchBuzz.PERIODIC -> last = t - loopStart
                StopwatchBuzz.NONE -> Unit
            }
            if (buzz != StopwatchBuzz.NONE) fired += t to buzz
        }
        return fired
    }

    @Test
    fun `one minute stretch - periodic at 30s, only the triple at 60s`() {
        assertEquals(
            listOf(
                30 to StopwatchBuzz.PERIODIC,
                60 to StopwatchBuzz.LOOP_END,
                90 to StopwatchBuzz.PERIODIC,
                120 to StopwatchBuzz.LOOP_END,
            ),
            simulateCountdown(duration = 60, seconds = 120),
        )
    }

    @Test
    fun `periodic buzz never fires at 29s nor on the loop's last second`() {
        val fired = simulateCountdown(duration = 60, seconds = 60).map { it.first }
        assertEquals(listOf(30, 60), fired)
    }

    @Test
    fun `30s stretch buzzes three times, not once`() {
        assertEquals(
            listOf(30 to StopwatchBuzz.LOOP_END, 60 to StopwatchBuzz.LOOP_END),
            simulateCountdown(duration = 30, seconds = 60),
        )
    }

    @Test
    fun `periodic buzz still running at loop end is skipped`() {
        // 2s buzz at 30 would overlap the triple at 31 or 32.
        assertEquals(listOf(31 to StopwatchBuzz.LOOP_END), simulateCountdown(31, 31))
        assertEquals(listOf(32 to StopwatchBuzz.LOOP_END), simulateCountdown(32, 32))
        // At 33 it has finished by the time the triple fires.
        assertEquals(
            listOf(30 to StopwatchBuzz.PERIODIC, 33 to StopwatchBuzz.LOOP_END),
            simulateCountdown(33, 33),
        )
    }

    @Test
    fun `count-up buzzes every 30s starting at 30`() {
        var last = 0
        val fired = (1..90).filter { t ->
            StopwatchBuzzSchedule.countUp(t, last).also { if (it) last = t }
        }
        assertEquals(listOf(30, 60, 90), fired)
    }
}
