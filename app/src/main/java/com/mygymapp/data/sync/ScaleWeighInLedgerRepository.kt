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
 * Local durable ledger for scale weigh-in sync — the third independent record type
 * alongside sessions ([SyncLedgerRepository]) and readiness ([ReadinessLedgerRepository]).
 * Same deliberate non-generalization reasoning as readiness (docs/SYNC.md
 * "Extensibility"): a dedicated small class per record type instead of touching what's
 * already verified in production.
 *
 * One difference from the other two: a weigh-in's ID is an ISO date string (`2026-08-05`),
 * not an 8-hex UUID — see [com.mygymapp.data.repository.ScaleHistoryRepository], "one
 * weigh-in per calendar day, overwrite on re-save same day." That's still fine as a YAML
 * map key here (quoted the same defensive way as the other two ledgers) and as the
 * idempotency key server-side — a same-day re-weigh naturally produces a content-hash
 * update to the same id, handled by the existing "different hash for known id = update"
 * rule (docs/SYNC.md §2.2 step 5), not a new row.
 */
@Singleton
class ScaleWeighInLedgerRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val mutex = Mutex()
    private val loadSettings = LoadSettings.builder().build()

    private fun ledgerFile(): File = File(fileManager.getDir("_sync"), "scale_state.yml")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hashOf(file: File): String = "sha256:${sha256(file.readBytes())}"

    private fun readAllUnlocked(): MutableMap<String, SyncLedgerEntry> {
        val file = ledgerFile()
        if (!file.exists()) return mutableMapOf()
        val root = try {
            Load(loadSettings).loadFromString(file.readText())
        } catch (_: Exception) {
            null
        }
        val entriesMap = (root as? Map<*, Any?>)?.get("weighIns") as? Map<*, Any?>
            ?: return mutableMapOf()

        val result = LinkedHashMap<String, SyncLedgerEntry>()
        for ((rawKey, raw) in entriesMap) {
            val id = rawKey?.toString() ?: continue
            @Suppress("UNCHECKED_CAST")
            val fields = raw as? Map<String, Any?> ?: continue
            result[id] = SyncLedgerEntry(
                sessionId = id,
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

    suspend fun getPending(): List<SyncLedgerEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { readAllUnlocked().values.filter { it.status != SyncStatus.SENT } }
    }

    private fun writeAllUnlocked(entries: Map<String, SyncLedgerEntry>) {
        val sb = StringBuilder()
        sb.appendLine("weighIns:")
        for ((id, entry) in entries) {
            // Quoted defensively like the other ledgers, though a date-string id
            // ("2026-08-05") is not YAML-integer-ambiguous the way an all-digit 8-hex id
            // is — kept for consistency and because it costs nothing.
            sb.appendLine("  \"$id\":")
            sb.appendLine("    relPath: \"${entry.relPath}\"")
            sb.appendLine("    status: ${entry.status.name}")
            sb.appendLine("    attempts: ${entry.attempts}")
            sb.appendLine("    lastAttemptAt: \"${entry.lastAttemptAt}\"")
            sb.appendLine("    lastError: \"${entry.lastError.replace("\"", "'")}\"")
            sb.appendLine("    contentHash: \"${entry.contentHash}\"")
        }
        ledgerFile().writeText(sb.toString())
    }

    suspend fun enqueue(id: String, relPath: String, file: File) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            all[id] = SyncLedgerEntry(
                sessionId = id,
                relPath = relPath,
                status = SyncStatus.PENDING,
                attempts = 0,
                contentHash = hashOf(file),
            )
            writeAllUnlocked(all)
        }
    }

    suspend fun markSent(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val existing = all[id] ?: return@withLock
            all[id] = existing.copy(
                status = SyncStatus.SENT,
                attempts = existing.attempts + 1,
                lastAttemptAt = java.time.LocalDateTime.now().toString(),
                lastError = "",
            )
            writeAllUnlocked(all)
        }
    }

    suspend fun markFailed(id: String, error: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val existing = all[id] ?: return@withLock
            all[id] = existing.copy(
                status = SyncStatus.FAILED,
                attempts = existing.attempts + 1,
                lastAttemptAt = java.time.LocalDateTime.now().toString(),
                lastError = error.take(500),
            )
            writeAllUnlocked(all)
        }
    }
}
