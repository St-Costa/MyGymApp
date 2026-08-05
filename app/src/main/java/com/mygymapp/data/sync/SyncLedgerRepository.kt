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
 * Local durable ledger of which workout sessions have been synced to the self-hosted
 * server: `gymdata/_sync/state.yml`, one entry per session ID. See `docs/SYNC.md` §1.2.
 *
 * Deliberately file-based (like every other repository here) rather than a database —
 * this is a handful of rows for a personal project. Read/write follows the same
 * hand-rolled YAML approach as [com.mygymapp.data.parser.MarkdownParser] (snakeyaml-engine
 * `Load` for reading, manual `StringBuilder` for writing) since the shape here is a flat
 * map, not the frontmatter+body document `MarkdownParser` is built for.
 *
 * Not the source of truth for session *content* — [com.mygymapp.data.repository.WorkoutRepository]
 * owns that. This only tracks delivery status per session ID.
 */
@Singleton
class SyncLedgerRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val mutex = Mutex()
    private val loadSettings = LoadSettings.builder().build()

    private fun ledgerFile(): File =
        File(fileManager.getDir("_sync"), "state.yml")

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Sha256 of [file]'s current bytes, prefixed `sha256:` to match the wire envelope format. */
    fun hashOf(file: File): String = "sha256:${sha256(file.readBytes())}"

    // ─── Read ─────────────────────────────────────────────────────────────────

    private fun readAllUnlocked(): MutableMap<String, SyncLedgerEntry> {
        val file = ledgerFile()
        if (!file.exists()) return mutableMapOf()
        val root = try {
            Load(loadSettings).loadFromString(file.readText())
        } catch (_: Exception) {
            null
        }
        @Suppress("UNCHECKED_CAST")
        val sessionsMap = (root as? Map<String, Any?>)?.get("sessions") as? Map<String, Any?>
            ?: return mutableMapOf()

        val result = LinkedHashMap<String, SyncLedgerEntry>()
        for ((sessionId, raw) in sessionsMap) {
            @Suppress("UNCHECKED_CAST")
            val fields = raw as? Map<String, Any?> ?: continue
            result[sessionId] = SyncLedgerEntry(
                sessionId = sessionId,
                relPath = fields["relPath"] as? String ?: "",
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

    suspend fun getAll(): List<SyncLedgerEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { readAllUnlocked().values.toList() }
    }

    suspend fun getPending(): List<SyncLedgerEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readAllUnlocked().values.filter { it.status != SyncStatus.SENT }
        }
    }

    // ─── Write ────────────────────────────────────────────────────────────────

    private fun writeAllUnlocked(entries: Map<String, SyncLedgerEntry>) {
        val sb = StringBuilder()
        sb.appendLine("sessions:")
        for ((sessionId, entry) in entries) {
            sb.appendLine("  $sessionId:")
            sb.appendLine("    relPath: \"${entry.relPath}\"")
            sb.appendLine("    status: ${entry.status.name}")
            sb.appendLine("    attempts: ${entry.attempts}")
            sb.appendLine("    lastAttemptAt: \"${entry.lastAttemptAt}\"")
            sb.appendLine("    lastError: \"${entry.lastError.replace("\"", "'")}\"")
            sb.appendLine("    contentHash: \"${entry.contentHash}\"")
        }
        ledgerFile().writeText(sb.toString())
    }

    /**
     * Enqueues [sessionId] as PENDING with the current content hash of [file]. Called
     * right after a session is durably saved (`ActiveRoutineViewModel.registerRoutine()`)
     * and by "Resync all". Overwrites any prior entry for this ID — a fresh enqueue always
     * wins over whatever delivery state existed before.
     */
    suspend fun enqueue(sessionId: String, relPath: String, file: File) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            all[sessionId] = SyncLedgerEntry(
                sessionId = sessionId,
                relPath = relPath,
                status = SyncStatus.PENDING,
                attempts = 0,
                contentHash = hashOf(file),
            )
            writeAllUnlocked(all)
        }
    }

    /**
     * If [sessionId] is known and SENT but [file]'s current content hash differs from what
     * was last sent, flips it back to PENDING so the worker resends it. No-op if the
     * session isn't in the ledger yet (nothing to requeue) or the hash is unchanged.
     * Called from [com.mygymapp.data.repository.WorkoutRepository]'s rename-sync paths.
     */
    suspend fun requeueIfChanged(sessionId: String, file: File) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val existing = all[sessionId] ?: return@withLock
            val currentHash = hashOf(file)
            if (existing.contentHash != currentHash) {
                all[sessionId] = existing.copy(
                    status = SyncStatus.PENDING,
                    contentHash = currentHash,
                )
                writeAllUnlocked(all)
            }
        }
    }

    suspend fun markSent(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val existing = all[sessionId] ?: return@withLock
            all[sessionId] = existing.copy(
                status = SyncStatus.SENT,
                attempts = existing.attempts + 1,
                lastAttemptAt = java.time.LocalDateTime.now().toString(),
                lastError = "",
            )
            writeAllUnlocked(all)
        }
    }

    suspend fun markFailed(sessionId: String, error: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val existing = all[sessionId] ?: return@withLock
            all[sessionId] = existing.copy(
                status = SyncStatus.FAILED,
                attempts = existing.attempts + 1,
                lastAttemptAt = java.time.LocalDateTime.now().toString(),
                lastError = error.take(500),
            )
            writeAllUnlocked(all)
        }
    }

    /**
     * "Resync all": marks every known entry PENDING again (does not forget delivery
     * history, just makes everything eligible for resend). The caller is responsible for
     * also enqueuing any session IDs not yet present in the ledger at all — see
     * `SyncWorker`'s full-history rescan, triggered from the Options screen.
     */
    suspend fun resetAllToPending() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val reset = all.mapValues { (_, e) -> e.copy(status = SyncStatus.PENDING) }
            writeAllUnlocked(reset)
        }
    }

    suspend fun pendingCount(): Int = getPending().size

    suspend fun lastSuccessfulSyncAt(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            readAllUnlocked().values
                .filter { it.status == SyncStatus.SENT && it.lastAttemptAt.isNotBlank() }
                .maxByOrNull { it.lastAttemptAt }
                ?.lastAttemptAt
        }
    }
}
