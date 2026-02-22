package com.mygymapp.ui.screen.exerciselist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.DataChangedSignal
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.repository.ExerciseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExerciseListUiState(
    val exercisesByBodypart: Map<String, List<Exercise>> = emptyMap(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class ExerciseListViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
    private val dataChangedSignal: DataChangedSignal,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExerciseListUiState())
    val uiState: StateFlow<ExerciseListUiState> = _uiState

    init {
        loadExercises()
        viewModelScope.launch {
            dataChangedSignal.exercisesChanged.collect { loadExercises() }
        }
    }

    fun loadExercises() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val exercises = exerciseRepository.getAll()
            val grouped = exercises.groupBy { it.bodypart.ifBlank { "Other" } }
            _uiState.value = ExerciseListUiState(
                exercisesByBodypart = grouped,
                isLoading = false,
            )
        }
    }

    fun deleteExercise(id: String) {
        viewModelScope.launch {
            exerciseRepository.delete(id)
            val updated = _uiState.value.exercisesByBodypart
                .mapValues { (_, list) -> list.filter { it.id != id } }
                .filterValues { it.isNotEmpty() }
            _uiState.value = _uiState.value.copy(exercisesByBodypart = updated)
        }
    }
}
