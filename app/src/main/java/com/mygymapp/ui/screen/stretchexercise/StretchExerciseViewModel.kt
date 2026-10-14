package com.mygymapp.ui.screen.stretchexercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.WorkoutSession
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
            val session = workoutRepository.getSession(sessionId, java.time.LocalDate.now())
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

            // Switch is offered only for a plain NORMAL slot — not warmup/fixed-daily —
            // matching WorkoutExercise.isSwitchEligible().
            val isDaily = workoutExercise?.isDaily ?: false
            val switchEligible = workoutExercise?.isSwitchEligible() == true &&
                !isDaily && workoutExercise?.excludeFromTonnage != true

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

    fun toggleStopwatch() {
        val wasRunning = _uiState.value.isStopwatchRunning
        crumb(if (wasRunning) "stopwatch stop" else "stopwatch start")
        if (wasRunning) {
            timerJob?.cancel()
            timerJob = null
            _uiState.value = _uiState.value.copy(
                isStopwatchRunning = false,
                remainingSeconds = _uiState.value.countdownDuration,
                loopCount = 1,
            )
        } else {
            timerJob?.cancel()
            val countdown = StretchCountdown.start(_uiState.value.countdownDuration)
            _uiState.value = _uiState.value.copy(
                isStopwatchRunning = true,
                countdownDuration = countdown.remainingSeconds,
                remainingSeconds = countdown.remainingSeconds,
                loopCount = countdown.loopCount,
            )
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    val current = _uiState.value
                    val (next, _) = StretchCountdown(
                        remainingSeconds = current.remainingSeconds,
                        loopCount = current.loopCount,
                    ).tick(current.countdownDuration)
                    _uiState.value = current.copy(
                        remainingSeconds = next.remainingSeconds,
                        loopCount = next.loopCount,
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
     * Called when the user taps "Complete Exercise". Persists on [clearScope], flips
     * `completionSaved` once on disk — see [ExerciseSessionViewModel]. A set toggled done →
     * real work; none toggled → completedEmpty (skipped styling; tonnage/history math skips it).
     */
    fun completeExercise() {
        val sets = _uiState.value.sets
        val session = currentSession
        // A stretch set only becomes `done` via an explicit toggle. Marking at least one set
        // done is the signal the exercise was performed.
        val anyDone = sets.any { it.done }
        crumb("complete $exerciseId (anyDone=$anyDone)")
        markCompletionAndSave {
            if (session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.copy(
                            completed = true,
                            completedEmpty = !anyDone,
                            sets = sets.map { ExerciseSet.Stretch(timeSeconds = it.timeSeconds, done = it.done) },
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
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.copy(
                            completed = false,
                            sets = sets.map { ExerciseSet.Stretch(timeSeconds = it.timeSeconds, done = it.done) },
                        )
                    } else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
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
