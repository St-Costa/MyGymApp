package com.mygymapp.ui.service

/** Which vibration [StopwatchService] fires on a given tick. */
internal enum class StopwatchBuzz { NONE, PERIODIC, LOOP_END }

/**
 * Pure vibration schedule for [StopwatchService], kept out of the service so it is
 * unit-testable. `lastPeriodicAt` is the second (within the current loop, or since start
 * in count-up mode) of the last periodic buzz — `0` at start and after every loop wrap,
 * so the first periodic buzz lands at exactly 30s, not 29s.
 */
internal object StopwatchBuzzSchedule {
    const val PERIODIC_EVERY_S = 30

    /** Length of the periodic buzz (`vibrate()` is a 2000 ms one-shot). */
    const val PERIODIC_BUZZ_S = 2

    /**
     * Countdown-loop mode (stretch). The loop-end triple always wins: on the loop's last
     * second it replaces the periodic buzz, and a periodic buzz that would still be running
     * when the loop ends (e.g. at 30s of a 31s stretch) is skipped altogether, so the end of
     * a stretch is only ever the three short buzzes.
     */
    fun countdown(loopElapsed: Int, duration: Int, lastPeriodicAt: Int): StopwatchBuzz = when {
        loopElapsed >= duration -> StopwatchBuzz.LOOP_END
        loopElapsed - lastPeriodicAt >= PERIODIC_EVERY_S &&
            duration - loopElapsed > PERIODIC_BUZZ_S -> StopwatchBuzz.PERIODIC
        else -> StopwatchBuzz.NONE
    }

    /** Legacy count-up mode (superset): one buzz every 30s. */
    fun countUp(elapsed: Int, lastPeriodicAt: Int): Boolean =
        elapsed - lastPeriodicAt >= PERIODIC_EVERY_S
}
