package com.mygymapp.ui.screen.stretchexercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.withExerciseSetsOnExit
import com.mygymapp.data.model.withExerciseStarted
import com.mygymapp.data.model.withExerciseSwitched
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.data.util.Breadcrumbs
import com.mygymapp.ui.screen.exercise.ExerciseSessionViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StretchSetUi(
    val timeSeconds: Int = 0,
    val done: Boolean = false,
)

data class StretchExerciseUiState(
    val exercise: Exercise? = null,
    val sets: List<StretchSetUi> = emptyList(),
    val description: String = "",
    val isLoading: Boolean = true,
    val isStopwatchRunning: Boolean = false,
    // Countdown-loop timer: counts down from [countdownDuration] (the set duration) and
    // restarts automatically at zero — sets stay manual (toggled via checkbox), the loop
    // only drives the display while StopwatchService owns buzz + notification.
    val countdownDuration: Int = 60,
    val remainingSeconds: Int = 60,
    val loopCount: Int = 1,
    // See StrengthExerciseUiState for the "Switch exercise" fields' semantics.
    val switchEligible: Boolean = false,
    val excludeIds: Set<String> = emptySet(),
)

@HiltViewModel
class StretchExerciseViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository,
) : ExerciseSessionViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""
    private val exerciseId: String = savedStateHandle["exerciseId"] ?: ""

    private val _uiState = MutableStateFlow(StretchExerciseUiState())
    val uiState: StateFlow<StretchExerciseUiState> = _uiState

    private var currentSession: WorkoutSession? = null
    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            val exercise = exerciseRepository.getById(exerciseId) ?: return@launch

            // The active session is always dated today; fetch it straight by id.
            var loadedSession = workoutRepository.getSession(sessionId, java.time.LocalDate.now())
            // Start the wall-clock window now: the row badge and the section estimate count
            // the real time spent stretching — holds plus the recovery pauses between them —
            // not just the configured hold seconds.
            if (loadedSession != null) {
                val started = loadedSession.withExerciseStarted(
                    exerciseId,
                    java.time.LocalDateTime.now().toString(),
                )
                if (started !== loadedSession) loadedSession = workoutRepository.save(started)
            }
            val session = loadedSession
            currentSession = session

            val workoutExercise = session?.exercises?.find { it.exerciseId == exerciseId }
            val existingSets = workoutExercise?.sets
                ?.filterIsInstance<ExerciseSet.Stretch>() ?: emptyList()

            // Restore in-progress set values (including done state) from current session
            val sets = existingSets.map { set ->
                StretchSetUi(timeSeconds = set.timeSeconds, done = set.done)
            }.ifEmpty {
                listOf(StretchSetUi(timeSeconds = 60))
            }

            // Process-death restore: done-toggles newer than the last disk save survive
            // in the SavedStateHandle — overlay them over the just-loaded sets. Running
            // timers are deliberately NOT restored (a mid-countdown kill restarts the
            // countdown from full on next open; fabricating elapsed time would lie).
            val restoredSets = applyStretchDraft(
                sets,
                decodeStretchDraft(savedStateHandle.get<ArrayList<String>>(DRAFT_STRETCH_SETS)),
            )

            // Switch is offered for a NORMAL or fixed-daily slot — not warmup — matching
            // WorkoutExercise.isSwitchEligible().
            val isDaily = workoutExercise?.isDaily ?: false
            val isWarmup = (workoutExercise?.excludeFromTonnage ?: false) && !isDaily
            val switchEligible = workoutExercise?.isSwitchEligible() == true && !isWarmup

            // All sets share the routine's timePerSetSeconds (the editor has no per-set
            // duration) — the countdown runs off the first set's duration.
            val duration = sets.firstOrNull()?.timeSeconds?.coerceAtLeast(1) ?: 60

            _uiState.value = StretchExerciseUiState(
                exercise = exercise,
                sets = restoredSets,
                description = exercise.notes,
                isLoading = false,
                countdownDuration = duration,
                remainingSeconds = duration,
                switchEligible = switchEligible,
                excludeIds = session?.exercises?.map { it.exerciseId }?.toSet() ?: emptySet(),
            )
        }
    }

    fun toggleSetDone(setIndex: Int) {
        crumb("set ${setIndex + 1} done")
        val sets = _uiState.value.sets.toMutableList()
        if (setIndex in sets.indices && !sets[setIndex].done) {
            sets[setIndex] = sets[setIndex].copy(done = true)
            _uiState.value = _uiState.value.copy(sets = sets)
            // Single choke point for set mutations — mirror for process-death restore.
            savedStateHandle[DRAFT_STRETCH_SETS] = encodeStretchDraft(sets)
        }
    }

    /**
     * Appends a new hold row ("+ Add set") with the same duration as the others,
     * still unticked — planning, not performing: it only counts once the lifter ticks it.
     * The neutral hold is appended to the session on disk right away so the row survives
     * back-out/process death and the active-routine time estimate can count it on return.
     */
    fun addSet() {
        val sets = _uiState.value.sets
        val duration = sets.firstOrNull()?.timeSeconds?.coerceAtLeast(1) ?: 60
        crumb("add set ${sets.size + 1}")
        val updated = sets + StretchSetUi(timeSeconds = duration, done = false)
        _uiState.value = _uiState.value.copy(sets = updated)
        savedStateHandle[DRAFT_STRETCH_SETS] = encodeStretchDraft(updated)
        viewModelScope.launch {
            val fresh = workoutRepository.getSession(sessionId, java.time.LocalDate.now())
                ?: currentSession ?: return@launch
            val entry = fresh.exercises.find { it.exerciseId == exerciseId } ?: return@launch
            val saved = workoutRepository.save(
                fresh.withExerciseSetsOnExit(
                    exerciseId,
                    entry.sets + ExerciseSet.Stretch(timeSeconds = duration, done = false),
                )
            )
            currentSession = saved
        }
    }

    /**
     * Removes the last hold row, but only while it is still unticked — ticked work is never
     * deleted by navigation chrome. No-op with a single row or a ticked trailing hold.
     */
    fun removeLastSet() {
        val sets = _uiState.value.sets
        if (sets.size <= 1) return
        if (sets.last().done) return
        crumb("remove set ${sets.size}")
        val updated = sets.dropLast(1)
        _uiState.value = _uiState.value.copy(sets = updated)
        savedStateHandle[DRAFT_STRETCH_SETS] = encodeStretchDraft(updated)
        viewModelScope.launch {
            val fresh = workoutRepository.getSession(sessionId, java.time.LocalDate.now())
                ?: currentSession ?: return@launch
            val entry = fresh.exercises.find { it.exerciseId == exerciseId } ?: return@launch
            val last = entry.sets.lastOrNull() as? ExerciseSet.Stretch ?: return@launch
            if (last.done) return@launch
            val saved = workoutRepository.save(
                fresh.withExerciseSetsOnExit(exerciseId, entry.sets.dropLast(1))
            )
            currentSession = saved
        }
    }

    fun toggleStopwatch() {
        val wasRunning = _uiState.value.isStopwatchRunning
        crumb(if (wasRunning) "stopwatch stop" else "stopwatch start")
        if (wasRunning) {
            timerJob?.cancel()
            timerJob = null
            val stopped = stopStretchTimer(_uiState.value.countdownDuration)
            _uiState.value = _uiState.value.copy(
                isStopwatchRunning = false,
                remainingSeconds = stopped.remainingSeconds,
                loopCount = stopped.loopCount,
            )
        } else {
            timerJob?.cancel()
            val started = startStretchTimer(_uiState.value.countdownDuration)
            _uiState.value = _uiState.value.copy(
                isStopwatchRunning = true,
                countdownDuration = started.countdownDuration,
                remainingSeconds = started.remainingSeconds,
                loopCount = started.loopCount,
            )
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    val current = _uiState.value
                    val ticked = tickStretchTimer(
                        current.countdownDuration,
                        current.remainingSeconds,
                        current.loopCount,
                    )
                    _uiState.value = current.copy(
                        remainingSeconds = ticked.remainingSeconds,
                        loopCount = ticked.loopCount,
                    )
                }
            }
        }
    }

    fun updateDescription(text: String) {
        _uiState.value = _uiState.value.copy(description = text)
    }

    fun saveDescription(text: String) {
        viewModelScope.launch {
            val exercise = _uiState.value.exercise ?: return@launch
            exerciseRepository.save(exercise.copy(notes = text))
        }
    }

    /**
     * Called when the user taps "Complete Exercise". Persists on [clearScope] only the sets
     * the lifter actually ticked done — an unticked hold is a proposal, not performed work.
     * Nothing ticked closes as completedEmpty (skipped styling) with an empty set list as
     * the ghost-data guard. Flips `completionSaved` once on disk — see [ExerciseSessionViewModel].
     */
    fun completeExercise() {
        val sets = _uiState.value.sets
        val session = currentSession
        // Only an explicit tick marks a hold performed. Completed work is exactly the
        // ticked sets — unticked rows are not recorded.
        val doneSets = sets.filter { it.done }
        val anyDone = doneSets.isNotEmpty()
        crumb("complete $exerciseId (done=${doneSets.size}/${sets.size})")
        markCompletionAndSave {
            if (session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.copy(
                            completed = true,
                            completedEmpty = !anyDone,
                            completedAt = java.time.LocalDateTime.now().toString(),
                            sets = doneSets.map { ExerciseSet.Stretch(timeSeconds = it.timeSeconds, done = true) },
                        )
                    } else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
            }
        }
    }

    // "Switch exercise" — see StrengthExerciseViewModel.switchedExerciseId for the pattern.
    private val _switchedExerciseId = MutableStateFlow<String?>(null)
    val switchedExerciseId: StateFlow<String?> = _switchedExerciseId

    fun switchExercise(newExerciseId: String) {
        crumb("switch $exerciseId → $newExerciseId")
        viewModelScope.launch {
            val newExercise = exerciseRepository.getById(newExerciseId) ?: return@launch
            val fresh = workoutRepository.getSession(sessionId, java.time.LocalDate.now())
                ?: currentSession ?: return@launch
            // A stretch set only becomes `done` via an explicit toggle — same signal the
            // Complete button uses for its "Salta" label.
            val untouched = _uiState.value.sets.none { it.done }
            val updated = fresh.withExerciseSwitched(
                exerciseId, newExercise, allowDiscardRecordedSets = untouched,
            )
            if (updated === fresh) return@launch
            workoutRepository.save(updated)
            currentSession = updated
            markSwitched()
            _switchedExerciseId.value = newExerciseId
        }
    }

    private fun crumb(message: String) = Breadcrumbs.add("stretch", message)

    override fun onCleared() {
        timerJob?.cancel()
        super.onCleared()
    }

    override fun saveProgressOnExit() {
        crumb("exit without completing $exerciseId")
        val sets = _uiState.value.sets
        val session = currentSession
        clearScope.launch {
            if (session != null) {
                // Completion flags are preserved: a skipped slot the lifter re-entered to look
                // around and backed out of stays skipped (only an explicit Complete re-closes
                // it) — same semantics as StrengthExerciseViewModel.
                val exitSets = sets.map { ExerciseSet.Stretch(timeSeconds = it.timeSeconds, done = it.done) }
                workoutRepository.save(session.withExerciseSetsOnExit(exerciseId, exitSets))
            }
            clearScope.cancel()
        }
    }
}

/**
 * Process-death drafts for stretch sets (Sprint 9): one `"done"` flag row per set —
 * durations reload fresh from the session every init, only the toggles need saving.
 * Size mismatch ignores the draft rather than misaligning sets. Pure, unit-tested.
 */
internal const val DRAFT_STRETCH_SETS = "draft_stretch_sets"

internal fun encodeStretchDraft(sets: List<StretchSetUi>): ArrayList<String> =
    ArrayList(sets.map { if (it.done) "1" else "0" })

internal fun decodeStretchDraft(raw: List<String>?): List<Boolean>? {
    if (raw == null) return null
    return raw.map {
        when (it) {
            "1" -> true
            "0" -> false
            else -> return null
        }
    }
}

internal fun applyStretchDraft(base: List<StretchSetUi>, draft: List<Boolean>?): List<StretchSetUi> {
    if (draft == null || draft.size != base.size) return base
    return base.mapIndexed { i, s -> s.copy(done = draft[i]) }
}
