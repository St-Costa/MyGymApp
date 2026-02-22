package com.mygymapp.ui.screen.routineedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.Routine
import com.mygymapp.data.model.RoutineExercise
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoutineExerciseUi(
    val exerciseId: String,
    val exerciseName: String,
    val exerciseType: ExerciseType,
    val sets: Int = 3,
    val repRangeMin: Int = 8,
    val repRangeMax: Int = 12,
    val timePerSetSeconds: Int = 60,
)

data class RoutineEditUiState(
    val id: String = "",
    val name: String = "",
    val day: String = "",
    val notes: String = "",
    val exercises: List<RoutineExerciseUi> = emptyList(),
    val isNew: Boolean = true,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val deleted: Boolean = false,
)

val DAYS_OF_WEEK = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

@HiltViewModel
class RoutineEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineRepository: RoutineRepository,
    private val exerciseRepository: ExerciseRepository,
) : ViewModel() {

    private val routineId: String? = savedStateHandle.get<String>("id")?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow(RoutineEditUiState())
    val uiState: StateFlow<RoutineEditUiState> = _uiState

    init {
        viewModelScope.launch {
            if (routineId != null) {
                val routine = routineRepository.getById(routineId)
                if (routine != null) {
                    val exerciseUis = routine.exercises.mapNotNull { re ->
                        val exercise = exerciseRepository.getById(re.exerciseId) ?: return@mapNotNull null
                        RoutineExerciseUi(
                            exerciseId = re.exerciseId,
                            exerciseName = exercise.name,
                            exerciseType = exercise.type,
                            sets = re.sets,
                            repRangeMin = re.repRangeMin,
                            repRangeMax = re.repRangeMax,
                            timePerSetSeconds = re.timePerSetSeconds,
                        )
                    }
                    _uiState.value = RoutineEditUiState(
                        id = routine.id,
                        name = routine.name,
                        day = routine.day,
                        notes = routine.notes,
                        exercises = exerciseUis,
                        isNew = false,
                    )
                }
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.value = _uiState.value.copy(name = value)
    }

    fun onDayChange(day: String) {
        _uiState.value = _uiState.value.copy(day = day)
    }

    fun onNotesChange(value: String) {
        _uiState.value = _uiState.value.copy(notes = value)
    }

    fun addExercise(exerciseId: String) {
        viewModelScope.launch {
            val exercise = exerciseRepository.getById(exerciseId) ?: return@launch
            val already = _uiState.value.exercises.any { it.exerciseId == exerciseId }
            if (already) return@launch
            val newItem = RoutineExerciseUi(
                exerciseId = exercise.id,
                exerciseName = exercise.name,
                exerciseType = exercise.type,
                repRangeMin = exercise.defaultRepRangeMin,
                repRangeMax = exercise.defaultRepRangeMax,
            )
            _uiState.value = _uiState.value.copy(
                exercises = _uiState.value.exercises + newItem,
            )
        }
    }

    fun removeExercise(index: Int) {
        val list = _uiState.value.exercises.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _uiState.value = _uiState.value.copy(exercises = list)
        }
    }

    fun moveExercise(fromIndex: Int, toIndex: Int) {
        val list = _uiState.value.exercises.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _uiState.value = _uiState.value.copy(exercises = list)
        }
    }

    fun updateExerciseSets(index: Int, sets: Int) {
        updateExercise(index) { it.copy(sets = sets.coerceAtLeast(1)) }
    }

    fun updateExerciseRepMin(index: Int, value: Int) {
        updateExercise(index) { it.copy(repRangeMin = value.coerceAtLeast(1)) }
    }

    fun updateExerciseRepMax(index: Int, value: Int) {
        updateExercise(index) { it.copy(repRangeMax = value.coerceAtLeast(1)) }
    }

    fun updateExerciseTime(index: Int, seconds: Int) {
        updateExercise(index) { it.copy(timePerSetSeconds = seconds.coerceAtLeast(1)) }
    }

    private fun updateExercise(index: Int, transform: (RoutineExerciseUi) -> RoutineExerciseUi) {
        val list = _uiState.value.exercises.toMutableList()
        if (index in list.indices) {
            list[index] = transform(list[index])
            _uiState.value = _uiState.value.copy(exercises = list)
        }
    }

    fun deleteRoutine() {
        val id = routineId ?: return
        viewModelScope.launch {
            routineRepository.delete(id)
            _uiState.value = _uiState.value.copy(deleted = true)
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.name.isBlank()) return

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true)
            val routine = Routine(
                id = state.id,
                name = state.name.trim(),
                day = state.day,
                notes = state.notes.trim(),
                exercises = state.exercises.map { ex ->
                    RoutineExercise(
                        exerciseId = ex.exerciseId,
                        sets = ex.sets,
                        repRangeMin = ex.repRangeMin,
                        repRangeMax = ex.repRangeMax,
                        timePerSetSeconds = ex.timePerSetSeconds,
                    )
                },
            )
            routineRepository.save(routine)
            _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
        }
    }
}
