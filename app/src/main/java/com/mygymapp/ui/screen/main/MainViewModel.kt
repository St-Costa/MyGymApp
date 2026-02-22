package com.mygymapp.ui.screen.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.Routine
import com.mygymapp.data.model.RoutineExercise
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.ui.components.DayStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

data class MainUiState(
    val gitgraphDays: List<DayStatus> = List(28) { DayStatus.NONE },
    val todayIndex: Int = 27,
    // One value per square (28 total): % change vs previous session, null if no comparison
    val gitgraphTonnageChanges: List<Double?> = List(28) { null },
    // Routine name for each day of the current week (7 values, last row only)
    val lastWeekRoutineNames: List<String?> = List(7) { null },
    val isLoading: Boolean = true,
    val isSeedingData: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val routineRepository: RoutineRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    init {
        viewModelScope.launch {
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
        val todayDow = today.dayOfWeek.value // 1=Mon, 7=Sun
        val currentWeekMonday = today.minusDays((todayDow - 1).toLong())
        val startDate = currentWeekMonday.minusWeeks(3) // 4 weeks total

        val sessions = workoutRepository.getSessionsInRange(startDate, today)
        val sessionsByRoutine = sessions.groupBy { it.routineId }

        val days = mutableListOf<DayStatus>()
        val gitgraphTonnageChanges = mutableListOf<Double?>()
        val lastWeekRoutineNames = mutableListOf<String?>()

        for (dayOffset in 0 until 28) {
            val date = startDate.plusDays(dayOffset.toLong())

            if (date.isAfter(today)) {
                days.add(DayStatus.NONE)
                gitgraphTonnageChanges.add(null)
                if (dayOffset >= 21) lastWeekRoutineNames.add(null)
                continue
            }

            val dateStr = date.toString()
            val daySessions = sessions.filter { it.date == dateStr }
            val lastSession = daySessions.maxByOrNull { it.completedAt }

            if (dayOffset >= 21) {
                lastWeekRoutineNames.add(lastSession?.routineName)
            }

            if (lastSession == null) {
                days.add(DayStatus.NONE)
                gitgraphTonnageChanges.add(null)
                continue
            }

            val previous = sessionsByRoutine[lastSession.routineId]
                ?.filter { it.date < dateStr }
                ?.maxByOrNull { it.completedAt }

            val status = if (previous != null) {
                if (lastSession.totalTonnage >= previous.totalTonnage) DayStatus.IMPROVED
                else DayStatus.REGRESSED
            } else {
                DayStatus.IMPROVED // first time doing this routine
            }

            val tonnageChange = if (previous != null && previous.totalTonnage > 0) {
                (lastSession.totalTonnage - previous.totalTonnage) / previous.totalTonnage * 100.0
            } else null

            days.add(status)
            gitgraphTonnageChanges.add(tonnageChange)
        }

        val todayIndex = 3 * 7 + (todayDow - 1)

        _uiState.value = MainUiState(
            gitgraphDays = days,
            todayIndex = todayIndex,
            gitgraphTonnageChanges = gitgraphTonnageChanges,
            lastWeekRoutineNames = lastWeekRoutineNames,
            isLoading = false,
        )
    }

    // ─── Debug seed ───────────────────────────────────────────────────────────

    fun seedDebugData() {
        if (_uiState.value.isSeedingData) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSeedingData = true)
            try {
                seedDebugDataInternal()
            } finally {
                _uiState.value = _uiState.value.copy(isSeedingData = false)
            }
            loadGitgraphInternal()
        }
    }

    private suspend fun seedDebugDataInternal() {
        val today = LocalDate.now()
        val todayDow = today.dayOfWeek.value
        val currentWeekMonday = today.minusDays((todayDow - 1).toLong())

        // 1. Delete all sessions from the current week
        val thisWeekSessions = workoutRepository.getSessionsInRange(currentWeekMonday, today)
        thisWeekSessions.forEach { workoutRepository.delete(it) }

        // 2. Create one FORZA and one STRETCH exercise
        val strengthEx = exerciseRepository.save(
            Exercise(
                id = "",
                name = "Debug Bench Press",
                type = ExerciseType.FORZA,
                bodypart = "Chest",
                defaultRepRangeMin = 8,
                defaultRepRangeMax = 12,
            )
        )
        val stretchEx = exerciseRepository.save(
            Exercise(
                id = "",
                name = "Debug Chest Stretch",
                type = ExerciseType.STRETCH,
                bodypart = "Chest",
            )
        )

        // 3. Create one routine
        val routine = routineRepository.save(
            Routine(
                id = "",
                name = "Debug Routine",
                day = "Monday",
                enabled = true,
                exercises = listOf(
                    RoutineExercise(
                        exerciseId = strengthEx.id,
                        sets = 3,
                        repRangeMin = 8,
                        repRangeMax = 12,
                    ),
                    RoutineExercise(
                        exerciseId = stretchEx.id,
                        sets = 2,
                        timePerSetSeconds = 30,
                    ),
                ),
            )
        )

        // 4. Baseline sessions in the previous week (Mon–Sun)
        //    Tonnage = 3 sets × 10 reps × weight
        val prevWeekWeights = listOf(27.0, 28.0, 27.0, 30.0, 29.0, 31.0, 28.0)
        val prevWeekMonday = currentWeekMonday.minusWeeks(1)
        for (col in 0 until 7) {
            val date = prevWeekMonday.plusDays(col.toLong())
            if (date.isAfter(today)) break
            createDebugSession(routine, strengthEx, stretchEx, date, prevWeekWeights[col])
        }

        // 5. Current week sessions up to today with varied tonnage
        val curWeekWeights = listOf(31.0, 29.0, 34.0, 33.0, 37.0, 35.0, 36.0)
        for (col in 0 until todayDow) {
            val date = currentWeekMonday.plusDays(col.toLong())
            createDebugSession(routine, strengthEx, stretchEx, date, curWeekWeights[col])
        }
    }

    private suspend fun createDebugSession(
        routine: Routine,
        strengthEx: Exercise,
        stretchEx: Exercise,
        date: LocalDate,
        weight: Double,
    ) {
        val reps = 10
        val sets = 3
        val tonnage = reps * weight * sets
        val completedAt = LocalDateTime.of(date.year, date.month, date.dayOfMonth, 10, 0).toString()

        workoutRepository.save(
            WorkoutSession(
                id = "",
                routineId = routine.id,
                routineName = routine.name,
                date = date.toString(),
                completedAt = completedAt,
                totalTonnage = tonnage,
                tonnageByBodypart = mapOf(strengthEx.bodypart to tonnage),
                exercises = listOf(
                    WorkoutExercise(
                        exerciseId = strengthEx.id,
                        exerciseName = strengthEx.name,
                        bodypart = strengthEx.bodypart,
                        type = ExerciseType.FORZA,
                        completed = true,
                        sets = List(sets) { ExerciseSet.Strength(reps = reps, weight = weight) },
                    ),
                    WorkoutExercise(
                        exerciseId = stretchEx.id,
                        exerciseName = stretchEx.name,
                        bodypart = stretchEx.bodypart,
                        type = ExerciseType.STRETCH,
                        completed = true,
                        sets = listOf(
                            ExerciseSet.Stretch(timeSeconds = 30, done = true),
                            ExerciseSet.Stretch(timeSeconds = 30, done = true),
                        ),
                    ),
                ),
            )
        )
    }
}
