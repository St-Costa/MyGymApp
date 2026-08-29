package com.mygymapp.ui.screen.sessionprogress

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.bestEstimated1RM
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.polar.DisconnectStats
import com.mygymapp.data.polar.PolarManager
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.data.steps.HealthConnectStepsReader
import com.mygymapp.data.sync.BackupVerifier
import com.mygymapp.data.sync.BackupVerifyOutcome
import com.mygymapp.data.sync.BackupVerifyReport
import com.mygymapp.data.sync.SyncConfigRepository
import com.mygymapp.data.sync.SyncLedgerRepository
import com.mygymapp.data.sync.SyncStatus
import com.mygymapp.data.sync.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * State of the small "invio al server" reassurance box shown right after finishing a
 * session. [CHECKING] is the initial state: the box opens on "verifica connessione al
 * server…" and only resolves to [SYNC_OFF] once we've actually confirmed sync is off —
 * so a configured-and-enabled sync never flashes a misleading "disattivata" first.
 */
enum class SessionSyncStatus { CHECKING, SYNC_OFF, PENDING, SENT, FAILED }

data class SessionProgressUiState(
    val isLoading: Boolean = true,
    val routineName: String = "",
    val sessionCalories: Double = 0.0,
    val sessionTrimp: Double = 0.0,
    val vo2max: Double = 0.0,
    val sessionTonnage: List<Double> = emptyList(),
    val sessionBestE1RM: List<Double> = emptyList(),
    // Per-session totals across the same 12-week window / same routine as sessionTonnage,
    // in minutes: total time held across all STRETCH sets, and total time across all closed
    // cardio blocks. Their charts render only when THIS session (the last data point) has a
    // nonzero value — a routine with no stretch/cardio never shows an empty chart.
    val sessionStretchMinutes: List<Double> = emptyList(),
    val sessionCardioMinutes: List<Double> = emptyList(),
    val sessionLabels: List<String> = emptyList(),
    // The per-metric cardio trend charts (HRR / VO2max / resting HR / cardiac drift) were
    // dropped from this screen — the deeper ECG-derived analysis already moved server-side
    // (docs/SYNC.md "Fourth record type: raw ECG"), and these remaining four added clutter
    // without being acted on here.
    // Display-only, never persisted/synced: steps walked during this specific session
    // (startedAt..completedAt), queried fresh from Health Connect each time this screen
    // loads — distinct from the daily-average figure that DOES get saved/synced via the
    // readiness pipeline (docs/SYNC.md § Daily step average), which is a whole-day total,
    // not scoped to a session. Null when unavailable (Health Connect not installed,
    // permission not granted, or startedAt missing on an old/legacy session).
    val sessionSteps: Long? = null,
    // Small reassurance box shown right after finishing a session (justCompleted) — always
    // shown, even when sync is off (SYNC_OFF), so its absence is never mistaken for a bug.
    // Re-derived from the sync ledger, not a one-shot snapshot: refreshed whenever the
    // expedited SyncWorker (enqueued by ActiveRoutineViewModel.registerRoutine()) finishes.
    val syncStatus: SessionSyncStatus = SessionSyncStatus.CHECKING,
    // Only meaningful when syncStatus == SENT: the upload the server confirmed. bytesSent
    // is the raw session-file size, syncDurationMs the wall time of that POST, and
    // syncServerStatus the server's own receipt word ("stored" / "duplicate"). All from
    // the sync ledger entry for this session.
    val syncBytesSent: Long = 0,
    val syncDurationMs: Long = 0,
    val syncServerStatus: String = "",
    // Involuntary Polar strap drops during the session just finished. Only populated when
    // this screen is opened right after completing (justCompleted) — it's read live off
    // the @Singleton PolarManager, which resets the counter at the next session's start,
    // so reopening this screen later shows nothing. Box is hidden entirely when count==0.
    val polarDrops: DisconnectStats = DisconnectStats(),
    // Full-store backup round-trip (docs/BACKUP.md §3.7), run once when this screen opens
    // right after a session (justCompleted) and a sync server is configured. Same
    // BackupVerifier the Options "Verifica backup sul server" button uses. All three null/
    // false ⇒ box hidden.
    val backupVerifyRunning: Boolean = false,
    val backupVerifyError: String? = null,
    val backupVerifyReport: BackupVerifyReport? = null,
)

