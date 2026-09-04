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
        RepoSyncCoordinator.withLock {
        // See docs/SYNC.md §1.5 + [shouldSyncRun]. With the "Sincronizzazione attiva" toggle
        // OFF, only a forced run drains the queue — the periodic net is a no-op, and a
        // catalogue edit no longer kicks an upload (RepoLedgerRepository still queues it, so
        // it rides out on the next end-of-session sync or "Invia dati in coda"). A forced
        // run is set by registerRoutine() (finalize) and the manual send buttons.
        val force = inputData.getBoolean(SYNC_FORCE_KEY, false)
        if (!shouldSyncRun(config.isConfigured(), config.isEnabled(), force)) {
            return@withLock Result.success()
        }

        val serverUrl = config.serverUrl()
        val token = config.bearerToken()

        val pending = ledger.getPending()
        if (pending.isEmpty()) return@withLock Result.success()

        // Build the bulk request. An `upsert` whose file vanished between enqueue and now
        // has a `delete` tombstone queued separately — retire the stale upsert here, don't
        // send it.
        val bulkEntries = mutableListOf<RepoBulkEntry>()
        for (entry in pending) {
            if (entry.op == "delete") {
                bulkEntries += RepoBulkEntry(entry.relPath, "delete", entry.contentHash, file = null)
            } else {
                val file = File(fileManager.root, entry.relPath)
                if (!file.exists()) {
                    appLogger.w(TAG, "Repo upsert skip: ${entry.relPath} gone from disk (delete queued separately)")
                    ledger.markSent(entry.relPath)
                    continue
                }
                bulkEntries += RepoBulkEntry(entry.relPath, "upsert", ledger.hashOf(file), file)
            }
        }
        if (bulkEntries.isEmpty()) return@withLock Result.success()

        val runId = System.nanoTime().toString(16)
        val bytes = bulkEntries.sumOf { it.file?.length() ?: 0L }
        val startedAt = System.currentTimeMillis()
        appLogger.i(TAG, "run=$runId start entries=${bulkEntries.size} bytes=$bytes")

        var anyFailure = false
        when (val outcome = api.postBulk(serverUrl, token, BuildConfig.VERSION_NAME, bulkEntries)) {
            is RepoBulkOutcome.Applied -> {
                for (r in outcome.results) {
                    if (r.isSuccess) {
                        ledger.markSent(r.relPath)
                        appLogger.i(TAG, "Synced ${r.relPath}: ${r.status}")
                    } else {
                        ledger.markFailed(r.relPath, r.error ?: "error")
                        appLogger.w(TAG, "Repo sync failed for ${r.relPath}: ${r.error}")
                        anyFailure = true
                    }
                }
                // A truncated `results` (fewer than we sent) means the rest are untouched —
                // leave them PENDING and retry.
                if (outcome.results.size < bulkEntries.size) {
                    appLogger.w(TAG, "Bulk returned ${outcome.results.size}/${bulkEntries.size} results — retrying rest")
                    anyFailure = true
                }
            }
            is RepoBulkOutcome.Failure -> {
                // Whole-request (or per-chunk) failure — mark every not-yet-resolved entry
                // FAILED so the status line reflects it, and retry the drain.
                for (e in bulkEntries) ledger.markFailed(e.relPath, outcome.reason)
                appLogger.w(TAG, "Repo bulk sync failed: ${outcome.reason}")
                anyFailure = true
            }
        }

        val durationMs = System.currentTimeMillis() - startedAt
        appLogger.i(TAG, "run=$runId finish entries=${bulkEntries.size} bytes=$bytes durationMs=$durationMs failed=$anyFailure")
        if (anyFailure) Result.retry() else Result.success()
        }
    }

    object Scheduler {
        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * [force] `true` bypasses the "Sincronizzazione attiva" toggle for this one run —
         * pass it only from an explicit user-meaningful action (session finalize, "Invia
         * dati in coda"). A plain edit-triggered nudge passes `false` and does nothing while
         * the toggle is off.
         */
        fun runExpedited(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<RepoSyncWorker>()
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
