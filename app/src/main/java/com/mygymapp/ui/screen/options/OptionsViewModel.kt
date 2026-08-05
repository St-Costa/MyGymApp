package com.mygymapp.ui.screen.options

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.PowerliftingScheduleRepository
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.data.sync.DiagnosticStep
import com.mygymapp.data.sync.SyncApi
import com.mygymapp.data.sync.SyncConfigRepository
import com.mygymapp.data.sync.SyncDiagnostics
import com.mygymapp.data.sync.SyncLedgerRepository
import com.mygymapp.data.sync.SyncWorker
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
    val syncLastSuccessAt: String? = null,
    val syncIsTestingConnection: Boolean = false,
    val syncConnectionTestResult: Boolean? = null, // null = not tested yet this session
    val syncIsResyncing: Boolean = false,
    val syncDiagnosticSteps: List<DiagnosticStep> = emptyList(),
    val syncDiagnosticRunning: Boolean = false,
)

@HiltViewModel
class OptionsViewModel @Inject constructor(
    private val scheduleRepository: PowerliftingScheduleRepository,
    private val syncConfigRepository: SyncConfigRepository,
    private val syncLedgerRepository: SyncLedgerRepository,
    private val syncApi: SyncApi,
    private val workoutRepository: WorkoutRepository,
    private val syncDiagnostics: SyncDiagnostics,
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

    private fun refreshSyncStatus() {
        viewModelScope.launch {
            val pending = syncLedgerRepository.pendingCount()
            val lastSuccess = syncLedgerRepository.lastSuccessfulSyncAt()
            _uiState.value = _uiState.value.copy(
                syncPendingCount = pending,
                syncLastSuccessAt = lastSuccess,
            )
        }
    }

    /**
     * Re-enqueues every completed session in `history/` for delivery, regardless of prior
     * SENT status. Backfill mechanism (docs/SYNC.md §1.5/§3.5) — used to rebuild the
     * server's raw store from scratch, or to resend everything after improving the
     * server-side parser during development.
     */
    fun resyncAll() {
        _uiState.value = _uiState.value.copy(syncIsResyncing = true)
        viewModelScope.launch {
            val sessions = workoutRepository.getAllCompletedSessions()
            withContext(Dispatchers.IO) {
                sessions.forEach { session ->
                    val file = workoutRepository.fileFor(session)
                    if (file.exists()) {
                        syncLedgerRepository.enqueue(
                            session.id,
                            workoutRepository.relPathFor(session),
                            file,
                        )
                    }
                }
            }
            SyncWorker.Scheduler.runExpedited(appContext)
            refreshSyncStatus()
            _uiState.value = _uiState.value.copy(syncIsResyncing = false)
        }
    }

    /**
     * Runs [SyncDiagnostics] (health check + real send + idempotency re-send) and surfaces
     * each labeled step in the UI. Every step is also written to AppLogger — see
     * `adb shell run-as com.mygymapp cat files/gymdata/logs/app.log`.
     */
    fun runDiagnostics() {
        _uiState.value = _uiState.value.copy(syncDiagnosticRunning = true, syncDiagnosticSteps = emptyList())
        viewModelScope.launch {
            val steps = syncDiagnostics.run()
            _uiState.value = _uiState.value.copy(syncDiagnosticRunning = false, syncDiagnosticSteps = steps)
            refreshSyncStatus()
        }
    }
}
