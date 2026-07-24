package com.mygymapp.ui.screen.options

import androidx.lifecycle.ViewModel
import com.mygymapp.data.PowerliftingScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import javax.inject.Inject

data class OptionsUiState(
    /** Monday of the selected powerlifting anchor week, or null if not set. */
    val anchorMonday: LocalDate? = null,
    val intervalWeeks: Int = 4,
)

@HiltViewModel
class OptionsViewModel @Inject constructor(
    private val scheduleRepository: PowerliftingScheduleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OptionsUiState(
            anchorMonday = scheduleRepository.anchorMonday(),
            intervalWeeks = scheduleRepository.intervalWeeks(),
        )
    )
    val uiState: StateFlow<OptionsUiState> = _uiState

    /** Select any day; the whole week (its Monday) becomes the anchor. */
    fun selectWeek(date: LocalDate) {
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        _uiState.value = _uiState.value.copy(anchorMonday = monday)
        persist()
    }

    fun setInterval(weeks: Int) {
        _uiState.value = _uiState.value.copy(intervalWeeks = weeks.coerceAtLeast(1))
        persist()
    }

    fun clearSchedule() {
        _uiState.value = _uiState.value.copy(anchorMonday = null)
        persist()
    }

    private fun persist() {
        scheduleRepository.save(_uiState.value.anchorMonday, _uiState.value.intervalWeeks)
    }
}
