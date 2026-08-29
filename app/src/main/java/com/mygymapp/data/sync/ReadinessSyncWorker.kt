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
import com.mygymapp.data.polar.ReadinessRepository
import com.mygymapp.data.util.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Drains the readiness sync ledger — the readiness-event counterpart of [SyncWorker].
 * Deliberately its own worker rather than a generalized one (docs/SYNC.md
 * "Extensibility"): readiness events fire immediately after a 60s measurement, whereas
 * sessions fire once at workout end, and keeping them independent means retrying one kind
 * never contends with or risks the other.
 */
@HiltWorker
class ReadinessSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ledger: ReadinessLedgerRepository,
    private val config: SyncConfigRepository,
    private val api: ReadinessSyncApi,
    private val readinessRepository: ReadinessRepository,
    private val appLogger: AppLogger,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ReadinessSyncWorker"
        private const val UNIQUE_EXPEDITED_NAME = "readiness-sync-expedited"
        private const val UNIQUE_PERIODIC_NAME = "readiness-sync-periodic"

        /** Exposed so the Options screen can observe completion and refresh its status line. */
        const val EXPEDITED_WORK_NAME = UNIQUE_EXPEDITED_NAME
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // See docs/SYNC.md §1.5 + [shouldSyncRun]. Toggle OFF ⇒ only a forced run drains
        // the queue (session finalize / manual send); the periodic net is a no-op and a
        // new readiness event is queued but not uploaded until then.
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
            val event = readinessRepository.getById(entry.sessionId)
            val file = event?.let { readinessRepository.fileFor(it) }
            if (event == null || file == null || !file.exists()) {
                appLogger.w(TAG, "Sync skip: readiness event ${entry.sessionId} file not found on disk")
                ledger.markFailed(entry.sessionId, "readiness file not found on disk")
                anyFailure = true
                continue
            }

            // Always hash the file fresh right before sending, not entry.contentHash from
            // enqueue time — same reasoning as SyncWorker.doWork().
            val currentHash = ledger.hashOf(file)

            when (val result = api.postReadiness(
                serverUrl = serverUrl,
                bearerToken = token,
                eventId = entry.sessionId,
                relPath = entry.relPath,
                contentHash = currentHash,
                appVersion = BuildConfig.VERSION_NAME,
                file = file,
                stepsAvgPerDay = event.stepsAvgPerDay,
                stepsDaysSpanned = event.stepsDaysSpanned,
                stepsPreviousDay = event.stepsPreviousDay,
            )) {
                is SyncResult.Success -> {
                    ledger.markSent(entry.sessionId)
                    appLogger.i(TAG, "Synced readiness ${entry.sessionId}: ${result.status}")
                }
                is SyncResult.Failure -> {
                    ledger.markFailed(entry.sessionId, result.reason)
                    appLogger.w(TAG, "Readiness sync failed for ${entry.sessionId}: ${result.reason}")
                    anyFailure = true
                }
            }
        }

        if (anyFailure) Result.retry() else Result.success()
    }

    object Scheduler {
        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** [force] `true` bypasses the "Sincronizzazione attiva" toggle for this run —
         *  session finalize / manual send only. */
        fun runExpedited(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<ReadinessSyncWorker>()
                .setConstraints(constraints)
                .setInputData(androidx.work.workDataOf(SYNC_FORCE_KEY to force))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_EXPEDITED_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Durability net, same role as [SyncWorker.Scheduler.ensurePeriodic] — catches
         * anything an expedited run couldn't send (e.g. the app was killed before
         * WorkManager persisted the retry). Matters most for a server that's expected to
         * be offline for days at a time: without this, a lost expedited run would just
         * never retry until the next HR connect happens to trigger a new one.
         */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReadinessSyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
