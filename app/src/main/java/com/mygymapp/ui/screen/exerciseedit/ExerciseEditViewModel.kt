package com.mygymapp.ui.screen.exerciseedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.repository.ExerciseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExerciseEditUiState(
    val id: String = "",
    val name: String = "",
    val type: ExerciseType = ExerciseType.FORZA,
    val bodypart: String = "",
    val link: String = "",
    val notes: String = "",
    val existingBodyparts: List<String> = emptyList(),
    val isNew: Boolean = true,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val deleted: Boolean = false,
)

@HiltViewModel
class ExerciseEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
) : ViewModel() {

    private val exerciseId: String? = savedStateHandle.get<String>("id")?.takeIf { it.isNotBlank() }

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

    fun deleteExercise() {
        val id = exerciseId ?: return
        viewModelScope.launch {
            exerciseRepository.delete(id)
            _uiState.value = _uiState.value.copy(deleted = true)
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.name.isBlank()) return

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true)
            val exercise = Exercise(
                id = state.id,
                name = state.name.trim(),
                type = state.type,
                bodypart = state.bodypart.trim(),
                link = state.link.trim(),
                notes = state.notes.trim(),
            )
            exerciseRepository.save(exercise)
            _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
        }
    }
}
