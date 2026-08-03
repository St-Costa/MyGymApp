package com.mygymapp.ui.screen.stretchexercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.WorkoutSession
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

            _uiState.value = StretchExerciseUiState(
                exercise = exercise,
                sets = sets,
                description = exercise.notes,
                isLoading = false,
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
        exerciseCompleted = true
        val sets = _uiState.value.sets
        val session = currentSession
        // A stretch set only becomes `done` via an explicit toggle, so "no set marked done"
        // means the lifter never touched this exercise — treat it like an unopened exercise
        // rather than recording an empty/untouched stretch as performed work.
        val anyDone = sets.any { it.done }
        viewModelScope.launch {
            if (session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        if (anyDone) {
                            ex.copy(
                                completed = true,
                                completedEmpty = false,
                                sets = sets.map { ExerciseSet.Stretch(timeSeconds = it.timeSeconds, done = it.done) },
                            )
                        } else {
                            ex.copy(completed = true, completedEmpty = true, sets = emptyList())
                        }
                    } else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
            }
            _completionSaved.value = true
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        if (exerciseCompleted) {
            clearScope.cancel()
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
