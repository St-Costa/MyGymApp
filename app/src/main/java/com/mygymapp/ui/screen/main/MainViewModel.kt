package com.mygymapp.ui.screen.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.ui.components.DayStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class MainUiState(
    val gitgraphDays: List<DayStatus> = List(28) { DayStatus.NONE },
    val todayIndex: Int = 27,
    val isLoading: Boolean = true,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    init {
        loadGitgraph()
    }

    fun loadGitgraph() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val startDate = today.minusDays(27)

            val sessions = workoutRepository.getSessionsInRange(startDate, today)

            // Group sessions by routineId, sorted by date
            val sessionsByRoutine = sessions.groupBy { it.routineId }

            // For each day, determine the status
            val days = (0 until 28).map { dayOffset ->
                val date = startDate.plusDays(dayOffset.toLong())
                val dateStr = date.toString()
                val daySessions = sessions.filter { it.date == dateStr }

                if (daySessions.isEmpty()) {
                    DayStatus.NONE
                } else {
                    // Check if any session that day had improved tonnage
                    var improved = false
                    var regressed = false
                    for (session in daySessions) {
                        val routineSessions = sessionsByRoutine[session.routineId]
                            ?.filter { it.date < dateStr }
                            ?.sortedByDescending { it.date }
                        val previous = routineSessions?.firstOrNull()
                        if (previous != null) {
                            if (session.totalTonnage >= previous.totalTonnage) {
                                improved = true
                            } else {
                                regressed = true
                            }
                        } else {
                            // First time doing this routine, count as improved
                            improved = true
                        }
                    }
                    if (improved) DayStatus.IMPROVED else if (regressed) DayStatus.REGRESSED else DayStatus.NONE
                }
            }

            val todayIndex = 27 // Today is always the last day

            _uiState.value = MainUiState(
                gitgraphDays = days,
                todayIndex = todayIndex,
                isLoading = false,
            )
        }
    }
}
