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
import com.mygymapp.data.repository.FileManager
import com.mygymapp.data.util.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drains the repo-file sync ledger — the fifth independent sync worker alongside
 * [SyncWorker] (sessions), [ReadinessSyncWorker], [ScaleWeighInSyncWorker] and
 * [EcgSyncWorker]. See `docs/BACKUP.md` §3.4.
 *
 * Per pending [RepoLedgerEntry]:
 * - `op = "upsert"` → re-read `gymdata/<relPath>`, hash it fresh, `POST /v1/repo` with the
 *   file part. If the file is gone from disk, the user must have deleted it in the window
 *   between enqueue and now — a `delete` tombstone should already be queued too, so just
 *   drop this stale upsert (`markSent` — nothing to deliver).
 * - `op = "delete"` → no file to read; `POST /v1/repo` with `op: "delete"` and the
 *   last-known content hash.
 *
 * `stored` / `duplicate` / `deleted` / `already_absent` are all success. No retry cap —
 * a personal self-hosted server being down for a while is expected (same as the other four).
 */
@HiltWorker
class RepoSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ledger: RepoLedgerRepository,
    private val config: SyncConfigRepository,
    private val api: RepoSyncApi,
    private val fileManager: FileManager,
    private val appLogger: AppLogger,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RepoSyncWorker"
        private const val UNIQUE_EXPEDITED_NAME = "repo-sync-expedited"
        private const val UNIQUE_PERIODIC_NAME = "repo-sync-periodic"

        /** Exposed so the Options screen can observe completion and refresh its status line. */
        const val EXPEDITED_WORK_NAME = UNIQUE_EXPEDITED_NAME
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // isConfigured() (not isEnabled()) — same reasoning as SyncWorker.doWork(): the
        // enabled toggle gates whether NEW work is queued, never whether an already-queued
        // entry gets drained (those exist only from an explicit action).
        if (!config.isConfigured()) return@withContext Result.success()

        val serverUrl = config.serverUrl()
        val token = config.bearerToken()

        val pending = ledger.getPending()
        if (pending.isEmpty()) return@withContext Result.success()

        var anyFailure = false
        for (entry in pending) {
            val result = if (entry.op == "delete") {
                api.postDelete(
                    serverUrl = serverUrl,
                    bearerToken = token,
                    relPath = entry.relPath,
                    lastKnownHash = entry.contentHash,
                    appVersion = BuildConfig.VERSION_NAME,
                )
            } else {
                val file = File(fileManager.root, entry.relPath)
                if (!file.exists()) {
                    // The file was deleted between enqueue and now — a `delete` tombstone
                    // for it should already be queued. Nothing to upsert; retire this entry.
                    appLogger.w(TAG, "Repo upsert skip: ${entry.relPath} gone from disk (delete queued separately)")
                    ledger.markSent(entry.relPath)
                    continue
                }
                val currentHash = ledger.hashOf(file)
                api.postUpsert(
                    serverUrl = serverUrl,
                    bearerToken = token,
                    relPath = entry.relPath,
                    contentHash = currentHash,
                    appVersion = BuildConfig.VERSION_NAME,
                    file = file,
                )
            }

            when (result) {
                is SyncResult.Success -> {
                    ledger.markSent(entry.relPath)
                    appLogger.i(TAG, "Synced ${entry.op} ${entry.relPath}: ${result.status}")
                }
                is SyncResult.Failure -> {
                    ledger.markFailed(entry.relPath, result.reason)
                    appLogger.w(TAG, "Repo sync failed for ${entry.relPath}: ${result.reason}")
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
            val request = OneTimeWorkRequestBuilder<RepoSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_EXPEDITED_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Durability net — see [SyncWorker.Scheduler.ensurePeriodic] for the reasoning. */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RepoSyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
