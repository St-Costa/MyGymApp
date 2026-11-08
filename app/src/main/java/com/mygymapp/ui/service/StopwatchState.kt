package com.mygymapp.ui.service

/**
 * Explicit run mode + tick transition for [StopwatchService] (ISM-15).
 *
 * Before this file existed the mode was a nullable `countdownDuration`
 * (`null` = legacy count-up) and the countdown state update lived inline in
 * the tick runnable next to the vibration side effects. [StopwatchMode] is
 * the single definition of the mode — including the silent fallback
 * (`EXTRA_DURATION_SECONDS <= 0` means count-up, never an error) — and
 * [advanceCountdown] is the single definition of one tick's state update
 * (driven by the already-pure [StopwatchBuzzSchedule]). The service only
 * executes: vibrate per buzz, repost the tick, push the notification.
 *
 * Preserved quirks, deliberately unchanged: an unknown intent action leaves a
 * frozen service in its old mode (no `else` branch — same as before);
 * `ACTION_STOP` does not reset `loopStartElapsed` (the next START overwrites
 * it); an in-flight loop-end triple is not cancelled on STOP.
 */

/** Count-up (legacy stopwatch) vs countdown-loop (stretch) mode. */
internal sealed interface StopwatchMode {
    data object CountUp : StopwatchMode
    data class Countdown(val durationSeconds: Int) : StopwatchMode
}

/** `EXTRA_DURATION_SECONDS` > 0 selects a countdown loop; anything else is count-up. */
internal fun stopwatchModeFor(extraSeconds: Int): StopwatchMode =
    if (extraSeconds > 0) StopwatchMode.Countdown(extraSeconds) else StopwatchMode.CountUp

/** Countdown-loop mutable state, mirrored from the service fields each tick. */
internal data class CountdownState(
    val loopStartElapsed: Long,
    val loopCount: Int,
    val lastVibrationAt: Int,
)

/** One tick's outcome: the next state, what to vibrate, what to display. */
internal data class CountdownAdvance(
    val state: CountdownState,
    val buzz: StopwatchBuzz,
    val displayRemainingSeconds: Int,
)

/**
 * Mirrors the countdown branch of the tick runnable exactly: the loop-end
 * triple wins and wraps (`loopElapsed` restarts at 0, so the display shows
 * the full duration); a periodic buzz only records its second; otherwise the
 * display is `duration - loopElapsed` and nothing changes.
 */
internal fun advanceCountdown(
    state: CountdownState,
    nowMs: Long,
    durationSeconds: Int,
): CountdownAdvance {
    val loopElapsed = ((nowMs - state.loopStartElapsed) / 1000).toInt()
    return when (StopwatchBuzzSchedule.countdown(loopElapsed, durationSeconds, state.lastVibrationAt)) {
        StopwatchBuzz.LOOP_END -> CountdownAdvance(
            state = state.copy(
                loopStartElapsed = nowMs,
                loopCount = state.loopCount + 1,
                lastVibrationAt = 0,
            ),
            buzz = StopwatchBuzz.LOOP_END,
            displayRemainingSeconds = durationSeconds,
        )
        StopwatchBuzz.PERIODIC -> CountdownAdvance(
            state = state.copy(lastVibrationAt = loopElapsed),
            buzz = StopwatchBuzz.PERIODIC,
            displayRemainingSeconds = durationSeconds - loopElapsed,
        )
        StopwatchBuzz.NONE -> CountdownAdvance(
            state = state,
            buzz = StopwatchBuzz.NONE,
            displayRemainingSeconds = durationSeconds - loopElapsed,
        )
    }
}
