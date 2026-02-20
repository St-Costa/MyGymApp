package com.mygymapp.ui.screen.weekview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Routine
import com.mygymapp.data.repository.RoutineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class WeekDayUi(
    val dayName: String,
    val dayKey: String,
    val routines: List<Routine> = emptyList(),
    val isToday: Boolean = false,
)

data class WeekViewUiState(
    val days: List<WeekDayUi> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class WeekViewViewModel @Inject constructor(
    private val routineRepository: RoutineRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeekViewUiState())
    val uiState: StateFlow<WeekViewUiState> = _uiState

    init {
        loadWeek()
    }

    fun loadWeek() {
        viewModelScope.launch {
            val allRoutines = routineRepository.getAll()
            val today = LocalDate.now().dayOfWeek.name.lowercase()

            val dayNames = listOf(
                "Monday" to "monday",
                "Tuesday" to "tuesday",
                "Wednesday" to "wednesday",
                "Thursday" to "thursday",
                "Friday" to "friday",
                "Saturday" to "saturday",
                "Sunday" to "sunday",
            )

            val days = dayNames.map { (display, key) ->
                val routines = allRoutines.filter {
                    it.day.equals(key, ignoreCase = true) && it.enabled
                }
                WeekDayUi(
                    dayName = display,
                    dayKey = key,
                    routines = routines,
                    isToday = key == today,
                )
            }

            _uiState.value = WeekViewUiState(days = days, isLoading = false)
        }
    }
}
