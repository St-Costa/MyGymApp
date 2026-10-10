package com.mygymapp.data.sync

import com.mygymapp.data.repository.FileManager
import com.mygymapp.data.util.AtomicFiles
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
 * Local durable ledger for readiness event sync, deliberately separate from
 * [SyncLedgerRepository] (sessions) rather than generalized into one — see docs/SYNC.md
 * "Extensibility": the session sync path is already verified end-to-end in production, so
 * this mirrors its pattern (`gymdata/_sync/readiness_state.yml`) instead of risking a
 * refactor of code that already works. Field-for-field the same shape.
 */
@Singleton
class ReadinessLedgerRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val mutex = Mutex()
    private val loadSettings = LoadSettings.builder().build()

    private fun ledgerFile(): File = File(fileManager.getDir("_sync"), "readiness_state.yml")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hashOf(file: File): String = "sha256:${sha256(file.readBytes())}"

    private fun readAllUnlocked(): MutableMap<String, SyncLedgerEntry> {
        val file = ledgerFile()
        if (!file.exists()) return mutableMapOf()
        val raw = try {
            file.readText()
        } catch (_: Exception) {
            return mutableMapOf()
        }
        if (raw.isBlank()) return mutableMapOf()
        val root = try {
            Load(loadSettings).loadFromString(raw)
        } catch (_: Exception) {
            quarantineCorruptFile(file)
            null
        }
        val eventsMap = (root as? Map<*, Any?>)?.get("events") as? Map<*, Any?>
        if (eventsMap == null) {
            if (root != null) quarantineCorruptFile(file)
            return mutableMapOf()
        }

        val result = LinkedHashMap<String, SyncLedgerEntry>()
        for ((rawKey, raw) in eventsMap) {
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

    suspend fun getAll(): List<SyncLedgerEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { readAllUnlocked().values.toList() }
    }

    suspend fun getPending(): List<SyncLedgerEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readAllUnlocked().values.filter {
                it.status == SyncStatus.PENDING || it.status == SyncStatus.FAILED
            }
        }
    }

    private fun writeAllUnlocked(entries: Map<String, SyncLedgerEntry>) {
        val sb = StringBuilder()
        sb.appendLine("events:")
        for ((id, entry) in entries) {
            // Quoted for the same reason as SyncLedgerRepository: an all-digit id would
            // otherwise round-trip through YAML as an Integer key, not a String.
            sb.appendLine("  \"$id\":")
            sb.appendLine("    relPath: \"${entry.relPath}\"")
            sb.appendLine("    status: ${entry.status.name}")
            sb.appendLine("    attempts: ${entry.attempts}")
            sb.appendLine("    lastAttemptAt: \"${entry.lastAttemptAt}\"")
            sb.appendLine("    lastError: \"${entry.lastError.replace("\"", "'")}\"")
            sb.appendLine("    contentHash: \"${entry.contentHash}\"")
        }
        AtomicFiles.writeTextAtomically(ledgerFile(), sb.toString())
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

    /**
     * Dead-letter for entries whose source file is gone for good — same contract as
     * [SyncLedgerRepository.markDead]: EXPIRED, out of `getPending()`, reason recorded.
     */
    suspend fun markDead(id: String, reason: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val existing = all[id] ?: return@withLock
            all[id] = existing.copy(
                status = SyncStatus.EXPIRED,
                attempts = existing.attempts + 1,
                lastAttemptAt = java.time.LocalDateTime.now().toString(),
                lastError = reason.take(500).replace(Regex("[\\r\\n]+"), " "),
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
                // See SyncLedgerRepository.markFailed for why newlines are stripped: a raw
                // HTTP error body/exception message can contain them, and a literal '\n'
                // inside the quoted YAML scalar would corrupt the whole ledger on next read.
                lastError = error.take(500).replace(Regex("[\\r\\n]+"), " "),
            )
            writeAllUnlocked(all)
        }
    }
}
