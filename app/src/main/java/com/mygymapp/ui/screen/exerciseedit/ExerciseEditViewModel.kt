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
    // FORZA only: no external weight by design (plank, push-ups, mobility work). See
    // Exercise.isBodyweight for why this matters to tonnage/PR/e1RM analysis.
    val isBodyweight: Boolean = false,
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
                        isBodyweight = exercise.isBodyweight,
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

    fun onBodyweightChange(value: Boolean) {
        _uiState.value = _uiState.value.copy(isBodyweight = value)
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
        val newMin = value.coerceAtLeast(1)
        val state = _uiState.value
        // Keep max >= min: if user raises min above max, pull max up with it.
        val newMax = if (newMin > state.defaultRepRangeMax) newMin else state.defaultRepRangeMax
        _uiState.value = state.copy(defaultRepRangeMin = newMin, defaultRepRangeMax = newMax)
    }

    fun onRepMaxChange(value: Int) {
        val newMax = value.coerceAtLeast(1)
        val state = _uiState.value
        // Symmetric: if user drops max below min, pull min down with it.
        val newMin = if (newMax < state.defaultRepRangeMin) newMax else state.defaultRepRangeMin
        _uiState.value = state.copy(defaultRepRangeMin = newMin, defaultRepRangeMax = newMax)
    }

    fun deleteExercise() {
        val id = exerciseId ?: return
        viewModelScope.launch {
            exerciseRepository.delete(id)
            dataChangedSignal.notifyExercisesChanged()
            _uiState.value = _uiState.value.copy(deleted = true)
        }
    }

    // Called from the Screen's back button / BackHandler before popBackStack().
    // Saves synchronously so the list screen sees fresh cache data immediately.
    private var savedExplicitly = false
    suspend fun saveNow() {
        val state = _uiState.value
        if (state.name.isBlank() || state.deleted) return
        val exercise = Exercise(
            id = state.id,
            name = state.name.trim(),
            type = state.type,
            // CARDIO exercises don't use bodypart — they live in their own dedicated section
            // (ExerciseListViewModel), not grouped by muscle group. The field is hidden in the
            // editor for CARDIO; force it blank here too in case of stale state.
            bodypart = if (state.type == ExerciseType.CARDIO) "" else state.bodypart.trim(),
            link = state.link.trim(),
            notes = state.notes.trim(),
            defaultRepRangeMin = state.defaultRepRangeMin,
            defaultRepRangeMax = state.defaultRepRangeMax,
            isBodyweight = state.isBodyweight,
        )
        exerciseRepository.save(exercise)
        dataChangedSignal.notifyExercisesChanged()
        savedExplicitly = true
    }

    override fun onCleared() {
        super.onCleared()
        if (savedExplicitly) return
        val state = _uiState.value
        if (state.name.isBlank() || state.deleted) return
        clearScope.launch {
            try {
                val exercise = Exercise(
                    id = state.id,
                    name = state.name.trim(),
                    type = state.type,
                    bodypart = if (state.type == ExerciseType.CARDIO) "" else state.bodypart.trim(),
                    link = state.link.trim(),
                    notes = state.notes.trim(),
                    defaultRepRangeMin = state.defaultRepRangeMin,
                    defaultRepRangeMax = state.defaultRepRangeMax,
                    isBodyweight = state.isBodyweight,
                )
                exerciseRepository.save(exercise)
                dataChangedSignal.notifyExercisesChanged()
            } finally {
                clearScope.cancel()
            }
        }
    }
}
