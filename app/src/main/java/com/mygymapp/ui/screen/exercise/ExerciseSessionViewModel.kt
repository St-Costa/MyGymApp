package com.mygymapp.ui.screen.exercise

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Shared plumbing for the per-exercise "do the sets, tap Complete, navigate back" screens
 * ([StrengthExerciseViewModel][com.mygymapp.ui.screen.strengthexercise.StrengthExerciseViewModel],
 * [StretchExerciseViewModel][com.mygymapp.ui.screen.stretchexercise.StretchExerciseViewModel],
 * [SupersetViewModel][com.mygymapp.ui.screen.superset.SupersetViewModel]).
 *
 * All three had a byte-for-byte copy of the same `completionSaved` + `clearScope` +
 * `onCleared()` teardown; small divergences between the copies were an actual source of bugs.
 * This centralises it:
 *
 *  - [clearScope] — a `SupervisorJob` scope that outlives ViewModel teardown, used for the
 *    final disk write so a process death between the tap and the write completing can't lose
 *    the `completed = true` (see docs/CONVENTIONS.md#completionsaved-pattern and
 *    #oncleared-save).
 *  - [markCompletionAndSave] — run the completion write on [clearScope], tracked as
 *    [completionJob], and flip [completionSaved] once it lands. The screen observes
 *    [completionSaved] and only then calls `onComplete()` (navigates back), so the write is
 *    always on disk before the active-routine screen reloads.
 *  - [markSwitched] — "Switch exercise": the swap write already happened elsewhere; this only
 *    marks the instance done so [onCleared] doesn't re-save a slot that no longer exists.
 *  - [onCleared] — if the exercise was completed/switched, join [completionJob] (so `cancel`
 *    can't abort a write still in flight) then tear the scope down; otherwise delegate to
 *    [saveProgressOnExit] for the back-navigation "save as incomplete" write.
 *
 * Subclasses implement only [saveProgressOnExit]; anything screen-specific in the completion
 * write is passed as the `block` lambda to [markCompletionAndSave].
 */
abstract class ExerciseSessionViewModel : ViewModel() {

    protected val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set once the exercise/superset is completed or switched — stops [onCleared] re-saving. */
    private var finished = false
    private var completionJob: Job? = null

    private val _completionSaved = MutableStateFlow(false)
    val completionSaved: StateFlow<Boolean> = _completionSaved

    /**
     * Runs [block] (the completion write) on [clearScope], then flips [completionSaved].
     * Idempotent-ish: a second call is ignored, since the screen navigates away on the first.
     */
    protected fun markCompletionAndSave(block: suspend () -> Unit) {
        if (finished) return
        finished = true
        completionJob = clearScope.launch {
            block()
            _completionSaved.value = true
        }
    }

    /**
     * Mark this instance done without a completion write — for "Switch exercise", where the
     * swap was already persisted by the shared [com.mygymapp.data.model.withExerciseSwitched]
     * path and this slot/VM is now obsolete.
     */
    protected fun markSwitched() {
        finished = true
    }

    /**
     * Called from [onCleared] only on a plain back-navigation (not completed, not switched):
     * persist whatever is on screen as an *incomplete* slot. Implementations must call
     * [clearScope].cancel() when their write finishes (mirrors the pattern the completed path
     * uses here).
     */
    protected abstract fun saveProgressOnExit()

    /** Subclasses that need extra teardown (timers) override, call `super.onCleared()` first. */
    override fun onCleared() {
        super.onCleared()
        if (finished) {
            clearScope.launch {
                try {
                    completionJob?.join()
                } finally {
                    clearScope.cancel()
                }
            }
            return
        }
        saveProgressOnExit()
    }
}
