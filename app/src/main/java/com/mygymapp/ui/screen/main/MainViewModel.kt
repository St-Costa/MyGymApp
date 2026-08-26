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
import kotlinx.coroutines.flow.StateFlow
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
    // day. The "today" cell is overridden by GitgraphView to render like a history cell
    // instead whenever todaySessionId is non-null (see the today* fields below).
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
    private val powerliftingScheduleRepository: com.mygymapp.data.PowerliftingScheduleRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    init {
        viewModelScope.launch {
            // Gitgraph load doesn't depend on any of the boot maintenance below, so it runs
            // as its own concurrent child job — the screen populates as soon as the slower of
            // the two finishes, instead of waiting for maintenance to complete first.
            launch { loadGitgraphInternal() }

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
            dataChangedSignal.routinesChanged.collect { loadGitgraphInternal() }
        }
    }

    fun loadGitgraph() {
        viewModelScope.launch { loadGitgraphInternal() }
    }

    private suspend fun loadGitgraphInternal() {
        val today = LocalDate.now()
        val todayDow = today.dayOfWeek.value // 1=Mon, 7=Sun
        val currentWeekMonday = today.minusDays((todayDow - 1).toLong())
        // The 4 history rows cover the 4 weeks BEFORE the current one — the current week lives
        // only in the schedule row below, not duplicated here. So the oldest row starts 4 weeks
        // before last Monday, and the newest row ends on last Sunday.
        val startDate = currentWeekMonday.minusWeeks(4)
        val historyEndDate = currentWeekMonday.minusDays(1) // last Sunday

        val sessions = workoutRepository.getSessionsInRange(startDate, historyEndDate)
        // Fetched further back than the visible 4 weeks purely so the FIRST visible week has
        // something to compare against too — without this, every square in the oldest row
        // would look like "first time doing this routine" (green, no %) whenever the routine's
        // actual previous session falls just outside the visible window.
        val lookbackSessions = workoutRepository.getSessionsInRange(startDate.minusMonths(2), startDate.minusDays(1))
        // Current week's sessions (for the schedule row's "today" cell + tap targets) queried
        // separately since they're outside the history window above.
        val currentWeekSessions = workoutRepository.getSessionsInRange(currentWeekMonday, today)
        val sessionsByRoutine = (sessions + lookbackSessions + currentWeekSessions).groupBy { it.routineId }

        val days = mutableListOf<DayStatus>()
        val gitgraphTonnageChanges = mutableListOf<Double?>()
        val gitgraphCardioMinutes = mutableListOf<Int?>()
        val routineNames = mutableListOf<String?>()
        val sessionIds = mutableListOf<String?>()
        val sessionDates = mutableListOf<String?>()

        for (dayOffset in 0 until 28) {
            val date = startDate.plusDays(dayOffset.toLong())
            val dateStr = date.toString()
            val daySessions = sessions.filter { it.date == dateStr }
            val lastSession = daySessions.maxByOrNull { it.completedAt }

            routineNames.add(lastSession?.routineName)
            sessionIds.add(lastSession?.id)
            sessionDates.add(if (lastSession != null) dateStr else null)

            if (lastSession == null) {
                days.add(DayStatus.NONE)
                gitgraphTonnageChanges.add(null)
                gitgraphCardioMinutes.add(null)
                continue
            }

            val previous = sessionsByRoutine[lastSession.routineId]
                ?.filter { it.date < dateStr }
                ?.maxByOrNull { it.completedAt }

            val (currTonnage, prevTonnage) = if (previous != null)
                computeCommonTonnage(lastSession, previous)
            else Pair(lastSession.totalTonnage, 0.0)

            val status = if (previous != null) {
                if (currTonnage >= prevTonnage) DayStatus.IMPROVED else DayStatus.REGRESSED
            } else {
                DayStatus.IMPROVED // first time doing this routine
            }

            val tonnageChange = if (previous != null && prevTonnage > 0)
                (currTonnage - prevTonnage) / prevTonnage * 100.0
            else null

            // Fallback for when there's no tonnage % to show (all-cardio/warmup routines,
            // where tonnage is structurally always 0, or a first-time routine with no prior
            // session to compare against): total cardio minutes for this day's session.
            val cardioMinutes = if (tonnageChange == null) cardioMinutesFor(lastSession) else null

            days.add(status)
            gitgraphTonnageChanges.add(tonnageChange)
            gitgraphCardioMinutes.add(cardioMinutes)
        }

        // One flag per week-row: the row's Monday is startDate + week*7.
        val powerliftingWeeks = (0 until 4).map { week ->
            powerliftingScheduleRepository.isPowerliftingWeek(startDate.plusWeeks(week.toLong()))
        }

        // Schedule row (current week, Monday..Sunday): for each day, the enabled routine(s)
        // assigned to it (Routine.day). The "today" cell additionally carries today's own
        // session outcome (status/%/minutes/name), same shape as a history cell, so
        // GitgraphView can render it like one once a session for today exists.
        val allRoutines = routineRepository.getAll()
        val todayStr = today.toString()
        val todaySession = currentWeekSessions.filter { it.date == todayStr }.maxByOrNull { it.completedAt }
        val completedTodayRoutineIds = currentWeekSessions
            .filter { it.date == todayStr && it.completedAt.isNotBlank() }
            .map { it.routineId }
            .toSet()

        var todayStatus = DayStatus.NONE
        var todayTonnageChange: Double? = null
        var todayCardioMinutes: Int? = null
        if (todaySession != null) {
            val previous = sessionsByRoutine[todaySession.routineId]
                ?.filter { it.date < todayStr }
                ?.maxByOrNull { it.completedAt }
            val (currTonnage, prevTonnage) = if (previous != null)
                computeCommonTonnage(todaySession, previous)
            else Pair(todaySession.totalTonnage, 0.0)
            todayStatus = if (previous != null) {
                if (currTonnage >= prevTonnage) DayStatus.IMPROVED else DayStatus.REGRESSED
            } else {
                DayStatus.IMPROVED
            }
            todayTonnageChange = if (previous != null && prevTonnage > 0)
                (currTonnage - prevTonnage) / prevTonnage * 100.0
            else null
            todayCardioMinutes = if (todayTonnageChange == null) cardioMinutesFor(todaySession) else null
        }

        val dowKeys = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        val scheduleCells = dowKeys.map { key ->
            val routines = allRoutines.filter { it.day.equals(key, ignoreCase = true) && it.enabled }
            // Tapping opens the first one not yet completed today, so finishing one and
            // tapping the same cell again moves on to the next.
            val toOpen = routines.firstOrNull { it.id !in completedTodayRoutineIds } ?: routines.firstOrNull()
            ScheduleCell(
                routineNames = routines.map { it.name },
                openRoutineId = toOpen?.id,
            )
        }

        _uiState.value = MainUiState(
            gitgraphDays = days,
            gitgraphTonnageChanges = gitgraphTonnageChanges,
            gitgraphCardioMinutes = gitgraphCardioMinutes,
            routineNames = routineNames,
            sessionIds = sessionIds,
            sessionDates = sessionDates,
            powerliftingWeeks = powerliftingWeeks,
            scheduleCells = scheduleCells,
            todayDowIndex = todayDow - 1,
            todayStatus = todayStatus,
            todayTonnageChange = todayTonnageChange,
            todayCardioMinutes = todayCardioMinutes,
            todayRoutineName = todaySession?.routineName,
            todaySessionId = todaySession?.id,
            todaySessionDate = todaySession?.date,
            isLoading = false,
        )
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Total minutes across every ExerciseSet.Cardio block in the session (all cardio
     * exercises, all blocks), rounded down. Null if the session has no closed cardio blocks —
     * mirrors CardioExerciseViewModel.blockDurationSeconds()'s parsing.
     */
    private fun cardioMinutesFor(session: WorkoutSession): Int? {
        val totalSeconds = session.exercises
            .flatMap { it.sets }
            .filterIsInstance<ExerciseSet.Cardio>()
            .filter { it.startedAt.isNotBlank() && it.endedAt.isNotBlank() }
            .sumOf { block ->
                val start = runCatching { LocalDateTime.parse(block.startedAt) }.getOrNull()
                val end = runCatching { LocalDateTime.parse(block.endedAt) }.getOrNull()
                if (start != null && end != null) {
                    java.time.Duration.between(start, end).seconds.coerceAtLeast(0)
                } else 0
            }
        return if (totalSeconds > 0) (totalSeconds / 60).toInt() else null
    }

    /**
     * Computes tonnage for each session using only exercises present in BOTH sessions.
     * This ensures the comparison is fair when routine composition has changed between sessions.
     * Falls back to totalTonnage if the two sessions share no exercises.
     */
    private fun computeCommonTonnage(s1: WorkoutSession, s2: WorkoutSession): Pair<Double, Double> {
        // Warmup + fixed-daily exercises never contribute to tonnage comparisons. An exercise the
        // lifter never touched (completed=false, no sets — see docs/CONVENTIONS.md "Untouched-exercise
        // guard") is excluded too: it wasn't really performed, so it shouldn't drag either session's
        // common-tonnage average toward zero.
        val commonIds = s1.exercises.filterNot { it.excludeFromTonnage || it.isUntouched() || it.completedEmpty }.map { it.exerciseId }.toSet()
            .intersect(s2.exercises.filterNot { it.excludeFromTonnage || it.isUntouched() || it.completedEmpty }.map { it.exerciseId }.toSet())
        if (commonIds.isEmpty()) return Pair(s1.totalTonnage, s2.totalTonnage)
        fun tonnageFor(s: WorkoutSession): Double = s.exercises
            .filter { it.exerciseId in commonIds && !it.excludeFromTonnage }
            .sumOf { ex -> ex.sets.filterIsInstance<ExerciseSet.Strength>().sumOf { it.reps * it.weight } }
        return Pair(tonnageFor(s1), tonnageFor(s2))
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
