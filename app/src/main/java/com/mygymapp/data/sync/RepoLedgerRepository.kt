package com.mygymapp.data.sync

import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local durable ledger for the **repo-file** sync pipeline — the fifth independent record
 * type alongside sessions ([SyncLedgerRepository]), readiness ([ReadinessLedgerRepository]),
 * scale ([ScaleWeighInLedgerRepository]) and ECG ([EcgSyncLedgerRepository]). Covers every
 * human-authored file under `exercises/` and `routines/`. See `docs/BACKUP.md` §3.1.
 *
 * Same deliberate non-generalization as the other four: a dedicated small class rather than
 * touching what's already verified in production.
 *
 * Two structural differences from the other ledgers:
 * - **Keyed by relative path** (`exercises/{slug}-{id}.md`), not an id — the slug in the
 *   filename changes on rename, so the path is the stable-per-file-version key.
 * - **Syncs deletions.** An exercise/routine removed on the phone (or a stale path left
 *   behind by a rename) becomes a `DELETED_PENDING` entry with `op: delete`; the worker
 *   POSTs a tombstone and the entry moves to `DELETED_SENT`. The entry is kept (not
 *   dropped) so a re-scan can't resurrect the file server-side.
 */
@Singleton
class RepoLedgerRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val mutex = Mutex()
    private val loadSettings = LoadSettings.builder().build()

    private fun ledgerFile(): File = File(fileManager.getDir("_sync"), "repo_state.yml")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Sha256 of [bytes], prefixed `sha256:` to match the wire envelope format. */
    fun hashOf(bytes: ByteArray): String = "sha256:${sha256(bytes)}"

    fun hashOf(file: File): String = hashOf(file.readBytes())

    // ─── Read ─────────────────────────────────────────────────────────────────

    private fun readAllUnlocked(): MutableMap<String, RepoLedgerEntry> {
        val file = ledgerFile()
        if (!file.exists()) return mutableMapOf()
        val root = try {
            Load(loadSettings).loadFromString(file.readText())
        } catch (_: Exception) {
            null
        }
        @Suppress("UNCHECKED_CAST")
        val filesMap = (root as? Map<*, Any?>)?.get("files") as? Map<*, Any?>
            ?: return mutableMapOf()

        val result = LinkedHashMap<String, RepoLedgerEntry>()
        for ((rawKey, raw) in filesMap) {
            // Keys are always written quoted (see writeAllUnlocked); toString() defensively
            // in case an older file left one bare.
            val relPath = rawKey?.toString() ?: continue
            @Suppress("UNCHECKED_CAST")
            val fields = raw as? Map<String, Any?> ?: continue
            result[relPath] = RepoLedgerEntry(
                relPath = relPath,
                op = fields["op"] as? String ?: "upsert",
                status = (fields["status"] as? String)?.let {
                    runCatching { SyncStatus.valueOf(it) }.getOrNull()
                } ?: SyncStatus.PENDING,
                attempts = (fields["attempts"] as? Number)?.toInt() ?: 0,
                lastAttemptAt = fields["lastAttemptAt"] as? String ?: "",
                lastError = fields["lastError"] as? String ?: "",
                contentHash = fields["contentHash"] as? String ?: "",
            )
        }
        return result
    }

    suspend fun getAll(): List<RepoLedgerEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { readAllUnlocked().values.toList() }
    }

    /** Everything still owed to the server: PENDING upserts, FAILED (retryable), and
     *  DELETED_PENDING tombstones. SENT and DELETED_SENT are done. */
    suspend fun getPending(): List<RepoLedgerEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readAllUnlocked().values.filter {
                it.status == SyncStatus.PENDING ||
                    it.status == SyncStatus.FAILED ||
                    it.status == SyncStatus.DELETED_PENDING
            }
        }
    }

    suspend fun pendingCount(): Int = getPending().size

    // ─── Write ────────────────────────────────────────────────────────────────

    private fun writeAllUnlocked(entries: Map<String, RepoLedgerEntry>) {
        val sb = StringBuilder()
        sb.appendLine("files:")
        for ((relPath, entry) in entries) {
            // Quoted: the key contains '/' and '-' and would otherwise be an awkward YAML
            // scalar. A leading '"' in the key itself is not possible (slugify strips it).
            sb.appendLine("  \"$relPath\":")
            sb.appendLine("    op: ${entry.op}")
            sb.appendLine("    status: ${entry.status.name}")
            sb.appendLine("    attempts: ${entry.attempts}")
            sb.appendLine("    lastAttemptAt: \"${entry.lastAttemptAt}\"")
            sb.appendLine("    lastError: \"${entry.lastError.replace("\"", "'")}\"")
            sb.appendLine("    contentHash: \"${entry.contentHash}\"")
        }
        ledgerFile().writeText(sb.toString())
    }

    /**
     * If [relPath]'s content hash differs from what's recorded (or there's no entry yet),
     * mark it `PENDING`/`op=upsert` with the new hash. No-op if the hash is unchanged —
     * this is what makes the push incremental (an exercise re-saved with no real change
     * doesn't re-send). Also un-tombstones a path that was `DELETED_*` and has reappeared.
     *
     * Called from [com.mygymapp.data.repository.ExerciseRepository.save] /
     * [com.mygymapp.data.repository.RoutineRepository.save], after the file write.
     */
    suspend fun requeueIfChanged(relPath: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val newHash = hashOf(bytes)
            val existing = all[relPath]
            // Already delivered this exact content as an upsert — nothing to do. Any other
            // state (never seen, PENDING/FAILED, or a stale DELETED_* the file has come
            // back from) falls through to a fresh PENDING upsert.
            if (existing != null &&
                existing.status == SyncStatus.SENT &&
                existing.op == "upsert" &&
                existing.contentHash == newHash
            ) {
                return@withLock
            }
            all[relPath] = RepoLedgerEntry(
                relPath = relPath,
                op = "upsert",
                status = SyncStatus.PENDING,
                attempts = 0,
                contentHash = newHash,
            )
            writeAllUnlocked(all)
        }
    }

    /**
     * Tombstone [relPath] for deletion server-side. Keeps the entry (as `DELETED_PENDING`)
     * rather than dropping it, so a later full re-scan can't re-upsert a file the user
     * deleted. [lastKnownHash] rides along so the server can confirm which version it's
     * tombstoning. No-op if the path is already `DELETED_*` (idempotent).
     *
     * Called from `ExerciseRepository.delete` / `RoutineRepository.delete`, and from the
     * rename path for the now-obsolete old `{slug}-{id}.md`.
     */
    suspend fun markDeleted(relPath: String, lastKnownHash: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val existing = all[relPath]
            if (existing != null &&
                (existing.status == SyncStatus.DELETED_PENDING || existing.status == SyncStatus.DELETED_SENT)
            ) {
                return@withLock
            }
            all[relPath] = RepoLedgerEntry(
                relPath = relPath,
                op = "delete",
                status = SyncStatus.DELETED_PENDING,
                attempts = 0,
                contentHash = lastKnownHash,
            )
            writeAllUnlocked(all)
        }
    }

    suspend fun markSent(relPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val existing = all[relPath] ?: return@withLock
            val nextStatus = if (existing.op == "delete") SyncStatus.DELETED_SENT else SyncStatus.SENT
            all[relPath] = existing.copy(
                status = nextStatus,
                attempts = existing.attempts + 1,
                lastAttemptAt = java.time.LocalDateTime.now().toString(),
                lastError = "",
            )
            writeAllUnlocked(all)
        }
    }

    suspend fun markFailed(relPath: String, error: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val existing = all[relPath] ?: return@withLock
            // A delete that failed stays DELETED_PENDING (still a tombstone to deliver);
            // an upsert that failed goes to FAILED (still retryable, same as the other
            // ledgers — FAILED is a diagnostics status, not a dead-letter state).
            val nextStatus =
                if (existing.op == "delete") SyncStatus.DELETED_PENDING else SyncStatus.FAILED
            all[relPath] = existing.copy(
                status = nextStatus,
                attempts = existing.attempts + 1,
                lastAttemptAt = java.time.LocalDateTime.now().toString(),
                // Strip newlines — a raw HTTP error body / exception message can contain
                // them, and a literal '\n' inside the quoted YAML scalar writeAllUnlocked()
                // emits would corrupt the whole ledger on the next read.
                lastError = error.take(500).replace(Regex("[\\r\\n]+"), " "),
            )
            writeAllUnlocked(all)
        }
    }

    /**
     * Called after a restore ([RestoreApi]): the file at [relPath] now holds exactly the
     * server's bytes, so record it as already-SENT with [serverHash] to stop the next push
     * re-sending what was just pulled down.
     */
    suspend fun markRestored(relPath: String, serverHash: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            all[relPath] = RepoLedgerEntry(
                relPath = relPath,
                op = "upsert",
                status = SyncStatus.SENT,
                attempts = 0,
                lastAttemptAt = java.time.LocalDateTime.now().toString(),
                contentHash = serverHash,
            )
            writeAllUnlocked(all)
        }
    }
}
