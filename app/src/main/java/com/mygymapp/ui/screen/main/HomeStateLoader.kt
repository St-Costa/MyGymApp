package com.mygymapp.ui.screen.main

import com.mygymapp.data.model.DayCellStatus
import com.mygymapp.data.model.Routine
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.parser.GitgraphHistoryCalculator
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.ui.components.DayStatus
import com.mygymapp.ui.components.ScheduleCell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the home screen's [MainUiState] (the gitgraph rows + current-week schedule row) and
 * caches it in a [StateFlow] shared process-wide.
 *
 * Why a singleton and not just [MainViewModel]: [MyGymApp.onCreate] kicks a [refresh] the
 * moment the process starts, so the work overlaps Activity/Compose creation instead of
 * serializing after it. By the time `MainViewModel` is constructed the state is usually
 * already in [state] and the screen renders with no further I/O. `MainViewModel` still calls
 * [refresh] on resume / `routinesChanged` so a just-finished workout shows up.
 *
 * The heavy part (the 28 history squares) is itself served from `history/_gitgraph.yaml` by
 * [WorkoutRepository.getGitgraphHistory]; this class adds only the small current-week queries.
 */
@Singleton
class HomeStateLoader @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val routineRepository: RoutineRepository,
    private val powerliftingScheduleRepository: com.mygymapp.data.PowerliftingScheduleRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state

    private var job: Job? = null

    /**
     * True once a phase-2 (fully-populated) state has been emitted at least once. Until then,
     * every load emits its phase-1 scaffold so the screen has something to paint; after that,
     * a load holds the last complete state on screen and swaps in one go (no schedule-row
     * blank flicker), even a `force` load that cancelled a first load mid-flight.
     */
    @Volatile
    private var everCompleted = false

    /**
     * Recomputes [state]. Concurrent calls coalesce: while one is running a plain call joins
     * it; [force] cancels the in-flight one and starts fresh (data actually changed).
     */
    fun refresh(force: Boolean = false) {
        val running = job
        if (!force && running?.isActive == true) return
        if (force) running?.cancel()
        job = scope.launch { loadHomeState() }
    }

    private fun DayCellStatus.toUiStatus(): DayStatus = when (this) {
        DayCellStatus.NONE -> DayStatus.NONE
        DayCellStatus.IMPROVED -> DayStatus.IMPROVED
        DayCellStatus.REGRESSED -> DayStatus.REGRESSED
    }

    private suspend fun loadHomeState() = coroutineScope {
        val today = LocalDate.now()
        val todayDow = today.dayOfWeek.value // 1=Mon, 7=Sun
        val currentWeekMonday = today.minusDays((todayDow - 1).toLong())
        // The 4 history rows cover the 4 weeks BEFORE the current one — the current week lives
        // only in the schedule row below, not duplicated here.
        val startDate = currentWeekMonday.minusWeeks(4)

        val dowKeys = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

        // Enabled routines assigned to each weekday column, resolved once — both the phase-1
        // scaffold and the phase-2 cells key off this instead of re-filtering allRoutines twice.
        fun routinesForColumn(allRoutines: List<Routine>, col: Int) =
            allRoutines.filter { it.day.equals(dowKeys[col], ignoreCase = true) && it.enabled }

        // Kick the slow half — parsing the current week's full-session .md files — off first
        // so it runs while we assemble and emit the fast half.
        val currentWeekDeferred = async {
            val cw = workoutRepository.getSessionsInRange(currentWeekMonday, today)
            val lastHist = cw.map { it.routineId }.distinct()
                .map { rid ->
                    async {
                        rid to workoutRepository.getLastSessionForRoutine(
                            rid,
                            beforeDate = currentWeekMonday,
                        )
                    }
                }
                .awaitAll()
                .toMap()
            cw to lastHist
        }

        // ── Phase 1: history rows + schedule scaffolding. Everything here is either the
        //    gitgraph cache (a small front-matter parse) or in-memory (routine list, prefs),
        //    so it's ready in ~200ms even on a cold start. Emit it with isLoading=false so the
        //    screen paints — the 4 history rows, which are most of it, are already final. The
        //    current-week session outcomes fill in on the phase-2 emit a moment later.
        val history = workoutRepository.getGitgraphHistory(today)
        val allRoutines = routineRepository.getAll()

        val days = history.days.map { it.status.toUiStatus() }
        val gitgraphTonnageChanges = history.days.map { it.tonnageChangePct }
        val gitgraphStretchMinutes = history.days.map { it.stretchMinutes }
        val gitgraphCardioMinutes = history.days.map { it.cardioMinutes }
        val routineNames = history.days.map { it.routineName }
        val sessionIds = history.days.map { it.sessionId }
        val sessionDates = history.days.map { d -> d.date.takeIf { d.sessionId != null } }
        val powerliftingWeeks = (0 until 4).map { week ->
            powerliftingScheduleRepository.isPowerliftingWeek(startDate.plusWeeks(week.toLong()))
        }

        fun scheduleScaffold() = dowKeys.indices.map { col ->
            val routines = routinesForColumn(allRoutines, col)
            ScheduleCell(
                routineNames = routines.map { it.name },
                openRoutineId = routines.firstOrNull()?.id,
            )
        }

        val phase1 = MainUiState(
            gitgraphDays = days,
            gitgraphTonnageChanges = gitgraphTonnageChanges,
            gitgraphStretchMinutes = gitgraphStretchMinutes,
            gitgraphCardioMinutes = gitgraphCardioMinutes,
            routineNames = routineNames,
            sessionIds = sessionIds,
            sessionDates = sessionDates,
            powerliftingWeeks = powerliftingWeeks,
            scheduleCells = scheduleScaffold(),
            todayDowIndex = todayDow - 1,
            isLoading = false,
        )
        // Emit the partial state only while the screen has nothing complete to show yet. Once
        // a phase-2 state has been emitted at least once (normal refresh, routinesChanged, or
        // a force after the first successful load), hold it on screen and swap in one go at
        // phase 2 below — emitting the scaffold here would blank the schedule row's outcomes
        // for the ~100ms until phase 2, a visible flicker.
        if (!everCompleted) {
            _state.value = phase1
        }

        // ── Phase 2: current-week session outcomes (schedule cells' status/%/minutes + the
        //    today* fields). Waits on the slow parse kicked above.
        val (currentWeekSessions, lastHistoricalByRoutine) = currentWeekDeferred.await()
        val currentWeekSessionsByRoutine: Map<String, List<WorkoutSession>> =
            (currentWeekSessions + lastHistoricalByRoutine.values.filterNotNull())
                .groupBy { it.routineId }

        val todayStr = today.toString()
        val todaySession = currentWeekSessions.filter { it.date == todayStr }.maxByOrNull { it.completedAt }
        val completedTodayRoutineIds = currentWeekSessions
            .filter { it.date == todayStr && it.completedAt.isNotBlank() }
            .map { it.routineId }
            .toSet()
        val currentWeekSessionByDate = currentWeekSessions
            .groupBy { it.date }
            .mapValues { (_, s) -> s.maxByOrNull { it.completedAt } }

        var todayStatus = DayStatus.NONE
        var todayTonnageChange: Double? = null
        var todayStretchMinutes: Int? = null
        var todayCardioMinutes: Int? = null
        todaySession?.let {
            val cell = GitgraphHistoryCalculator.dayCell(it, currentWeekSessionsByRoutine)
            todayStatus = cell.status.toUiStatus()
            todayTonnageChange = cell.tonnageChangePct
            todayStretchMinutes = cell.stretchMinutes
            todayCardioMinutes = cell.cardioMinutes
        }

        val scheduleCells = dowKeys.indices.map { col ->
            val routines = routinesForColumn(allRoutines, col)
            val toOpen = routines.firstOrNull { it.id !in completedTodayRoutineIds } ?: routines.firstOrNull()
            val dayDate = currentWeekMonday.plusDays(col.toLong())
            val session = if (col == todayDow - 1) null else currentWeekSessionByDate[dayDate.toString()]
            val sessionCell = session?.let {
                GitgraphHistoryCalculator.dayCell(it, currentWeekSessionsByRoutine)
            }
            ScheduleCell(
                routineNames = routines.map { it.name },
                openRoutineId = toOpen?.id,
                sessionStatus = sessionCell?.status?.toUiStatus() ?: DayStatus.NONE,
                sessionTonnageChange = sessionCell?.tonnageChangePct,
                sessionStretchMinutes = sessionCell?.stretchMinutes,
                sessionCardioMinutes = sessionCell?.cardioMinutes,
                sessionRoutineName = session?.routineName,
                sessionId = session?.id,
                sessionDate = session?.date,
            )
        }

        _state.value = phase1.copy(
            scheduleCells = scheduleCells,
            todayStatus = todayStatus,
            todayTonnageChange = todayTonnageChange,
            todayStretchMinutes = todayStretchMinutes,
            todayCardioMinutes = todayCardioMinutes,
            todayRoutineName = todaySession?.routineName,
            todaySessionId = todaySession?.id,
            todaySessionDate = todaySession?.date,
        )
        everCompleted = true
    }
}
