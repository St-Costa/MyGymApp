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
import com.mygymapp.data.repository.ScaleHistoryRepository
import com.mygymapp.data.util.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Drains the scale weigh-in sync ledger — the third independent sync worker alongside
 * [SyncWorker] (sessions) and [ReadinessSyncWorker]. Same reasoning for staying dedicated
 * rather than generalized: see docs/SYNC.md "Extensibility".
 *
 * No periodic durability net (like readiness, unlike sessions) — a missed send waits for
 * the next weigh-in to trigger another expedited run. The scale is used roughly daily in
 * practice, so this is an even shorter worst-case gap than readiness's "next HR connect."
 */
@HiltWorker
class ScaleWeighInSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ledger: ScaleWeighInLedgerRepository,
    private val config: SyncConfigRepository,
    private val api: ScaleWeighInSyncApi,
    private val scaleHistoryRepository: ScaleHistoryRepository,
    private val appLogger: AppLogger,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ScaleWeighInSyncWorker"
        private const val UNIQUE_EXPEDITED_NAME = "scale-sync-expedited"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // isEnabled() gates whether BleScaleManager queues a NEW weigh-in, not whether
        // already-queued entries get drained here — same reasoning as SyncWorker/
        // ReadinessSyncWorker (docs/SYNC.md §1.5).
        if (!config.isConfigured()) return@withContext Result.success()

        val serverUrl = config.serverUrl()
        val token = config.bearerToken()

        val pending = ledger.getPending()
        if (pending.isEmpty()) return@withContext Result.success()

        var anyFailure = false
        for (entry in pending) {
            val weighIn = scaleHistoryRepository.getById(entry.sessionId)
            val file = weighIn?.let { scaleHistoryRepository.fileFor(it.id) }
            if (weighIn == null || file == null || !file.exists()) {
                appLogger.w(TAG, "Sync skip: weigh-in ${entry.sessionId} file not found on disk")
                ledger.markFailed(entry.sessionId, "weigh-in file not found on disk")
                anyFailure = true
                continue
            }

            val currentHash = ledger.hashOf(file)
            val hashToSend = if (currentHash != entry.contentHash) currentHash else entry.contentHash

            when (val result = api.postWeighIn(
                serverUrl = serverUrl,
                bearerToken = token,
                weighInId = entry.sessionId,
                relPath = entry.relPath,
                contentHash = hashToSend,
                appVersion = BuildConfig.VERSION_NAME,
                file = file,
            )) {
                is SyncResult.Success -> {
                    ledger.markSent(entry.sessionId)
                    appLogger.i(TAG, "Synced weigh-in ${entry.sessionId}: ${result.status}")
                }
                is SyncResult.Failure -> {
                    ledger.markFailed(entry.sessionId, result.reason)
                    appLogger.w(TAG, "Weigh-in sync failed for ${entry.sessionId}: ${result.reason}")
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
            val request = OneTimeWorkRequestBuilder<ScaleWeighInSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_EXPEDITED_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
