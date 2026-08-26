package com.mygymapp.ui.screen.options

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.PowerliftingScheduleRepository
import com.mygymapp.data.polar.ConnectionState
import com.mygymapp.data.polar.PolarManager
import com.mygymapp.data.polar.ReadinessRepository
import com.mygymapp.data.repository.FileManager
import com.mygymapp.data.repository.ScaleHistoryRepository
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.data.sync.EcgSyncLedgerRepository
import com.mygymapp.data.sync.EcgSyncWorker
import com.mygymapp.data.sync.ReadinessLedgerRepository
import com.mygymapp.data.sync.ReadinessSyncWorker
import com.mygymapp.data.sync.ScaleWeighInLedgerRepository
import com.mygymapp.data.sync.ScaleWeighInSyncWorker
import com.mygymapp.data.sync.SyncApi
import com.mygymapp.data.sync.SyncConfigRepository
import com.mygymapp.data.steps.HealthConnectStepsReader
import com.mygymapp.data.steps.StepLedgerRepository
import com.mygymapp.data.sync.SyncLedgerRepository
import com.mygymapp.data.sync.SyncWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

data class OptionsUiState(
    /** Monday of the selected powerlifting anchor week, or null if not set. */
    val anchorMonday: LocalDate? = null,
    val intervalWeeks: Int = 4,
    // Server sync (docs/SYNC.md)
    val syncServerUrl: String = "",
    val syncBearerToken: String = "",
    val syncEnabled: Boolean = false,
    val syncPendingCount: Int = 0,
    val syncSessionsPending: Int = 0,
    val syncScalePending: Int = 0,
    val syncEcgPending: Int = 0,
    val syncLastSuccessAt: String? = null,
    val syncIsTestingConnection: Boolean = false,
    val syncConnectionTestResult: Boolean? = null, // null = not tested yet this session
    val syncIsResyncing: Boolean = false,
    // Set when syncIsResyncing starts, to 0..1 as ledgers drain — see resyncAll().
    val syncResyncProgress: Float = 0f,
    // ECG debug send (docs/SYNC.md "Fourth record type: raw ECG")
    val ecgDebugRecording: Boolean = false,
    val ecgDebugSecondsLeft: Int = 0,
    val ecgDebugResult: String? = null,
    // Step-counter debug (Options → "Debug contapassi")
    val stepDebugRunning: Boolean = false,
    val stepDebugResult: String? = null,
)

