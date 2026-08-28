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
import com.mygymapp.data.DataChangedSignal
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.data.util.AppLogger
import com.mygymapp.ui.components.DayStatus
import com.mygymapp.ui.components.ScheduleCell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

data class MainUiState(
    // 4 history rows (28 squares) covering the 4 weeks BEFORE the current one — the current
    // week lives only in the schedule row below, not duplicated here.
    val gitgraphDays: List<DayStatus> = List(28) { DayStatus.NONE },
    // One value per square (28 total): % change vs previous session, null if no comparison
    val gitgraphTonnageChanges: List<Double?> = List(28) { null },
    // Fallback shown when there's no tonnage % to display (e.g. an all-cardio/warmup routine,
    // where tonnage is structurally always 0): total cardio minutes for that day's session,
    // null if the day has no session or no cardio blocks.
    val gitgraphCardioMinutes: List<Int?> = List(28) { null },
    // Routine name for each day with a session (28 values, one per square) — shown inside
    // the square itself so an out-of-schedule day is still identifiable at a glance.
    val routineNames: List<String?> = List(28) { null },
    // Session ID + date for each of the 28 squares (null = no session that day) — tapping a
    // square with a session navigates to its progress view.
    val sessionIds: List<String?> = List(28) { null },
    val sessionDates: List<String?> = List(28) { null },
    // One flag per gitgraph week-row (4): true = powerlifting week
    val powerliftingWeeks: List<Boolean> = List(4) { false },
    // Schedule row (current week): 7 cells, Monday..Sunday, each the routine(s) assigned that
    // day PLUS — for any day that already has a completed session this week — that session's
    // outcome (status/%/minutes/name/id), so GitgraphView renders it like a history cell.
    // The "today" cell additionally carries the today* fields below.
    val scheduleCells: List<ScheduleCell> = List(7) { ScheduleCell() },
    val todayDowIndex: Int = 0, // 0=Monday..6=Sunday, which scheduleCells entry is "today"
    // Today's own session outcome, same shape as one history square — null/NONE if today has
    // no session yet.
    val todayStatus: DayStatus = DayStatus.NONE,
    val todayTonnageChange: Double? = null,
    val todayCardioMinutes: Int? = null,
    val todayRoutineName: String? = null,
    val todaySessionId: String? = null,
    val todaySessionDate: String? = null,
    val isLoading: Boolean = true,
    val isSeedingData: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val routineRepository: RoutineRepository,
    private val dataChangedSignal: DataChangedSignal,
    private val appLogger: AppLogger,
    private val homeStateLoader: HomeStateLoader,
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    // Only the debug-seed spinner is ViewModel-local now; the home content comes from the
    // process-wide HomeStateLoader (kicked off in MyGymApp.onCreate, so it overlaps Activity
    // creation instead of running after it).
    private val _isSeedingData = MutableStateFlow(false)

    val uiState: StateFlow<MainUiState> =
        combine(homeStateLoader.state, _isSeedingData) { home, seeding ->
            home.copy(isSeedingData = seeding)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, homeStateLoader.state.value)

    init {
        // A resume of the home screen should pick up anything that landed since (a finished
        // workout, a new routine). Coalesced inside the loader, so overlapping with the
        // onCreate kick is free.
        homeStateLoader.refresh()
        viewModelScope.launch {
            workoutRepository.migrateOldSessionFiles()
            // Ghost-session cleanup, old-session pruning, and orphan-ECG cleanup, combined into
            // one pass over history/ and throttled internally (see WorkoutRepository.runMaintenance)
            // — no need to re-walk and re-parse the whole session history on every single launch.
            val result = workoutRepository.runMaintenance(LocalDate.now().minusMonths(3))
            appLogger.i(
                TAG,
                "Boot cleanup: ghosts=${result.ghostsDeleted} pruned=${result.prunedDeleted} orphanEcg=${result.orphanEcgDeleted}",
            )
            // Repair exercises/routines where repRangeMin > repRangeMax was persisted.
            exerciseRepository.fixInvalidRepRanges()
            routineRepository.fixInvalidRepRanges()
        }
        viewModelScope.launch {
            dataChangedSignal.routinesChanged.collect { homeStateLoader.refresh(force = true) }
        }
    }

    /** Called from MainScreen on (re)entry — a resume should reflect a just-finished workout. */
    fun loadGitgraph() = homeStateLoader.refresh()


    // ─── Debug seed ───────────────────────────────────────────────────────────

    fun seedDebugData() {
        if (_isSeedingData.value) return
        viewModelScope.launch {
            _isSeedingData.value = true
            try {
                seedDebugDataInternal()
            } finally {
                _isSeedingData.value = false
            }
            // The seed's save()/delete() calls already dropped the gitgraph cache; force a
            // full recompute so the home reflects the reseeded week immediately.
            homeStateLoader.refresh(force = true)
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
