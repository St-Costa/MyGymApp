package com.mygymapp.ui.screen.activeroutine

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

data class ActiveRoutineUiState(
    val routineName: String = "",
    val notes: String = "",
    val exercises: List<ActiveExerciseUi> = emptyList(),
    val sessionId: String = "",
    val isLoading: Boolean = true,
    val allCompleted: Boolean = false,
    val totalTonnage: Double = 0.0,
    val previousTonnage: Double? = null,
)

data class ActiveExerciseUi(
    val exerciseId: String,
    val exerciseName: String,
    val type: ExerciseType,
    val bodypart: String,
    val completed: Boolean = false,
    val setCount: Int = 0,
)

@HiltViewModel
class ActiveRoutineViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineRepository: RoutineRepository,
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val routineId: String = savedStateHandle["routineId"] ?: ""

    private val _uiState = MutableStateFlow(ActiveRoutineUiState())
    val uiState: StateFlow<ActiveRoutineUiState> = _uiState

    private var currentSession: WorkoutSession? = null

    init {
        viewModelScope.launch {
            val routine = routineRepository.getById(routineId) ?: return@launch

            val exercises = routine.exercises.mapNotNull { re ->
                val exercise = exerciseRepository.getById(re.exerciseId) ?: return@mapNotNull null
                ActiveExerciseUi(
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    type = exercise.type,
                    bodypart = exercise.bodypart,
                    setCount = re.sets,
                )
            }

            // Create workout session
            val workoutExercises = routine.exercises.mapNotNull { re ->
                val exercise = exerciseRepository.getById(re.exerciseId) ?: return@mapNotNull null
                val sets = (1..re.sets).map { _ ->
                    when (exercise.type) {
                        ExerciseType.FORZA -> ExerciseSet.Strength()
                        ExerciseType.STRETCH -> ExerciseSet.Stretch(timeSeconds = re.timePerSetSeconds)
                    }
                }
                WorkoutExercise(
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    bodypart = exercise.bodypart,
                    type = exercise.type,
                    sets = sets,
                )
            }

            val session = WorkoutSession(
                id = "",
                routineId = routineId,
                routineName = routine.name,
                date = LocalDate.now().toString(),
                exercises = workoutExercises,
                notes = routine.notes,
            )
            val saved = workoutRepository.save(session)
            currentSession = saved

            // Get previous session for comparison
            val previousSession = workoutRepository.getLastSessionForRoutine(routineId)

            _uiState.value = ActiveRoutineUiState(
                routineName = routine.name,
                notes = routine.notes,
                exercises = exercises,
                sessionId = saved.id,
                isLoading = false,
                previousTonnage = previousSession?.totalTonnage,
            )
        }
    }

    fun markExerciseCompleted(exerciseId: String) {
        val exercises = _uiState.value.exercises.map { ex ->
            if (ex.exerciseId == exerciseId) ex.copy(completed = true) else ex
        }
        val allCompleted = exercises.all { it.completed }
        _uiState.value = _uiState.value.copy(
            exercises = exercises,
            allCompleted = allCompleted,
        )

        if (allCompleted) {
            finalizeSession()
        }
    }

    fun updateNotes(notes: String) {
        _uiState.value = _uiState.value.copy(notes = notes)
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            currentSession = session.copy(notes = notes)
            workoutRepository.save(currentSession!!)
            // Also update the routine notes
            val routine = routineRepository.getById(routineId) ?: return@launch
            routineRepository.save(routine.copy(notes = notes))
        }
    }

    private fun finalizeSession() {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            // Reload session from file to get updated exercise data
            val updated = session.copy(
                completedAt = LocalDateTime.now().toString(),
            )
            // Calculate tonnage
            var totalTonnage = 0.0
            val tonnageByBodypart = mutableMapOf<String, Double>()
            for (ex in updated.exercises) {
                var exTonnage = 0.0
                for (set in ex.sets) {
                    if (set is ExerciseSet.Strength) {
                        exTonnage += set.reps * set.weight
                    }
                }
                totalTonnage += exTonnage
                tonnageByBodypart[ex.bodypart] =
                    (tonnageByBodypart[ex.bodypart] ?: 0.0) + exTonnage
            }
            val finalSession = updated.copy(
                totalTonnage = totalTonnage,
                tonnageByBodypart = tonnageByBodypart,
            )
            currentSession = finalSession
            workoutRepository.save(finalSession)

            _uiState.value = _uiState.value.copy(totalTonnage = totalTonnage)
        }
    }
}
