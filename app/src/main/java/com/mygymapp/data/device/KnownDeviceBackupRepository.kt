package com.mygymapp.data.device

import android.content.Context
import com.mygymapp.data.parser.DeviceBackupIds
import com.mygymapp.data.parser.DeviceBackupParser
import com.mygymapp.data.repository.FileManager
import com.mygymapp.data.sync.RepoLedgerRepository
import com.mygymapp.data.sync.RepoSyncWorker
import com.mygymapp.data.sync.SyncConfigRepository
import com.mygymapp.data.util.AppLogger
import com.mygymapp.data.util.AtomicFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * File-backed mirror of the paired-device IDs (`exercises/_devices.md`,
 * [DEVICES_REL_PATH]) — the reinstall-survival story for the Polar H10 and the VitaFit
 * scale pairing (see `docs/BACKUP.md` §6, `docs/STORAGE.md`).
 *
 * `KnownPolarDeviceRepository` / `KnownScaleRepository` stay the source of truth at
 * runtime (fast `SharedPreferences` reads on the BLE scan path); every `set`/`forget`
 * they receive is mirrored here fire-and-forget, and every `get` with empty prefs
 * self-heals from this file. The file itself rides the tar backup and the server
 * full-store backup like any other repo file (`ExerciseRepository` skips `_`-prefixed
 * files, the server SQL view ignores them, `RestorePathPolicy` already allows the
 * exercises path), so a reinstall + "Ripristina" brings the pairings back with no
 * re-pairing — only the server URL/token still needs re-entering by hand (Keystore,
 * never backed up).
 *
 * Neither mirrored value is a secret (Polar ID printed on the sensor, MAC visible in
 * any BLE scan), so unlike the sync bearer they MAY be stored on the server.
 */
@Singleton
class KnownDeviceBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileManager: FileManager,
    private val repoLedgerRepository: RepoLedgerRepository,
    private val syncConfigRepository: SyncConfigRepository,
    private val appLogger: AppLogger,
) {
    companion object {
        const val DEVICES_REL_PATH = "exercises/_devices.md"
        private const val TAG = "DeviceBackup"
    }

    // Fire-and-forget only for the mirror writes out of the synchronous
    // SharedPreferences path (the file write itself is blocking and tiny; the ledger
    // queue can't run there because it's suspend). Reads stay synchronous — callers
    // read this file at most once per reinstall (prefs heal), never at 1 Hz.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun devicesFile(): File = File(fileManager.root, DEVICES_REL_PATH)

    /** Blocking read of the mirror file; null when absent/unparsable/empty. */
    fun snapshot(): DeviceBackupIds? {
        val file = devicesFile()
        if (!file.exists()) return null
        return try {
            DeviceBackupParser.fromMarkdown(file.readText())
        } catch (e: Exception) {
            appLogger.e(TAG, "Unparsable $DEVICES_REL_PATH, ignoring", e)
            null
        }
    }

    /** Fire-and-forget mirror of a Polar pairing (null = unpaired, clears the key). */
    fun savePolarSoon(deviceId: String?) = ioScope.launch {
        runCatching { write((snapshot() ?: DeviceBackupIds()).copy(polarDeviceId = deviceId)) }
            .onFailure { appLogger.e(TAG, "Mirror write failed (polar)", it) }
    }

    /** Fire-and-forget mirror of a scale pairing (null = unpaired, clears the key). */
    fun saveScaleSoon(address: String?) = ioScope.launch {
        runCatching { write((snapshot() ?: DeviceBackupIds()).copy(scaleAddress = address)) }
            .onFailure { appLogger.e(TAG, "Mirror write failed (scale)", it) }
    }

    /**
     * Re-reads the file (e.g. after "Ripristina" wrote it bypassing this repo).
     * No in-memory cache to refresh — the file IS the store.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        snapshot() // touch + log parity with UserProfileRepository.refresh(); no-op otherwise
    }

    private suspend fun write(ids: DeviceBackupIds) {
        val file = devicesFile()
        if (ids.polarDeviceId == null && ids.scaleAddress == null) {
            val lastHash = if (file.exists()) {
                runCatching { sha256(file.readBytes()) }.getOrNull()
            } else null
            if (file.exists()) file.delete()
            // Tombstone the server copy too so a deliberate "forget" propagates.
            if (lastHash != null) repoLedgerRepository.markDeleted(DEVICES_REL_PATH, lastHash)
            return
        }
        file.parentFile?.mkdirs()
        AtomicFiles.writeTextAtomically(file, DeviceBackupParser.toMarkdown(ids))
        queueForBackup()
    }

    private suspend fun queueForBackup() {
        val file = devicesFile()
        if (!file.exists()) return
        repoLedgerRepository.requeueIfChanged(DEVICES_REL_PATH, file.readBytes())
        if (syncConfigRepository.isEnabled() && syncConfigRepository.isConfigured()) {
            RepoSyncWorker.Scheduler.runExpedited(context)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        "sha256:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
}
