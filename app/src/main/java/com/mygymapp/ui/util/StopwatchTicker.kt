package com.mygymapp.ui.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A resettable 1 Hz stopwatch usable from a ViewModel: toggle() starts/stops the
 * tick loop, cancel() tears it down (call from onCleared). Extracted from
 * StretchExerciseViewModel and SupersetViewModel where the same
 * `while(true){delay(1000);…}` loop was copy-pasted.
 */
class StopwatchTicker(
    private val scope: CoroutineScope,
    private val onElapsed: (Int) -> Unit,
    private val onRunningChange: (Boolean) -> Unit,
) {
    private var job: Job? = null

    val isRunning: Boolean get() = job != null

    fun toggle() {
        if (job != null) {
            cancel()
            onRunningChange(false)
        } else {
            onElapsed(0)
            onRunningChange(true)
            var elapsed = 0
            job = scope.launch {
                while (true) {
                    delay(1000)
                    elapsed++
                    onElapsed(elapsed)
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
