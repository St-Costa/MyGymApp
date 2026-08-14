package com.mygymapp.ui.screen.sessionprogress

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.bestEstimated1RM
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.data.steps.HealthConnectStepsReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** A chart-ready trend across recent sessions: parallel data/label lists, zero/missing points dropped. */
data class ChartSeries(
    val data: List<Double> = emptyList(),
    val labels: List<String> = emptyList(),
)

data class SessionProgressUiState(
    val isLoading: Boolean = true,
    val routineName: String = "",
    val sessionCalories: Double = 0.0,
    val sessionTrimp: Double = 0.0,
    val vo2max: Double = 0.0,
    val sessionTonnage: List<Double> = emptyList(),
    val sessionBestE1RM: List<Double> = emptyList(),
    val sessionLabels: List<String> = emptyList(),
    // Cardio trend charts (one point per past session that recorded the metric).
    // Deep ECG-derived series (avg HR from ECG, RMSSD, SDNN, Poincaré ratio, arrhythmia
    // counts) were removed when that analysis moved server-side — see docs/SYNC.md
    // "Fourth record type: raw ECG". Only metrics computed from live HR/readiness
    // tracking remain.
    val hrrSeries: ChartSeries = ChartSeries(),
    val vo2maxSeries: ChartSeries = ChartSeries(),
    val restingHrSeries: ChartSeries = ChartSeries(),
    val cardiacDriftSeries: ChartSeries = ChartSeries(),
    // Display-only, never persisted/synced: steps walked during this specific session
    // (startedAt..completedAt), queried fresh from Health Connect each time this screen
    // loads — distinct from the daily-average figure that DOES get saved/synced via the
    // readiness pipeline (docs/SYNC.md § Daily step average), which is a whole-day total,
    // not scoped to a session. Null when unavailable (Health Connect not installed,
    // permission not granted, or startedAt missing on an old/legacy session).
    val sessionSteps: Long? = null,
)

@HiltViewModel
class SessionProgressViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workoutRepository: WorkoutRepository,
    private val healthConnectStepsReader: HealthConnectStepsReader,
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
        val labelFmt = DateTimeFormatter.ofPattern("d/M")

        val allSessions = workoutRepository.getSessionsInRange(startDate, LocalDate.parse(date))
            .filter { it.routineId == session.routineId }
        val sessionLabels = allSessions.map { LocalDate.parse(it.date).format(labelFmt) }

        val currentForza = session.exercises.filter { it.type == ExerciseType.FORZA && !it.excludeFromTonnage }
        val currentExerciseIds = currentForza.map { it.exerciseId }.toSet()
        val sessionTonnage = allSessions.map { hist ->
            hist.exercises
                .filter { it.exerciseId in currentExerciseIds && !it.excludeFromTonnage }
                .sumOf { ex -> ex.sets.filterIsInstance<ExerciseSet.Strength>().sumOf { it.reps * it.weight } }
        }

        // Best estimated 1RM per session, mirroring ActiveRoutineViewModel's chart data —
        // a max across exercises/sets, not a sum (see estimate1RM / bestEstimated1RM).
        val sessionBestE1RM = allSessions.map { hist ->
            hist.exercises
                .filter { it.exerciseId in currentExerciseIds && !it.excludeFromTonnage }
                .mapNotNull { ex -> ex.sets.filterIsInstance<ExerciseSet.Strength>().bestEstimated1RM() }
                .maxOrNull() ?: 0.0
        }

        // Cardio metrics aren't tied to a specific routine, so trend them across ALL completed
        // sessions in the window instead of just this routine's occurrences.
        val allCompletedSessions = workoutRepository.getSessionsInRange(startDate, LocalDate.parse(date))
            .filter { it.completedAt.isNotBlank() }
        val cardioLabels = allCompletedSessions.map { LocalDate.parse(it.date).format(labelFmt) }

        val sessionSteps = stepsDuringSession(session)

        _uiState.value = SessionProgressUiState(
            isLoading = false,
            routineName = session.routineName,
            sessionCalories = session.sessionCalories,
            sessionTrimp = session.sessionTrimp,
            vo2max = session.vo2max,
            sessionTonnage = sessionTonnage,
            sessionBestE1RM = sessionBestE1RM,
            sessionLabels = sessionLabels,
            hrrSeries = cardioSeries(allCompletedSessions, cardioLabels) { it.hrr60s },
            vo2maxSeries = cardioSeries(allCompletedSessions, cardioLabels) { it.vo2max },
            restingHrSeries = cardioSeries(allCompletedSessions, cardioLabels) { it.restingHr.toDouble() },
            cardiacDriftSeries = cardioSeries(allCompletedSessions, cardioLabels, hasData = { it != 0.0 }) { it.cardiacDriftBpmMin },
            sessionSteps = sessionSteps,
        )
    }

    /**
     * Steps walked during this specific session's own time window, read fresh from Health
     * Connect — display-only (see [SessionProgressUiState.sessionSteps]), nothing here gets
     * saved back onto the session or synced. Returns `null` rather than `0` whenever the
     * window can't be established or the query fails, so the UI can tell "no data" apart
     * from "zero steps really were taken."
     */
    private suspend fun stepsDuringSession(session: WorkoutSession): Long? {
        if (session.startedAt.isBlank() || session.completedAt.isBlank()) return null
        if (!healthConnectStepsReader.isAvailable()) return null
        val zone = ZoneId.systemDefault()
        val start = runCatching { LocalDateTime.parse(session.startedAt).atZone(zone).toInstant() }.getOrNull() ?: return null
        val end = runCatching { LocalDateTime.parse(session.completedAt).atZone(zone).toInstant() }.getOrNull() ?: return null
        if (!start.isBefore(end)) return null
        return healthConnectStepsReader.totalSteps(start, end)
    }

    /** Builds a chart series from sessions, keeping only points where [hasData] accepts the selected value. */
    private fun cardioSeries(
        sessions: List<WorkoutSession>,
        labels: List<String>,
        hasData: (Double) -> Boolean = { it > 0.0 },
        selector: (WorkoutSession) -> Double,
    ): ChartSeries {
        val data = mutableListOf<Double>()
        val lbls = mutableListOf<String>()
        sessions.forEachIndexed { i, s ->
            val v = selector(s)
            if (hasData(v)) {
                data += v
                lbls += labels.getOrElse(i) { "" }
            }
        }
        return ChartSeries(data, lbls)
    }
}
