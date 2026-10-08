package com.mygymapp.ui.screen.stretchexercise

/**
 * Pure countdown-loop state machine behind the stretch timer.
 *
 * The stretch timer counts down from the set duration and restarts automatically when it
 * hits zero (loop), buzzing once every 30s inside the loop and three times fast on each
 * loop completion — the buzzing itself lives in [com.mygymapp.ui.service.StopwatchService]
 * (which owns the background case), this is only the display-state math shared with the
 * ViewModel tick. Kept framework-free so it stays unit-testable.
 */
data class StretchCountdown(
    val remainingSeconds: Int,
    val loopCount: Int,
) {
    companion object {
        fun start(durationSeconds: Int) = StretchCountdown(
            remainingSeconds = durationSeconds.coerceAtLeast(1),
            loopCount = 1,
        )
    }
}

/**
 * Advances the countdown by one second. Returns the new state plus `true` when a loop
 * just completed (remaining wrapped back to the full [durationSeconds] and [loopCount]
 * incremented) — `false` for a plain decrement.
 */
fun StretchCountdown.tick(durationSeconds: Int): Pair<StretchCountdown, Boolean> {
    val duration = durationSeconds.coerceAtLeast(1)
    return if (remainingSeconds - 1 <= 0) {
        StretchCountdown(remainingSeconds = duration, loopCount = loopCount + 1) to true
    } else {
        copy(remainingSeconds = remainingSeconds - 1) to false
    }
}
