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
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local durable ledger for raw-ECG sync — the fourth independent record type alongside
 * sessions ([SyncLedgerRepository]), readiness ([ReadinessLedgerRepository]), and scale
 * weigh-ins ([ScaleWeighInLedgerRepository]). Same deliberate non-generalization reasoning
 * (docs/SYNC.md "Extensibility"): a dedicated small class instead of touching what's
 * already verified in production.
 *
 * Unlike the other three, the source file (`ecg/{sessionId}.ecg`) is **ephemeral by
 * design** — it only exists until this ledger confirms `SENT`, at which point
 * [EcgSyncWorker] deletes it. That makes this ledger the only one that needs an [enqueuedAt]
 * timestamp and an expiry path ([expireStale]): if the server stays unreachable long enough,
 * the phone must stop accumulating raw ECG files indefinitely. See docs/SYNC.md "Fourth
 * record type: raw ECG" for the full reasoning.
 *
 * [contentHash] is meant to reflect the **gzip-compressed** bytes actually transmitted
 * ([EcgSyncWorker] compresses before hashing) — the server verifies against that same
 * compressed representation before decompressing. [enqueue] is called before compression
 * happens (from [com.mygymapp.ui.screen.activeroutine.ActiveRoutineViewModel], which only
 * has the raw file), so the hash recorded at enqueue time is provisional; [EcgSyncWorker]
 * recomputes it over the compressed bytes it actually sends and that's the value checked
 * against the server's response — the enqueue-time hash is never sent over the wire.
 */
@Singleton
class EcgSyncLedgerRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val mutex = Mutex()
    private val loadSettings = LoadSettings.builder().build()

    private fun ledgerFile(): File = File(fileManager.getDir("_sync"), "ecg_state.yml")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hashOf(bytes: ByteArray): String = "sha256:${sha256(bytes)}"

    private fun readAllUnlocked(): MutableMap<String, EcgSyncLedgerEntry> {
        val file = ledgerFile()
        if (!file.exists()) return mutableMapOf()
        val root = try {
            Load(loadSettings).loadFromString(file.readText())
        } catch (_: Exception) {
            null
        }
        val entriesMap = (root as? Map<*, Any?>)?.get("ecg") as? Map<*, Any?>
            ?: return mutableMapOf()

        val result = LinkedHashMap<String, EcgSyncLedgerEntry>()
        for ((rawKey, raw) in entriesMap) {
            val id = rawKey?.toString() ?: continue
            @Suppress("UNCHECKED_CAST")
            val fields = raw as? Map<String, Any?> ?: continue
            result[id] = EcgSyncLedgerEntry(
                sessionId = id,
                relPath = fields["relPath"] as? String ?: "",
                status = (fields["status"] as? String)?.let {
                    runCatching { SyncStatus.valueOf(it) }.getOrNull()
                } ?: SyncStatus.PENDING,
                attempts = (fields["attempts"] as? Number)?.toInt() ?: 0,
                lastAttemptAt = fields["lastAttemptAt"] as? String ?: "",
                lastError = fields["lastError"] as? String ?: "",
                contentHash = fields["contentHash"] as? String ?: "",
                enqueuedAt = fields["enqueuedAt"] as? String ?: "",
            )
        }
        return result
    }

    suspend fun getPending(): List<EcgSyncLedgerEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readAllUnlocked().values.filter {
                it.status == SyncStatus.PENDING || it.status == SyncStatus.FAILED
            }
        }
    }

    private fun writeAllUnlocked(entries: Map<String, EcgSyncLedgerEntry>) {
        val sb = StringBuilder()
        sb.appendLine("ecg:")
        for ((id, entry) in entries) {
            sb.appendLine("  \"$id\":")
            sb.appendLine("    relPath: \"${entry.relPath}\"")
            sb.appendLine("    status: ${entry.status.name}")
            sb.appendLine("    attempts: ${entry.attempts}")
            sb.appendLine("    lastAttemptAt: \"${entry.lastAttemptAt}\"")
            sb.appendLine("    lastError: \"${entry.lastError.replace("\"", "'")}\"")
            sb.appendLine("    contentHash: \"${entry.contentHash}\"")
            sb.appendLine("    enqueuedAt: \"${entry.enqueuedAt}\"")
        }
        ledgerFile().writeText(sb.toString())
    }

    /** [compressedBytes] are hashed and recorded — the same bytes [EcgSyncApi] will POST. */
    suspend fun enqueue(id: String, relPath: String, compressedBytes: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            all[id] = EcgSyncLedgerEntry(
                sessionId = id,
                relPath = relPath,
                status = SyncStatus.PENDING,
                attempts = 0,
                contentHash = hashOf(compressedBytes),
                enqueuedAt = LocalDateTime.now().toString(),
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
                lastAttemptAt = LocalDateTime.now().toString(),
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
                lastAttemptAt = LocalDateTime.now().toString(),
                lastError = error.take(500),
            )
            writeAllUnlocked(all)
        }
    }

    /**
     * Marks any `PENDING`/`FAILED` entry older than [maxAgeDays] as `EXPIRED` and returns
     * the affected session IDs, so the caller ([EcgSyncWorker]) can delete their `.ecg`
     * files. Bounds worst-case local storage growth during an extended server outage —
     * the server has been observed offline for multi-day stretches in practice (see
     * docs/SYNC.md §3.2). Unlike the other three pipelines' "never give up" retry
     * philosophy, this one *must* give up eventually because the source file is ephemeral
     * and would otherwise accumulate without limit.
     */
    suspend fun expireStale(maxAgeDays: Int = 30): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAllUnlocked()
            val now = LocalDateTime.now()
            val expired = mutableListOf<String>()
            for ((id, entry) in all) {
                if (entry.status != SyncStatus.PENDING && entry.status != SyncStatus.FAILED) continue
                val enqueuedAt = runCatching { LocalDateTime.parse(entry.enqueuedAt) }.getOrNull() ?: continue
                if (Duration.between(enqueuedAt, now).toDays() >= maxAgeDays) {
                    all[id] = entry.copy(status = SyncStatus.EXPIRED, lastAttemptAt = now.toString())
                    expired.add(id)
                }
            }
            if (expired.isNotEmpty()) writeAllUnlocked(all)
            expired
        }
    }
}