@HiltViewModel
class OptionsViewModel @Inject constructor(
    private val scheduleRepository: PowerliftingScheduleRepository,
    private val syncConfigRepository: SyncConfigRepository,
    private val syncLedgerRepository: SyncLedgerRepository,
    private val syncApi: SyncApi,
    private val workoutRepository: WorkoutRepository,
    private val readinessRepository: ReadinessRepository,
    private val readinessLedgerRepository: ReadinessLedgerRepository,
    private val scaleHistoryRepository: ScaleHistoryRepository,
    private val scaleWeighInLedgerRepository: ScaleWeighInLedgerRepository,
    private val ecgSyncLedgerRepository: EcgSyncLedgerRepository,
    private val fileManager: FileManager,
    private val polarManager: PolarManager,
    private val stepLedgerRepository: StepLedgerRepository,
    private val healthConnectStepsReader: HealthConnectStepsReader,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OptionsUiState(
            anchorMonday = scheduleRepository.anchorMonday(),
            intervalWeeks = scheduleRepository.intervalWeeks(),
            syncServerUrl = syncConfigRepository.serverUrl(),
            syncBearerToken = syncConfigRepository.bearerToken(),
            syncEnabled = syncConfigRepository.isEnabled(),
        )
    )
    val uiState: StateFlow<OptionsUiState> = _uiState

    init {
        refreshSyncStatus()
        observeSyncWorkerCompletion()
        pollSyncStatusWhileScreenOpen()
    }

    /**
     * The status line ("N sessioni in attesa") was only ever refreshed right after
     * enqueueing work, not when that work actually *finished* — SyncWorker runs
     * asynchronously in the background (WorkManager), so "Resync all" would enqueue 58
     * sessions, immediately show "58 in attesa", and never update again even though the
     * worker went on to deliver all 58 within seconds. Observing WorkManager's own state
     * for the expedited work name means the status line refreshes itself the moment the
     * worker actually completes, regardless of who triggered it (auto-enqueue on session
     * end, "Resync all", or the periodic durability net).
     */
    private fun observeSyncWorkerCompletion() {
        val workManager = WorkManager.getInstance(appContext)
        listOf(
            SyncWorker.Scheduler.EXPEDITED_WORK_NAME,
            ReadinessSyncWorker.EXPEDITED_WORK_NAME,
            ScaleWeighInSyncWorker.EXPEDITED_WORK_NAME,
            EcgSyncWorker.EXPEDITED_WORK_NAME,
        ).forEach { workName ->
            viewModelScope.launch {
                workManager.getWorkInfosForUniqueWorkFlow(workName).collect { infos ->
                    if (infos.any { it.state.isFinished }) {
                        refreshSyncStatus()
                    }
                }
            }
        }
    }

    /**
     * Safety net alongside [observeSyncWorkerCompletion]: that observer only fires when a
     * *specific* unique WorkManager job transitions to a finished state, which can miss
     * cases where the pending count changed for another reason (e.g. a worker still
     * `ENQUEUED` waiting on network constraints never reaches `isFinished` on this launch,
     * or the periodic durability net running in the background fires while this screen
     * isn't observing it at all). A light poll while the screen is open — cheap, since
     * every ledger read is a small local YAML file — keeps the pending list from ever
     * looking silently stuck.
     */
    private fun pollSyncStatusWhileScreenOpen() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000L)
                refreshSyncStatus()
            }
        }
    }

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

    // ─── Server sync (docs/SYNC.md §1.5) ────────────────────────────────────

    fun setSyncServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(syncServerUrl = url, syncConnectionTestResult = null)
        persistSyncConfig()
    }

    fun setSyncBearerToken(token: String) {
        _uiState.value = _uiState.value.copy(syncBearerToken = token, syncConnectionTestResult = null)
        persistSyncConfig()
    }

    fun setSyncEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(syncEnabled = enabled)
        persistSyncConfig()
        if (enabled) SyncWorker.Scheduler.runExpedited(appContext)
    }

    private fun persistSyncConfig() {
        val s = _uiState.value
        syncConfigRepository.save(s.syncServerUrl, s.syncBearerToken, s.syncEnabled)
    }

    fun testConnection() {
        val url = _uiState.value.syncServerUrl
        if (url.isBlank()) return
        _uiState.value = _uiState.value.copy(syncIsTestingConnection = true, syncConnectionTestResult = null)
        viewModelScope.launch {
            val reachable = withContext(Dispatchers.IO) {
                syncApi.checkHealth(url) is com.mygymapp.data.sync.SyncResult.Success
            }
            _uiState.value = _uiState.value.copy(
                syncIsTestingConnection = false,
                syncConnectionTestResult = reachable,
            )
        }
    }

    /**
     * Reads all four ledgers (sessions + readiness + scale + ecg) fresh from disk — never
     * cached — and exposes both the per-type breakdown (shown as a bullet list) and the
     * summed total. Readiness has no dedicated UI bullet (no screen shows it standalone
     * today) but is still folded into [OptionsUiState.syncPendingCount].
     */
    private fun refreshSyncStatus() {
        viewModelScope.launch {
            val sessionsPending = syncLedgerRepository.pendingCount()
            val readinessPending = readinessLedgerRepository.getPending().size
            val scalePending = scaleWeighInLedgerRepository.getPending().size
            val ecgPending = ecgSyncLedgerRepository.getPending().size
            val lastSuccess = syncLedgerRepository.lastSuccessfulSyncAt()
            _uiState.value = _uiState.value.copy(
                syncPendingCount = sessionsPending + readinessPending + scalePending + ecgPending,
                syncSessionsPending = sessionsPending,
                syncScalePending = scalePending,
                syncEcgPending = ecgPending,
                syncLastSuccessAt = lastSuccess,
            )
        }
    }

    /**
     * Re-enqueues every session, readiness event, scale weigh-in, and still-on-disk raw
     * ECG file for delivery, regardless of prior SENT status — a full backfill across all
     * four independent sync pipelines (docs/SYNC.md §1.5/§3.5). Named "Invia tutti i dati
     * in coda" in the UI. Useful whenever the server was offline/not-yet-built when some of
     * this data was created (the common case while developing the server side — data is
     * always persisted and queued locally regardless of whether the server exists yet),
     * or to resend everything after improving a server-side parser.
     *
     * ECG is the one pipeline where this can't be a true backfill: unlike sessions/
     * readiness/scale (permanent local records), a raw `.ecg` file is deleted once
     * [EcgSyncWorker] confirms SENT — so this only re-enqueues whatever `.ecg` files still
     * happen to be sitting in `gymdata/ecg/` (pending/failed/not-yet-queued), not anything
     * already delivered and cleaned up.
     */
    fun resyncAll() {
        // Guards against a double-tap firing this twice concurrently — Compose recomposes
        // (and disables the button) only after this state write lands, so a second tap
        // landing in that window would otherwise race the first run and could read a file
        // the first run's EcgSyncWorker had already deleted (readBytes() on a gone file
        // throws FileNotFoundException, crashing the app — this happened for real, see
        // CHANGELOG).
        if (_uiState.value.syncIsResyncing) return
        _uiState.value = _uiState.value.copy(syncIsResyncing = true, syncResyncProgress = 0f)
        viewModelScope.launch {
            val sessions = workoutRepository.getAllCompletedSessions()
            val readinessEvents = readinessRepository.getAll()
            val weighIns = scaleHistoryRepository.getAll()
            val ecgFiles = fileManager.getDir("ecg").listFiles { f -> f.extension == "ecg" }?.toList().orEmpty()

            withContext(Dispatchers.IO) {
                sessions.forEach { session ->
                    val file = workoutRepository.fileFor(session)
                    if (file.exists()) {
                        syncLedgerRepository.enqueue(session.id, workoutRepository.relPathFor(session), file)
                    }
                }
                readinessEvents.forEach { event ->
                    val file = readinessRepository.fileFor(event)
                    if (file.exists()) {
                        readinessLedgerRepository.enqueue(event.id, "readiness/${event.id}.md", file)
                    }
                }
                weighIns.forEach { weighIn ->
                    val file = scaleHistoryRepository.fileFor(weighIn.id)
                    if (file.exists()) {
                        scaleWeighInLedgerRepository.enqueue(
                            weighIn.id,
                            scaleHistoryRepository.relPathFor(weighIn.id),
                            file,
                        )
                    }
                }
                ecgFiles.forEach { file ->
                    // Unlike the other three, this file can vanish between the listFiles()
                    // snapshot above and here — EcgSyncWorker deletes it as soon as a
                    // concurrent send confirms SENT (see class doc: "not a true backfill").
                    if (file.exists()) {
                        val sessionId = file.nameWithoutExtension
                        ecgSyncLedgerRepository.enqueue(sessionId, "ecg/${file.name}", file.readBytes())
                    }
                }
            }

            // Snapshot the total just-enqueued count once, right after enqueueing — this is
            // the denominator for the progress bar. Re-reading "pending" after this point
            // would undercount if new items got queued concurrently (e.g. a session ending
            // mid-resync) or overcount completed work as still-total, so the denominator is
            // fixed at start and the numerator (below) is "how many of THIS batch drained."
            val totalQueued = sessions.size + readinessEvents.size + weighIns.size + ecgFiles.size

            SyncWorker.Scheduler.runExpedited(appContext)
            ReadinessSyncWorker.Scheduler.runExpedited(appContext)
            ScaleWeighInSyncWorker.Scheduler.runExpedited(appContext)
            EcgSyncWorker.Scheduler.runExpedited(appContext)

            if (totalQueued > 0) {
                trackResyncProgress(totalQueued)
            }

            refreshSyncStatus()
            _uiState.value = _uiState.value.copy(syncIsResyncing = false, syncResyncProgress = 1f)
        }
    }

    /**
     * Polls the four ledgers' pending counts every second and derives a 0..1 progress
     * fraction from how much of [totalQueued] has drained, until either everything's gone
     * or a 60s timeout elapses (workers retry on their own after that — this is a UI
     * progress indicator, not a substitute for WorkManager's own retry/backoff).
     */
    private suspend fun trackResyncProgress(totalQueued: Int) {
        val deadline = System.currentTimeMillis() + RESYNC_PROGRESS_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val stillPending = syncLedgerRepository.pendingCount() +
                readinessLedgerRepository.getPending().size +
                scaleWeighInLedgerRepository.getPending().size +
                ecgSyncLedgerRepository.getPending().size
            val sent = (totalQueued - stillPending).coerceIn(0, totalQueued)
            val progress = sent.toFloat() / totalQueued
            _uiState.value = _uiState.value.copy(syncResyncProgress = progress)
            if (stillPending <= 0) return
            kotlinx.coroutines.delay(1_000L)
        }
    }

    // ─── ECG debug send (docs/SYNC.md "Fourth record type: raw ECG") ────────

    /**
     * Records ~10s of raw ECG from the currently connected Polar device and immediately
     * queues+sends it via the same [EcgSyncLedgerRepository]/[EcgSyncWorker] pipeline a
     * real workout session uses — a manual way to exercise `POST /v1/ecg` end to end
     * without needing to run a full session. Uses a synthetic id (`debug-{timestamp}`, not
     * a real 8-hex session id) so the server can tell debug uploads apart from genuine
     * session ECG recordings if it ever needs to.
     *
     * Requires a connected Polar device (checked via [PolarManager.connectionState]) and a
     * configured sync server — same requirement as "Invia tutti i dati in coda", since a
     * debug recording with nowhere to send it would just accumulate on disk.
     */
    fun sendDebugEcg() {
        if (polarManager.connectionState.value != ConnectionState.CONNECTED) {
            _uiState.value = _uiState.value.copy(ecgDebugResult = "Polar non connesso")
            return
        }
        if (!syncConfigRepository.isConfigured()) {
            _uiState.value = _uiState.value.copy(ecgDebugResult = "Server sync non configurato")
            return
        }

        val debugId = "debug-${System.currentTimeMillis()}"
        val totalSeconds = (DEBUG_ECG_RECORD_MILLIS / 1000L).toInt()
        _uiState.value = _uiState.value.copy(
            ecgDebugRecording = true,
            ecgDebugSecondsLeft = totalSeconds,
            ecgDebugResult = null,
        )
        viewModelScope.launch {
            polarManager.startEcgRecording(debugId)
            // Countdown shown in the button label — one tick per second, ticking down to 0
            // rather than up, so what's on screen matches "time remaining" directly.
            for (secondsLeft in totalSeconds - 1 downTo 0) {
                kotlinx.coroutines.delay(1_000L)
                _uiState.value = _uiState.value.copy(ecgDebugSecondsLeft = secondsLeft)
            }
            polarManager.stopEcgRecording()

            val fileSize = polarManager.ecgFileSize(debugId)
            if (fileSize <= 0L) {
                _uiState.value = _uiState.value.copy(
                    ecgDebugRecording = false,
                    ecgDebugResult = "Nessun dato registrato (Polar non ha inviato campioni ECG)",
                )
                return@launch
            }

            withContext(Dispatchers.IO) {
                val file = polarManager.ecgFileFor(debugId)
                ecgSyncLedgerRepository.enqueue(debugId, "ecg/${debugId}.ecg", file.readBytes())
            }
            EcgSyncWorker.Scheduler.runExpedited(appContext)
            refreshSyncStatus()

            _uiState.value = _uiState.value.copy(
                ecgDebugRecording = false,
                ecgDebugResult = "Registrati $fileSize byte, in coda per l'invio ($debugId)",
            )
        }
    }

    // ─── Step-counter debug (SYNC.md § Daily step average) ───────────────────

    /**
     * Confirms the Health Connect steps path actually works end to end — Health Connect
     * installed, permission granted, a real value comes back — without waiting for the next
     * readiness test. See [HealthConnectStepsReader]'s class doc for why this reads through
     * Health Connect rather than the raw `TYPE_STEP_COUNTER` sensor.
     *
     * Deliberately uses [StepLedgerRepository.peek] rather than
     * [StepLedgerRepository.recordReadingAndComputeAverage]: this button can be pressed any
     * number of times, and must never itself advance/consume the checkpoint the real
     * readiness flow diffs against, or it would corrupt the next real reading's day-span.
     *
     * "Ultime 24h" in the UI label is the delta since the last saved checkpoint (usually
     * yesterday's readiness test), not a literal rolling 24h window (see SYNC.md).
     */
    fun checkStepCounterDebug() {
        if (_uiState.value.stepDebugRunning) return
        _uiState.value = _uiState.value.copy(stepDebugRunning = true, stepDebugResult = null)
        viewModelScope.launch {
            if (!healthConnectStepsReader.isAvailable()) {
                _uiState.value = _uiState.value.copy(
                    stepDebugRunning = false,
                    stepDebugResult = "Health Connect non disponibile su questo dispositivo",
                )
                return@launch
            }
            if (!healthConnectStepsReader.hasReadPermission()) {
                _uiState.value = _uiState.value.copy(
                    stepDebugRunning = false,
                    stepDebugResult = "Permesso passi (Health Connect) non concesso — riapri la schermata Cuore per richiederlo",
                )
                return@launch
            }

            val reading = stepLedgerRepository.peek(healthConnectStepsReader)
            val resultText = if (reading != null) {
                "OK — passi dall'ultimo checkpoint: ${reading.avgStepsPerDay.toInt()}" +
                    if (reading.daysSpanned == 1) " (1 giorno)." else " su ${reading.daysSpanned} giorni (media)."
            } else {
                "OK, permesso concesso, ma nessun checkpoint precedente da confrontare (primo test readiness non ancora fatto)."
            }
            _uiState.value = _uiState.value.copy(stepDebugRunning = false, stepDebugResult = resultText)
        }
    }

    companion object {
        private const val DEBUG_ECG_RECORD_MILLIS = 10_000L
        private const val RESYNC_PROGRESS_TIMEOUT_MILLIS = 60_000L
    }
}
