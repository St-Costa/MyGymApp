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
import com.mygymapp.data.polar.PolarManager
import com.mygymapp.data.util.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Drains the raw-ECG sync ledger — the fourth independent sync worker alongside
 * [SyncWorker] (sessions), [ReadinessSyncWorker], and [ScaleWeighInSyncWorker]. Same
 * reasoning for staying dedicated rather than generalized: see docs/SYNC.md
 * "Extensibility".
 *
 * The key structural difference from the other three workers: the source `.ecg` file is
 * ephemeral (see [PolarManager.deleteEcgFile]) and this worker is now the thing that
 * triggers its deletion, and only after a confirmed successful upload — not on local
 * analysis success like before this feature existed (see docs/POLAR.md "Post-session
 * analysis" and docs/SYNC.md "Fourth record type: raw ECG"). If the server never confirms,
 * the file stays on disk and keeps retrying until [EcgSyncLedgerRepository.expireStale]'s
 * 30-day cap gives up on it.
 */
@HiltWorker
class EcgSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ledger: EcgSyncLedgerRepository,
    private val config: SyncConfigRepository,
    private val api: EcgSyncApi,
    private val polarManager: PolarManager,
    private val appLogger: AppLogger,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "EcgSyncWorker"
        private const val UNIQUE_EXPEDITED_NAME = "ecg-sync-expedited"
        private const val UNIQUE_PERIODIC_NAME = "ecg-sync-periodic"
        private const val MAX_AGE_DAYS = 30

        /** Exposed so the Options screen can observe completion and refresh its status line. */
        const val EXPEDITED_WORK_NAME = UNIQUE_EXPEDITED_NAME
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Age cap runs regardless of whether sync is configured — an unconfigured phone
        // should not silently accumulate .ecg files forever either. Runs first each pass.
        val expired = ledger.expireStale(MAX_AGE_DAYS)
        for (id in expired) {
            polarManager.deleteEcgFile(id)
            appLogger.w(TAG, "ECG sync expired for $id after $MAX_AGE_DAYS days pending; raw file deleted, unrecoverable")
        }

        // See docs/SYNC.md §1.5 + [shouldSyncRun]. Toggle OFF ⇒ only a forced run drains
        // the queue (session finalize / manual send); periodic net is a no-op. The age-cap
        // sweep above is deliberately *outside* this gate — stale .ecg files must be pruned
        // whatever the toggle says.
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
            val file = polarManager.ecgFileFor(entry.sessionId)
            if (!file.exists()) {
                appLogger.w(TAG, "Sync skip: ECG file for ${entry.sessionId} not found on disk")
                ledger.markFailed(entry.sessionId, "ecg file not found on disk")
                anyFailure = true
                continue
            }

            val compressed = try {
                gzip(file.readBytes())
            } catch (e: Exception) {
                appLogger.w(TAG, "ECG compress failed for ${entry.sessionId}: ${e.message}")
                ledger.markFailed(entry.sessionId, "compress failed: ${e.message}")
                anyFailure = true
                continue
            }
            val currentHash = ledger.hashOf(compressed)

            when (val result = api.postEcg(
                serverUrl = serverUrl,
                bearerToken = token,
                sessionId = entry.sessionId,
                relPath = entry.relPath,
                contentHash = currentHash,
                appVersion = BuildConfig.VERSION_NAME,
                fileName = file.name,
                compressedBytes = compressed,
            )) {
                is SyncResult.Success -> {
                    ledger.markSent(entry.sessionId)
                    // Deletion moved here from ActiveRoutineViewModel — only after a
                    // confirmed upload, not on local-analysis success (see class doc).
                    polarManager.deleteEcgFile(entry.sessionId)
                    appLogger.i(TAG, "Synced ECG ${entry.sessionId}: ${result.status}, raw file deleted")
                }
                is SyncResult.Failure -> {
                    ledger.markFailed(entry.sessionId, result.reason)
                    appLogger.w(TAG, "ECG sync failed for ${entry.sessionId}: ${result.reason}")
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
         *  session finalize / manual send only. (The age-cap sweep in doWork() ignores it.) */
        fun runExpedited(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<EcgSyncWorker>()
                .setConstraints(constraints)
                .setInputData(androidx.work.workDataOf(SYNC_FORCE_KEY to force))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_EXPEDITED_NAME,
                    if (force) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                    request,
                )
        }

        /** Durability net — see [ReadinessSyncWorker.Scheduler.ensurePeriodic] for the reasoning. */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<EcgSyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
