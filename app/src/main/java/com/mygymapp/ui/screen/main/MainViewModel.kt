package com.mygymapp.ui.screen.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val lastWeekRoutineNames: List<String?> = List(7) { null },
    val isLoading: Boolean = true,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    init {
        viewModelScope.launch {
            // Migration must finish before any query so that the exercise index and
            // ID-based filenames are in place. Prune runs after so the index is clean.
            workoutRepository.migrateOldSessionFiles()
            workoutRepository.pruneOldSessions(LocalDate.now().minusMonths(3))
            loadGitgraphInternal()
        }
    }

    fun loadGitgraph() {
        viewModelScope.launch { loadGitgraphInternal() }
    }

    private suspend fun loadGitgraphInternal() {
        val today = LocalDate.now()
        // Align to weeks: each row is Mon–Sun
        // dayOfWeek: 1=Monday .. 7=Sunday
        val todayDow = today.dayOfWeek.value // 1=Mon, 7=Sun
        val currentWeekMonday = today.minusDays((todayDow - 1).toLong())
        val startDate = currentWeekMonday.minusWeeks(3) // 4 weeks total

        val sessions = workoutRepository.getSessionsInRange(startDate, today)

        // Group sessions by routineId, sorted by date
        val sessionsByRoutine = sessions.groupBy { it.routineId }

        // For each day in the 4-week grid, determine the status
        val days = (0 until 28).map { dayOffset ->
            val date = startDate.plusDays(dayOffset.toLong())
            // Future days (after today) stay NONE
            if (date.isAfter(today)) {
                return@map DayStatus.NONE
            }
            val dateStr = date.toString()
            val daySessions = sessions.filter { it.date == dateStr }

            if (daySessions.isEmpty()) {
                DayStatus.NONE
            } else {
                // Use only the last completed session of the day (by completedAt)
                val lastSession = daySessions.maxByOrNull { it.completedAt }!!
                val previousSessions = sessionsByRoutine[lastSession.routineId]
                    ?.filter { it.date < dateStr }
                    ?.sortedByDescending { it.completedAt }
                val previous = previousSessions?.firstOrNull()
                if (previous != null) {
                    if (lastSession.totalTonnage >= previous.totalTonnage) DayStatus.IMPROVED
                    else DayStatus.REGRESSED
                } else {
                    // First time doing this routine, count as improved
                    DayStatus.IMPROVED
                }
            }
        }

        // Today's index: row 3 (last week) + column based on day of week
        val todayIndex = 3 * 7 + (todayDow - 1)

        // Routine name for each day of the current week (last row, dayOffset 21-27)
        val lastWeekRoutineNames: List<String?> = (0 until 7).map { col ->
            val date = startDate.plusDays((21 + col).toLong())
            if (date.isAfter(today)) return@map null
            val dateStr = date.toString()
            sessions.filter { it.date == dateStr }
                .maxByOrNull { it.completedAt }
                ?.routineName
        }

        _uiState.value = MainUiState(
            gitgraphDays = days,
            todayIndex = todayIndex,
            lastWeekRoutineNames = lastWeekRoutineNames,
            isLoading = false,
        )
    }
}
