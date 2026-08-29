package com.mygymapp.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mygymapp.BuildConfig
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.data.util.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Drains the sync ledger: for each PENDING/FAILED entry, re-reads the session file from
 * disk, verifies its hash still matches, and POSTs it to the configured server. See
 * `docs/SYNC.md` §1.3.
 *
 * Never performed inline from a ViewModel — always scheduled through [Scheduler] so a
 * flaky/absent server can never block session save or navigation (same discipline as the
 * `completionSaved`/`onCleared()` save patterns elsewhere in this codebase).
 *
 * Both `stored` and `duplicate` server responses count as success (idempotent receiver —
 * see `docs/SYNC.md` §2.2/§3.3). No cap on retry attempts: a personal self-hosted server
 * being down for a while is expected and should catch up whenever it's back, not give up.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ledger: SyncLedgerRepository,
    private val config: SyncConfigRepository,
    private val api: SyncApi,
    private val workoutRepository: WorkoutRepository,
    private val appLogger: AppLogger,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val UNIQUE_PERIODIC_NAME = "sync-periodic"
        private const val UNIQUE_EXPEDITED_NAME = "sync-expedited"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // See docs/SYNC.md §1.5 + [shouldSyncRun]. With "Sincronizzazione attiva" OFF, only a
        // forced run drains the queue: the 4h periodic net is a no-op, and a session that
        // finished while OFF is delivered only because registerRoutine() forces this run.
        // "Resync all" / "Verifica backup" also force. Queued entries are never lost — they
        // wait for the next forced run.
        val force = inputData.getBoolean(SYNC_FORCE_KEY, false)
        if (!shouldSyncRun(config.isConfigured(), config.isEnabled(), force)) {
            return@withContext Result.success()
        }
        val serverUrl = config.serverUrl()
        val token = config.bearerToken()

        val pending = ledger.getPending()
        if (pending.isEmpty()) return@withContext Result.success()

        var anyFailure = false
        for (entry in pending) {
            val session = runCatching {
                val sessionId = entry.sessionId
                val date = entry.relPath.substringAfterLast('/').take(10)
                workoutRepository.getSession(sessionId, LocalDate.parse(date))
            }.getOrNull()

            val file = session?.let { workoutRepository.fileFor(it) }
            if (session == null || file == null || !file.exists()) {
                appLogger.w(TAG, "Sync skip: session ${entry.sessionId} file not found on disk")
                ledger.markFailed(entry.sessionId, "session file not found on disk")
                anyFailure = true
                continue
            }

            // Always hash the file fresh right before sending (rather than trusting
            // entry.contentHash from enqueue time) — guards against content that changed
            // underneath the ledger between enqueue and this attempt (docs/SYNC.md §1.3
            // step 2). The server must be told the hash of what's actually in the request.
            val currentHash = ledger.hashOf(file)

            when (val result = api.postSession(
                serverUrl = serverUrl,
                bearerToken = token,
                sessionId = entry.sessionId,
                relPath = entry.relPath,
                contentHash = currentHash,
                appVersion = BuildConfig.VERSION_NAME,
                file = file,
            )) {
                is SyncResult.Success -> {
                    ledger.markSent(
                        entry.sessionId,
                        bytesSent = result.bytesSent,
                        durationMs = result.durationMs,
                        serverStatus = result.status,
                    )
                    appLogger.i(TAG, "Synced session ${entry.sessionId}: ${result.status}")
                }
                is SyncResult.Failure -> {
                    ledger.markFailed(entry.sessionId, result.reason)
                    appLogger.w(TAG, "Sync failed for ${entry.sessionId}: ${result.reason}")
                    anyFailure = true
                }
            }
        }

        // WorkManager's own exponential backoff (configured at enqueue time) handles
        // spacing out retries — returning retry() here just triggers that backoff.
        if (anyFailure) Result.retry() else Result.success()
    }

    /** Enqueues one-off and periodic [SyncWorker] runs. Call from app start and after each enqueue. */
    object Scheduler {
        /** Exposed so the Options screen can observe completion and refresh its status line. */
        const val EXPEDITED_WORK_NAME = UNIQUE_EXPEDITED_NAME

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Tries to send promptly. [force] `true` bypasses the "Sincronizzazione attiva"
         * toggle for this run — pass it only from session finalize / a manual "send all"
         * button. An edit-triggered nudge passes `false`.
         */
        fun runExpedited(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(androidx.work.workDataOf(SYNC_FORCE_KEY to force))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_EXPEDITED_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Durability net: catches anything the expedited run couldn't send. Idempotent to call repeatedly. */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
