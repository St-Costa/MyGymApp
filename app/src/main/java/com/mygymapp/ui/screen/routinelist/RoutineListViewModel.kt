package com.mygymapp.ui.screen.routinelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoutineListUiState())
    val uiState: StateFlow<RoutineListUiState> = _uiState

    init {
        loadRoutines()
    }

    fun loadRoutines() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val routines = routineRepository.getAll()
            _uiState.value = RoutineListUiState(routines = routines, isLoading = false)
        }
    }

    fun toggleEnabled(routine: Routine) {
        viewModelScope.launch {
            routineRepository.save(routine.copy(enabled = !routine.enabled))
            loadRoutines()
        }
    }

    fun deleteRoutine(id: String) {
        viewModelScope.launch {
            routineRepository.delete(id)
            loadRoutines()
        }
    }
}
