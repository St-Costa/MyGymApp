package com.mygymapp.ui.screen.sessionprogress

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class SessionProgressUiState(
    val isLoading: Boolean = true,
    val routineName: String = "",
    val totalTonnage: Double = 0.0,
    val sessionTonnage: List<Double> = emptyList(),
    val sessionTonnageByBodypart: Map<String, List<Double>> = emptyMap(),
    val sessionLabels: List<String> = emptyList(),
    val selectedChartFilter: String = "Totale",
)

@HiltViewModel
class SessionProgressViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    private val date: String = checkNotNull(savedStateHandle["date"])

    private val _uiState = MutableStateFlow(SessionProgressUiState())
    val uiState: StateFlow<SessionProgressUiState> = _uiState

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val session = workoutRepository.getSession(sessionId, LocalDate.parse(date))
            ?: run {
                _uiState.value = _uiState.value.copy(isLoading = false)
                return
            }

        val startDate = LocalDate.parse(date).with(DayOfWeek.MONDAY).minusWeeks(11)
        val allSessions = workoutRepository.getSessionsInRange(startDate, LocalDate.parse(date))
            .filter { it.routineId == session.routineId }

        val labelFmt = DateTimeFormatter.ofPattern("d/M")
        val sessionLabels = allSessions.map { LocalDate.parse(it.date).format(labelFmt) }

        val currentForza = session.exercises.filter { it.type == ExerciseType.FORZA }
        val currentExerciseIds = currentForza.map { it.exerciseId }.toSet()
        val sessionTonnage = allSessions.map { hist ->
            hist.exercises
                .filter { it.exerciseId in currentExerciseIds }
                .sumOf { ex -> ex.sets.filterIsInstance<ExerciseSet.Strength>().sumOf { it.reps * it.weight } }
        }

        val bodyparts = currentForza.map { it.bodypart }.distinct()
        val sessionTonnageByBodypart = bodyparts.associateWith { bp ->
            val bpIds = currentForza.filter { it.bodypart == bp }.map { it.exerciseId }.toSet()
            allSessions.map { hist ->
                hist.exercises
                    .filter { it.exerciseId in bpIds }
                    .sumOf { ex -> ex.sets.filterIsInstance<ExerciseSet.Strength>().sumOf { it.reps * it.weight } }
            }
        }

        _uiState.value = SessionProgressUiState(
            isLoading = false,
            routineName = session.routineName,
            totalTonnage = session.totalTonnage,
            sessionTonnage = sessionTonnage,
            sessionTonnageByBodypart = sessionTonnageByBodypart,
            sessionLabels = sessionLabels,
            selectedChartFilter = "Totale",
        )
    }

    fun selectFilter(filter: String) {
        _uiState.value = _uiState.value.copy(selectedChartFilter = filter)
    }
}
