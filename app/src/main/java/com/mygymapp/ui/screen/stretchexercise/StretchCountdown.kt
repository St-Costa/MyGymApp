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

/**
 * Display-side snapshot of the stretch/superset countdown loop (ISM-17/18).
 *
 * `StretchCountdown` is the tick math; this is the start/stop/tick *transition*
 * both exercise ViewModels (`StretchExerciseViewModel.toggleStopwatch`,
 * `SupersetViewModel.toggleStopwatch`) previously reimplemented inline — same
 * shape, drift-prone. The foreground-service buzzing stays in
 * `StopwatchService` (driven by the Screen in lockstep); timer `Job`
 * management stays in the ViewModels.
 */
data class StretchTimerSnapshot(
    val countdownDuration: Int,
    val remainingSeconds: Int,
    val loopCount: Int,
)

/** Start: normalize the duration (a stale 0 becomes 1s) and show it full, loop 1. */
fun startStretchTimer(durationSeconds: Int): StretchTimerSnapshot {
    val started = StretchCountdown.start(durationSeconds)
    return StretchTimerSnapshot(
        countdownDuration = started.remainingSeconds,
        remainingSeconds = started.remainingSeconds,
        loopCount = started.loopCount,
    )
}

/** Stop: back to the full duration, loop 1 (running flag lives in the caller). */
fun stopStretchTimer(durationSeconds: Int): StretchTimerSnapshot = StretchTimerSnapshot(
    countdownDuration = durationSeconds,
    remainingSeconds = durationSeconds,
    loopCount = 1,
)

/** One display tick; the loop-completion flag is display-irrelevant here (see `tick`). */
fun tickStretchTimer(
    countdownDuration: Int,
    remainingSeconds: Int,
    loopCount: Int,
): StretchTimerSnapshot {
    val (next, _) = StretchCountdown(remainingSeconds, loopCount).tick(countdownDuration)
    return StretchTimerSnapshot(
        countdownDuration = countdownDuration,
        remainingSeconds = next.remainingSeconds,
        loopCount = next.loopCount,
    )
}
