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
        val sessionsMap = (root as? Map<*, Any?>)?.get("sessions") as? Map<*, Any?>
            ?: return mutableMapOf()

        val result = LinkedHashMap<String, SyncLedgerEntry>()
        for ((rawKey, raw) in sessionsMap) {
            // Keys are always written quoted (see writeAllUnlocked), but a session ID
            // that happens to be all-digit (e.g. "82676173") is parsed back as a YAML
            // Integer key by any file written before that fix — toString() it rather
            // than crashing on `as String`.
            val sessionId = rawKey?.toString() ?: continue
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
                bytesSent = (fields["bytesSent"] as? Number)?.toLong() ?: 0L,
                durationMs = (fields["durationMs"] as? Number)?.toLong() ?: 0L,
                serverStatus = fields["serverStatus"] as? String ?: "",
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
            // Quoted: an all-digit sessionId (e.g. "82676173") is otherwise parsed back
            // as a YAML Integer key instead of a String, crashing readAllUnlocked's
            // `for ((sessionId, raw) in sessionsMap)` with a ClassCastException the next
            // time the ledger is read (hit in practice via "Resync all" — enough sessions
            // pushes the odds of an all-digit 8-hex ID up fast).
            sb.appendLine("  \"$sessionId\":")
            sb.appendLine("    relPath: \"${entry.relPath}\"")
            sb.appendLine("    status: ${entry.status.name}")
            sb.appendLine("    attempts: ${entry.attempts}")
            sb.appendLine("    lastAttemptAt: \"${entry.lastAttemptAt}\"")
            sb.appendLine("    lastError: \"${entry.lastError.replace("\"", "'")}\"")
            sb.appendLine("    contentHash: \"${entry.contentHash}\"")
            sb.appendLine("    bytesSent: ${entry.bytesSent}")
            sb.appendLine("    durationMs: ${entry.durationMs}")
            sb.appendLine("    serverStatus: \"${entry.serverStatus}\"")
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

    suspend fun markSent(
        sessionId: String,
        bytesSent: Long = 0,
        durationMs: Long = 0,
        serverStatus: String = "",
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val existing = all[sessionId] ?: return@withLock
            all[sessionId] = existing.copy(
                status = SyncStatus.SENT,
                attempts = existing.attempts + 1,
                lastAttemptAt = java.time.LocalDateTime.now().toString(),
                lastError = "",
                bytesSent = bytesSent,
                durationMs = durationMs,
                serverStatus = serverStatus,
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
                // error can be a raw HTTP error body or exception message — strip
                // newlines so it can't break the single-line quoted YAML scalar
                // writeAllUnlocked() emits (a literal '\n' inside the quotes would
                // corrupt the whole ledger file on the next read, not just this entry).
                lastError = error.take(500).replace(Regex("[\\r\\n]+"), " "),
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
