package com.mygymapp.ui.screen.activeroutine

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ActiveRoutineUiState(
    val routineName: String = "",
    val notes: String = "",
    val exercises: List<ActiveExerciseUi> = emptyList(),
    val sessionId: String = "",
    val isLoading: Boolean = true,
    val allCompleted: Boolean = false,
    val totalTonnage: Double = 0.0,
    val previousTonnage: Double? = null,
    // Chart: one point per completed session (last 12 weeks), oldest first
    val sessionTonnage: List<Double> = emptyList(),
    val sessionTonnageByBodypart: Map<String, List<Double>> = emptyMap(),
    val sessionLabels: List<String> = emptyList(),
    val selectedChartFilter: String = "Totale",
    val isLoadingChart: Boolean = false,
)

data class ActiveExerciseUi(
    val exerciseId: String,
    val exerciseName: String,
    val type: ExerciseType,
    val bodypart: String,
    val completed: Boolean = false,
    val setCount: Int = 0,
    val tonnageChangePct: Double? = null,
)

@HiltViewModel
class ActiveRoutineViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineRepository: RoutineRepository,
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val routineId: String = savedStateHandle["routineId"] ?: ""

    private val _uiState = MutableStateFlow(ActiveRoutineUiState())
    val uiState: StateFlow<ActiveRoutineUiState> = _uiState

    private var currentSession: WorkoutSession? = null
    private var previousTonnageByExercise: Map<String, Double> = emptyMap()

    init {
        viewModelScope.launch {
            val routine = routineRepository.getById(routineId) ?: return@launch

            val exercises = routine.exercises.mapNotNull { re ->
                val exercise = exerciseRepository.getById(re.exerciseId) ?: return@mapNotNull null
                ActiveExerciseUi(
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    type = exercise.type,
                    bodypart = exercise.bodypart,
                    setCount = re.sets,
                )
            }

            // Load previous session BEFORE saving the current one, so we don't find ourselves
            val previousSession = workoutRepository.getLastSessionForRoutine(routineId)

            // Pre-compute per-exercise tonnage from previous session
            previousTonnageByExercise = previousSession?.exercises
                ?.associate { ex ->
                    ex.exerciseId to ex.sets
                        .filterIsInstance<ExerciseSet.Strength>()
                        .sumOf { it.reps * it.weight }
                } ?: emptyMap()

            // Create and save the workout session
            val workoutExercises = routine.exercises.mapNotNull { re ->
                val exercise = exerciseRepository.getById(re.exerciseId) ?: return@mapNotNull null
                val sets = (1..re.sets).map { _ ->
                    when (exercise.type) {
                        ExerciseType.FORZA -> ExerciseSet.Strength()
                        ExerciseType.STRETCH -> ExerciseSet.Stretch(timeSeconds = re.timePerSetSeconds)
                    }
                }
                WorkoutExercise(
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    bodypart = exercise.bodypart,
                    type = exercise.type,
                    sets = sets,
                )
            }

            val session = WorkoutSession(
                id = "",
                routineId = routineId,
                routineName = routine.name,
                date = LocalDate.now().toString(),
                exercises = workoutExercises,
                notes = routine.notes,
            )
            val saved = workoutRepository.save(session)
            currentSession = saved

            _uiState.value = ActiveRoutineUiState(
                routineName = routine.name,
                notes = routine.notes,
                exercises = exercises,
                sessionId = saved.id,
                isLoading = false,
                previousTonnage = previousSession?.totalTonnage,
            )
        }
    }

    fun markExerciseCompleted(exerciseId: String) {
        viewModelScope.launch {
            // Reload session from disk to get actual set data written by exercise screen
            val session = currentSession ?: return@launch
            val today = LocalDate.parse(session.date)
            val reloaded = workoutRepository.getSession(session.id, today) ?: session

            // Compute current tonnage for this exercise
            val currentExTonnage = reloaded.exercises
                .find { it.exerciseId == exerciseId }
                ?.sets?.filterIsInstance<ExerciseSet.Strength>()
                ?.sumOf { it.reps * it.weight } ?: 0.0

            val prevExTonnage = previousTonnageByExercise[exerciseId]
            val changePct: Double? = if (prevExTonnage != null && prevExTonnage > 0) {
                ((currentExTonnage - prevExTonnage) / prevExTonnage) * 100.0
            } else null

            val updatedExercises = _uiState.value.exercises.map { ex ->
                if (ex.exerciseId == exerciseId) {
                    ex.copy(
                        completed = true,
                        tonnageChangePct = if (ex.type == ExerciseType.FORZA) changePct else null,
                    )
                } else ex
            }
            val allCompleted = updatedExercises.all { it.completed }
            _uiState.value = _uiState.value.copy(
                exercises = updatedExercises,
                allCompleted = allCompleted,
            )

            if (allCompleted) {
                finalizeSession(reloaded)
            }
        }
    }

    fun updateNotes(notes: String) {
        _uiState.value = _uiState.value.copy(notes = notes)
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            currentSession = session.copy(notes = notes)
            workoutRepository.save(currentSession!!)
            // Also update the routine notes
            val routine = routineRepository.getById(routineId) ?: return@launch
            routineRepository.save(routine.copy(notes = notes))
        }
    }

    fun selectChartFilter(filter: String) {
        _uiState.value = _uiState.value.copy(selectedChartFilter = filter)
    }

    private fun finalizeSession(reloaded: WorkoutSession) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingChart = true)

            val updated = reloaded.copy(
                completedAt = LocalDateTime.now().toString(),
            )
            // Calculate tonnage from actual set data
            var totalTonnage = 0.0
            val tonnageByBodypart = mutableMapOf<String, Double>()
            for (ex in updated.exercises) {
                var exTonnage = 0.0
                for (set in ex.sets) {
                    if (set is ExerciseSet.Strength) {
                        exTonnage += set.reps * set.weight
                    }
                }
                totalTonnage += exTonnage
                tonnageByBodypart[ex.bodypart] =
                    (tonnageByBodypart[ex.bodypart] ?: 0.0) + exTonnage
            }
            val finalSession = updated.copy(
                totalTonnage = totalTonnage,
                tonnageByBodypart = tonnageByBodypart,
            )
            currentSession = finalSession
            workoutRepository.save(finalSession)

            // Load all sessions for this routine in the last 12 weeks, one point per session
            val today = LocalDate.now()
            val startDate = today.with(DayOfWeek.MONDAY).minusWeeks(11)
            val allSessions = workoutRepository.getSessionsInRange(startDate, today)
                .filter { it.routineId == routineId }  // already sorted by date ascending

            val labelFmt = DateTimeFormatter.ofPattern("d/M")
            val sessionLabels = allSessions.map { LocalDate.parse(it.date).format(labelFmt) }
            val sessionTonnage = allSessions.map { it.totalTonnage }

            // Only bodyparts with strength exercises
            val bodyparts = finalSession.exercises
                .filter { it.type == ExerciseType.FORZA }
                .map { it.bodypart }.distinct()
            val sessionTonnageByBodypart = bodyparts.associateWith { bp ->
                allSessions.map { it.tonnageByBodypart[bp] ?: 0.0 }
            }

            _uiState.value = _uiState.value.copy(
                totalTonnage = totalTonnage,
                sessionTonnage = sessionTonnage,
                sessionTonnageByBodypart = sessionTonnageByBodypart,
                sessionLabels = sessionLabels,
                selectedChartFilter = "Totale",
                isLoadingChart = false,
            )
        }
    }
}
