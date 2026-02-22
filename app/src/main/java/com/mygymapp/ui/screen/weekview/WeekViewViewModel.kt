package com.mygymapp.ui.screen.weekview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.DataChangedSignal
import com.mygymapp.data.model.Routine
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class WeekDayUi(
    val dayName: String,
    val dayKey: String,
    val date: LocalDate,
    val routines: List<Routine> = emptyList(),
    val completedRoutineIds: Set<String> = emptySet(),
    val isToday: Boolean = false,
    val isPast: Boolean = false,
)

data class WeekViewUiState(
    val days: List<WeekDayUi> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class WeekViewViewModel @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val workoutRepository: WorkoutRepository,
    private val dataChangedSignal: DataChangedSignal,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeekViewUiState())
    val uiState: StateFlow<WeekViewUiState> = _uiState

    init {
        loadWeek()
        viewModelScope.launch {
            dataChangedSignal.routinesChanged.collect { loadWeek() }
        }
    }

    fun loadWeek() {
        viewModelScope.launch {
            val allRoutines = routineRepository.getAll()
            val today = LocalDate.now()
            val todayKey = today.dayOfWeek.name.lowercase()

            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val sunday = monday.plusDays(6)

            val sessions = workoutRepository.getSessionsInRange(monday, sunday)
            val completedRoutineIds = sessions
                .filter { it.completedAt.isNotBlank() }
                .map { it.routineId }
                .toSet()

            val dayEntries = listOf(
                "Monday" to DayOfWeek.MONDAY,
                "Tuesday" to DayOfWeek.TUESDAY,
                "Wednesday" to DayOfWeek.WEDNESDAY,
                "Thursday" to DayOfWeek.THURSDAY,
                "Friday" to DayOfWeek.FRIDAY,
                "Saturday" to DayOfWeek.SATURDAY,
                "Sunday" to DayOfWeek.SUNDAY,
            )

            val days = dayEntries.map { (display, dow) ->
                val date = monday.plusDays(dow.ordinal.toLong() - DayOfWeek.MONDAY.ordinal.toLong())
                val key = dow.name.lowercase()
                val routines = allRoutines.filter {
                    it.day.equals(key, ignoreCase = true) && it.enabled
                }
                WeekDayUi(
                    dayName = display,
                    dayKey = key,
                    date = date,
                    routines = routines,
                    completedRoutineIds = completedRoutineIds,
                    isToday = key == todayKey,
                    isPast = date.isBefore(today),
                )
            }

            _uiState.value = WeekViewUiState(days = days, isLoading = false)
        }
    }
}
