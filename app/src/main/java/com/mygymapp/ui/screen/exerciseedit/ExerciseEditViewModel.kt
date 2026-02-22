package com.mygymapp.ui.screen.exerciseedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.DataChangedSignal
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.repository.ExerciseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExerciseEditUiState(
    val id: String = "",
    val name: String = "",
    val type: ExerciseType = ExerciseType.FORZA,
    val bodypart: String = "",
    val link: String = "",
    val notes: String = "",
    val defaultRepRangeMin: Int = 8,
    val defaultRepRangeMax: Int = 12,
    val existingBodyparts: List<String> = emptyList(),
    val isNew: Boolean = true,
    val deleted: Boolean = false,
)

@HiltViewModel
class ExerciseEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val dataChangedSignal: DataChangedSignal,
) : ViewModel() {

    private val exerciseId: String? = savedStateHandle.get<String>("id")?.takeIf { it.isNotBlank() }

    private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(ExerciseEditUiState())
    val uiState: StateFlow<ExerciseEditUiState> = _uiState

    init {
        viewModelScope.launch {
            val bodyparts = exerciseRepository.getBodyparts()
            if (exerciseId != null) {
                val exercise = exerciseRepository.getById(exerciseId)
                if (exercise != null) {
                    _uiState.value = ExerciseEditUiState(
                        id = exercise.id,
                        name = exercise.name,
                        type = exercise.type,
                        bodypart = exercise.bodypart,
                        link = exercise.link,
                        notes = exercise.notes,
                        defaultRepRangeMin = exercise.defaultRepRangeMin,
                        defaultRepRangeMax = exercise.defaultRepRangeMax,
                        existingBodyparts = bodyparts,
                        isNew = false,
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(existingBodyparts = bodyparts)
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.value = _uiState.value.copy(name = value)
    }

    fun onTypeChange(type: ExerciseType) {
        _uiState.value = _uiState.value.copy(type = type)
    }

    fun onBodypartChange(value: String) {
        _uiState.value = _uiState.value.copy(bodypart = value)
    }

    fun onLinkChange(value: String) {
        _uiState.value = _uiState.value.copy(link = value)
    }

    fun onNotesChange(value: String) {
        _uiState.value = _uiState.value.copy(notes = value)
    }

    fun onRepMinChange(value: Int) {
        _uiState.value = _uiState.value.copy(defaultRepRangeMin = value.coerceAtLeast(1))
    }

    fun onRepMaxChange(value: Int) {
        _uiState.value = _uiState.value.copy(defaultRepRangeMax = value.coerceAtLeast(1))
    }

    fun deleteExercise() {
        val id = exerciseId ?: return
        viewModelScope.launch {
            exerciseRepository.delete(id)
            _uiState.value = _uiState.value.copy(deleted = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        val state = _uiState.value
        if (state.name.isBlank() || state.deleted) return
        clearScope.launch {
            val exercise = Exercise(
                id = state.id,
                name = state.name.trim(),
                type = state.type,
                bodypart = state.bodypart.trim(),
                link = state.link.trim(),
                notes = state.notes.trim(),
                defaultRepRangeMin = state.defaultRepRangeMin,
                defaultRepRangeMax = state.defaultRepRangeMax,
            )
            exerciseRepository.save(exercise)
            dataChangedSignal.notifyExercisesChanged()
            clearScope.cancel()
        }
    }
}