@HiltViewModel
class SessionProgressViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workoutRepository: WorkoutRepository,
    private val healthConnectStepsReader: HealthConnectStepsReader,
    private val syncConfigRepository: SyncConfigRepository,
    private val syncLedgerRepository: SyncLedgerRepository,
    private val backupVerifier: BackupVerifier,
    private val polarManager: PolarManager,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    private val date: String = checkNotNull(savedStateHandle["date"])
    private val justCompleted: Boolean = savedStateHandle["justCompleted"] ?: false

    private val _uiState = MutableStateFlow(SessionProgressUiState())
    val uiState: StateFlow<SessionProgressUiState> = _uiState

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { refreshSyncStatus() }
        observeSyncWorkerCompletion()
        pollSyncStatusWhilePending()
        if (justCompleted) viewModelScope.launch { runBackupVerify() }
    }

    /**
     * End-of-session full-store backup check (docs/BACKUP.md §3.7). Same [BackupVerifier]
     * the Options button runs — a real round-trip (manifest diff → direct POST of what
     * changed → read-back) against the user's exercises/routines. Runs once, on screen open
     * after a completed session; no retry/poll (unlike the session `SyncStatusBox`, which
     * tracks an async worker). A configured-but-unreachable server surfaces as
     * [SessionProgressUiState.backupVerifyError] in the box.
     */
    private suspend fun runBackupVerify() {
        if (!syncConfigRepository.isConfigured()) return
        _uiState.value = _uiState.value.copy(backupVerifyRunning = true, backupVerifyError = null, backupVerifyReport = null)
        _uiState.value = when (val outcome = backupVerifier.run()) {
            is BackupVerifyOutcome.HardFail ->
                _uiState.value.copy(backupVerifyRunning = false, backupVerifyError = outcome.reason)
            is BackupVerifyOutcome.Done ->
                _uiState.value.copy(backupVerifyRunning = false, backupVerifyReport = outcome.report)
        }
    }

    /**
     * Safety net alongside [observeSyncWorkerCompletion], same reasoning as
     * OptionsViewModel's poll: catches the case where the expedited worker already
     * finished before this screen started observing it (real race — registerRoutine()
     * calls runExpedited() and navigates here in the same breath). Only polls while still
     * PENDING so it settles down on its own once the result is known.
     */
    private fun pollSyncStatusWhilePending() {
        viewModelScope.launch {
            while (_uiState.value.syncStatus == SessionSyncStatus.PENDING ||
                _uiState.value.syncStatus == SessionSyncStatus.CHECKING ||
                _uiState.value.isLoading
            ) {
                kotlinx.coroutines.delay(2_000L)
                refreshSyncStatus()
            }
        }
    }

    /**
     * Mirrors OptionsViewModel.observeSyncWorkerCompletion(): the ledger entry for this
     * session flips PENDING → SENT/FAILED asynchronously once SyncWorker actually runs, so
     * the box needs to react to that instead of only reading a snapshot taken at screen load.
     */
    private fun observeSyncWorkerCompletion() {
        viewModelScope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(SyncWorker.Scheduler.EXPEDITED_WORK_NAME)
                .collect { infos ->
                    if (infos.any { it.state.isFinished }) refreshSyncStatus()
                }
        }
    }

    private suspend fun refreshSyncStatus() {
        if (!syncConfigRepository.isConfigured() || !syncConfigRepository.isEnabled()) {
            _uiState.value = _uiState.value.copy(syncStatus = SessionSyncStatus.SYNC_OFF)
            return
        }
        val entry = syncLedgerRepository.getAll().find { it.sessionId == sessionId }
        val status = when (entry?.status) {
            SyncStatus.SENT -> SessionSyncStatus.SENT
            SyncStatus.FAILED, SyncStatus.EXPIRED -> SessionSyncStatus.FAILED
            SyncStatus.PENDING -> SessionSyncStatus.PENDING
            // Not in the ledger yet (enqueue() hasn't landed the write) — treat as still in flight.
            null -> SessionSyncStatus.PENDING
            // DELETED_PENDING/DELETED_SENT are only produced by the repo-file ledger
            // (docs/BACKUP.md), never the session ledger — unreachable here.
            else -> SessionSyncStatus.PENDING
        }
        _uiState.value = _uiState.value.copy(
            syncStatus = status,
            syncBytesSent = entry?.bytesSent ?: 0L,
            syncDurationMs = entry?.durationMs ?: 0L,
            syncServerStatus = entry?.serverStatus ?: "",
        )
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

        // Stretch: total seconds held across every Stretch set of every STRETCH exercise.
        val sessionStretchMinutes = allSessions.map { hist ->
            val secs = hist.exercises
                .filter { it.type == ExerciseType.STRETCH }
                .flatMap { it.sets }
                .filterIsInstance<ExerciseSet.Stretch>()
                .sumOf { it.timeSeconds.toLong() }
            secs / 60.0
        }

        // Cardio: total seconds across every closed cardio block (blank endedAt = still
        // running / abandoned — skipped).
        val sessionCardioMinutes = allSessions.map { hist ->
            val secs = hist.exercises
                .flatMap { it.sets }
                .filterIsInstance<ExerciseSet.Cardio>()
                .filter { it.startedAt.isNotBlank() && it.endedAt.isNotBlank() }
                .sumOf { block ->
                    val start = runCatching { LocalDateTime.parse(block.startedAt) }.getOrNull()
                    val end = runCatching { LocalDateTime.parse(block.endedAt) }.getOrNull()
                    if (start != null && end != null) {
                        java.time.Duration.between(start, end).seconds.coerceAtLeast(0)
                    } else 0L
                }
            secs / 60.0
        }

        val sessionSteps = stepsDuringSession(session)

        _uiState.value = SessionProgressUiState(
            isLoading = false,
            routineName = session.routineName,
            sessionCalories = session.sessionCalories,
            sessionTrimp = session.sessionTrimp,
            vo2max = session.vo2max,
            sessionTonnage = sessionTonnage,
            sessionBestE1RM = sessionBestE1RM,
            sessionStretchMinutes = sessionStretchMinutes,
            sessionCardioMinutes = sessionCardioMinutes,
            sessionLabels = sessionLabels,
            sessionSteps = sessionSteps,
            polarDrops = if (justCompleted) polarManager.disconnectStats.value else DisconnectStats(),
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

}
