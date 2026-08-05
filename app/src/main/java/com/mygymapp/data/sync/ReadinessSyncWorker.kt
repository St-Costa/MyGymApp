package com.mygymapp.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
 * never contends with or risks the other. No periodic durability net for readiness (unlike
 * sessions) — a missed readiness sync just waits for the next expedited trigger from
 * PolarManager, which happens once per HR connect; add one later if that's not enough.
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
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // isEnabled() gates whether PolarManager queues a NEW readiness event, not
        // whether already-queued entries get drained here — same reasoning as
        // SyncWorker.doWork() (docs/SYNC.md §1.5).
        if (!config.isConfigured()) return@withContext Result.success()

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

            val currentHash = ledger.hashOf(file)
            val hashToSend = if (currentHash != entry.contentHash) currentHash else entry.contentHash

            when (val result = api.postReadiness(
                serverUrl = serverUrl,
                bearerToken = token,
                eventId = entry.sessionId,
                relPath = entry.relPath,
                contentHash = hashToSend,
                appVersion = BuildConfig.VERSION_NAME,
                file = file,
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

        fun runExpedited(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReadinessSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_EXPEDITED_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
