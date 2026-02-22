package com.mygymapp.ui.screen.strengthexercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StrengthSetUi(
    val reps: Int = 0,
    val weight: Double = 0.0,
    val previousReps: Int = 0,
    val previousWeight: Double = 0.0,
    val repsModified: Boolean = false,
    val weightModified: Boolean = false,
)

data class StrengthExerciseUiState(
    val exercise: Exercise? = null,
    val sets: List<StrengthSetUi> = emptyList(),
    val repRangeMin: Int = 0,
    val repRangeMax: Int = 0,
    val description: String = "",
    val isLoading: Boolean = true,
    val allSetsFilled: Boolean = false,
)

@HiltViewModel
class StrengthExerciseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val routineRepository: RoutineRepository,
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""
    private val exerciseId: String = savedStateHandle["exerciseId"] ?: ""

    private val _uiState = MutableStateFlow(StrengthExerciseUiState())
    val uiState: StateFlow<StrengthExerciseUiState> = _uiState

    private var currentSession: WorkoutSession? = null
    private var exerciseCompleted = false
    private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        viewModelScope.launch {
            val exercise = exerciseRepository.getById(exerciseId) ?: return@launch

            // Find previous workout data for this exercise (for showing grey "previous" values)
            val previousSessions = workoutRepository.getSessionsForExercise(exerciseId, 1)
            val previousSets = previousSessions.firstOrNull()?.exercises
                ?.find { it.exerciseId == exerciseId }?.sets
                ?.filterIsInstance<ExerciseSet.Strength>() ?: emptyList()

            // Find current session to get set count, rep range, and any in-progress values
            val sessions = workoutRepository.getSessionsInRange(
                java.time.LocalDate.now(), java.time.LocalDate.now()
            )
            val session = sessions.find { it.id == sessionId }
            currentSession = session

            val workoutExercise = session?.exercises?.find { it.exerciseId == exerciseId }
            val setCount = workoutExercise?.sets?.size ?: 3

            // Get rep range from routine
            var repMin = 0
            var repMax = 0
            val routineId = session?.routineId ?: ""
            if (routineId.isNotBlank()) {
                val routine = routineRepository.getById(routineId)
                val routineExercise = routine?.exercises?.find { it.exerciseId == exerciseId }
                repMin = routineExercise?.repRangeMin ?: 0
                repMax = routineExercise?.repRangeMax ?: 0
            }

            // Use current session's in-progress values if available, else fall back to previous
            val currentSets = workoutExercise?.sets?.filterIsInstance<ExerciseSet.Strength>() ?: emptyList()
            val hasProgress = currentSets.any { it.reps > 0 || it.weight > 0.0 }

            val sets = (0 until setCount).map { i ->
                val prev = previousSets.getOrNull(i)
                val curr = if (hasProgress) currentSets.getOrNull(i) else null
                StrengthSetUi(
                    reps = curr?.reps ?: prev?.reps ?: 0,
                    weight = curr?.weight ?: prev?.weight ?: 0.0,
                    previousReps = prev?.reps ?: 0,
                    previousWeight = prev?.weight ?: 0.0,
                    repsModified = curr != null && curr.reps != (prev?.reps ?: 0),
                    weightModified = curr != null && curr.weight != (prev?.weight ?: 0.0),
                )
            }

            _uiState.value = StrengthExerciseUiState(
                exercise = exercise,
                sets = sets,
                repRangeMin = repMin,
                repRangeMax = repMax,
                description = exercise.notes,
                isLoading = false,
            )
        }
    }

    fun updateReps(setIndex: Int, reps: Int) {
        updateSet(setIndex) { it.copy(reps = reps.coerceAtLeast(0), repsModified = true) }
    }

    fun updateWeight(setIndex: Int, weight: Double) {
        updateSet(setIndex) { it.copy(weight = weight.coerceAtLeast(0.0), weightModified = true) }
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

    /** Called when the user taps "Complete Exercise". Marks the exercise as completed. */
    fun completeExercise() {
        exerciseCompleted = true
    }

    override fun onCleared() {
        // Capture state on the main thread before launching the coroutine
        val completed = exerciseCompleted
        val sets = _uiState.value.sets
        val session = currentSession
        clearScope.launch {
            if (session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        val updatedSets = sets.map { setUi ->
                            ExerciseSet.Strength(reps = setUi.reps, weight = setUi.weight)
                        }
                        ex.copy(completed = completed, sets = updatedSets)
                    } else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
            }
            clearScope.cancel()
        }
    }

    private fun updateSet(index: Int, transform: (StrengthSetUi) -> StrengthSetUi) {
        val sets = _uiState.value.sets.toMutableList()
        if (index in sets.indices) {
            sets[index] = transform(sets[index])
            val allFilled = sets.all { it.reps > 0 && it.weight > 0 }
            _uiState.value = _uiState.value.copy(sets = sets, allSetsFilled = allFilled)
        }
    }
}
