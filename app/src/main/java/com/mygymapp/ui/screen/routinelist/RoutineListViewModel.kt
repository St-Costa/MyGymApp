package com.mygymapp.ui.screen.routinelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.DataChangedSignal
import com.mygymapp.data.model.FIXED_DAILY_ROUTINE_ID
import com.mygymapp.data.model.Routine
import com.mygymapp.data.repository.RoutineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoutineListUiState(
    val routines: List<Routine> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class RoutineListViewModel @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val dataChangedSignal: DataChangedSignal,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoutineListUiState())
    val uiState: StateFlow<RoutineListUiState> = _uiState

    init {
        loadRoutines()
        viewModelScope.launch {
            dataChangedSignal.routinesChanged.collect { loadRoutines() }
        }
    }

    fun loadRoutines() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val routines = routineRepository.getAll()
            _uiState.value = RoutineListUiState(routines = routines, isLoading = false)
        }
    }

    fun toggleEnabled(routine: Routine) {
        if (routine.id == FIXED_DAILY_ROUTINE_ID) return
        viewModelScope.launch {
            val updated = routine.copy(enabled = !routine.enabled)
            routineRepository.save(updated)
            _uiState.value = _uiState.value.copy(
                routines = _uiState.value.routines.map { if (it.id == routine.id) updated else it }
            )
        }
    }

    fun deleteRoutine(id: String) {
        if (id == FIXED_DAILY_ROUTINE_ID) return
        viewModelScope.launch {
            routineRepository.delete(id)
            _uiState.value = _uiState.value.copy(
                routines = _uiState.value.routines.filter { it.id != id }
            )
        }
    }
}
