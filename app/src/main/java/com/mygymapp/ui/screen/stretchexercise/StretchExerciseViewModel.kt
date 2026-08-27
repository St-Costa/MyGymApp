package com.mygymapp.ui.screen.stretchexercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.withExerciseSwitched
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    val elapsedSeconds: Int = 0,
    // See StrengthExerciseUiState for the "Switch exercise" fields' semantics.
    val switchEligible: Boolean = false,
    val excludeIds: Set<String> = emptySet(),
)

@HiltViewModel
class StretchExerciseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""
    private val exerciseId: String = savedStateHandle["exerciseId"] ?: ""

    private val _uiState = MutableStateFlow(StretchExerciseUiState())
    val uiState: StateFlow<StretchExerciseUiState> = _uiState

    private var currentSession: WorkoutSession? = null
    private var timerJob: Job? = null
    private var exerciseCompleted = false
    private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // The completeExercise() save, tracked so onCleared() can join it before cancelling
    // clearScope — see StrengthExerciseViewModel for the full reasoning.
    private var completionJob: Job? = null

    init {
        viewModelScope.launch {
            val exercise = exerciseRepository.getById(exerciseId) ?: return@launch

            val sessions = workoutRepository.getSessionsInRange(
                java.time.LocalDate.now(), java.time.LocalDate.now()
            )
            val session = sessions.find { it.id == sessionId }
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

            // Switch is offered only for a plain NORMAL slot — not warmup/fixed-daily —
            // matching WorkoutExercise.isSwitchEligible().
            val isDaily = workoutExercise?.isDaily ?: false
            val switchEligible = workoutExercise?.isSwitchEligible() == true &&
                !isDaily && workoutExercise?.excludeFromTonnage != true

            _uiState.value = StretchExerciseUiState(
                exercise = exercise,
                sets = sets,
                description = exercise.notes,
                isLoading = false,
                switchEligible = switchEligible,
                excludeIds = session?.exercises?.map { it.exerciseId }?.toSet() ?: emptySet(),
            )
        }
    }

    fun toggleSetDone(setIndex: Int) {
        val sets = _uiState.value.sets.toMutableList()
        if (setIndex in sets.indices && !sets[setIndex].done) {
            sets[setIndex] = sets[setIndex].copy(done = true)
            _uiState.value = _uiState.value.copy(sets = sets)
        }
    }

    fun toggleStopwatch() {
        val wasRunning = _uiState.value.isStopwatchRunning
        if (wasRunning) {
            timerJob?.cancel()
            timerJob = null
            _uiState.value = _uiState.value.copy(isStopwatchRunning = false)
        } else {
            timerJob?.cancel()
            _uiState.value = _uiState.value.copy(isStopwatchRunning = true, elapsedSeconds = 0)
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    _uiState.value = _uiState.value.copy(
                        elapsedSeconds = _uiState.value.elapsedSeconds + 1
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

    private val _completionSaved = MutableStateFlow(false)
    val completionSaved: StateFlow<Boolean> = _completionSaved

    /**
     * Called when the user taps "Complete Exercise".
     * Saves set data to disk and then emits [completionSaved] = true so the screen
     * can navigate back only after the write is guaranteed to be on disk.
     */
    fun completeExercise() {
        // Either way this screen navigates back (exerciseCompleted stops onCleared() from
        // re-saving); only the completed flag differs.
        exerciseCompleted = true
        val sets = _uiState.value.sets
        val session = currentSession
        // A stretch set only becomes `done` via an explicit toggle. Marking at least one set
        // done is the signal the exercise was performed — all sets are then saved as-is.
        // Marking none means it was not performed: leave it open (completed = false).
        val anyDone = sets.any { it.done }
        // Save on clearScope, not viewModelScope, so a process death between the tap and the
        // write completing can't lose it — see StrengthExerciseViewModel.completeExercise().
        completionJob = clearScope.launch {
            if (session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.copy(
                            completed = anyDone,
                            completedEmpty = false,
                            sets = sets.map { ExerciseSet.Stretch(timeSeconds = it.timeSeconds, done = it.done) },
                        )
                    } else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
            }
            _completionSaved.value = true
        }
    }

    // "Switch exercise" — see StrengthExerciseViewModel.switchedExerciseId for the pattern.
    private val _switchedExerciseId = MutableStateFlow<String?>(null)
    val switchedExerciseId: StateFlow<String?> = _switchedExerciseId

    fun switchExercise(newExerciseId: String) {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val newExercise = exerciseRepository.getById(newExerciseId) ?: return@launch
            val updated = session.withExerciseSwitched(exerciseId, newExercise)
            if (updated === session) return@launch
            workoutRepository.save(updated)
            exerciseCompleted = true
            _switchedExerciseId.value = newExerciseId
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        if (exerciseCompleted) {
            // Wait for completeExercise()'s clearScope save to finish before tearing the
            // scope down — see StrengthExerciseViewModel.onCleared().
            clearScope.launch {
                try {
                    completionJob?.join()
                } finally {
                    clearScope.cancel()
                }
            }
            return
        }
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
