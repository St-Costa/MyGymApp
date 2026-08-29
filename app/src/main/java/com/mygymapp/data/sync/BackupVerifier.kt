package com.mygymapp.data.sync

import com.mygymapp.BuildConfig
import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Structured result of a full-store backup round-trip (docs/BACKUP.md §3.7), rendered as a
 * git-diff-style block. Shared by Options ("Verifica backup sul server"), the end-of-session
 * summary, and the debug preview screen.
 *
 * [exercisesPushed] / [routinesPushed]: files this run actually sent (`stored` — new or
 * changed), shown as green `+` lines. Files the server already had unchanged are counted in
 * [exercisesUnchanged] / [routinesUnchanged], not listed.
 *
 * [serverSummary]: one line paraphrasing what the server did across the whole run.
 */
data class BackupVerifyReport(
    val exercisesPushed: List<String> = emptyList(),
    val routinesPushed: List<String> = emptyList(),
    val exercisesUnchanged: Int = 0,
    val routinesUnchanged: Int = 0,
    val pushFailed: List<String> = emptyList(),
    val missingAfter: List<String> = emptyList(),
    val hashMismatch: List<String> = emptyList(),
    val readBackMismatch: List<String> = emptyList(),
    val verifiedIdentical: Int = 0,
    val serverSummary: String = "",
) {
    val allGood: Boolean
        get() = pushFailed.isEmpty() && missingAfter.isEmpty() &&
            hashMismatch.isEmpty() && readBackMismatch.isEmpty()
}

sealed interface BackupVerifyOutcome {
    /** A precondition or a hard network failure — nothing usable to render as a diff. */
    data class HardFail(val reason: String) : BackupVerifyOutcome
    data class Done(val report: BackupVerifyReport) : BackupVerifyOutcome
}

/**
 * The full-store backup round-trip, factored out of `OptionsViewModel` so the end-of-session
 * summary can run the exact same check. See docs/BACKUP.md §3.7:
 *
 * 1. **Diff** — `GET /v1/manifest`; compare `sha256(local)` for every exercise/routine
 *    `.md` on disk against the server's hash.
 * 2. **Push** — for every differing file, `POST /v1/repo` `op:"upsert"` **directly** (via
 *    [RepoSyncApi], awaited), recording the server's per-file answer.
 * 3. **Read-back** — re-fetch the manifest, then `GET /v1/file` for each file and compare
 *    bytes, exercising the real restore path.
 *
 * No delete, nothing synthetic — the user's real catalogue is meant to stay on the server.
 */
@Singleton
class BackupVerifier @Inject constructor(
    private val fileManager: FileManager,
    private val repoLedgerRepository: RepoLedgerRepository,
    private val repoSyncApi: RepoSyncApi,
    private val restoreApi: RestoreApi,
    private val config: SyncConfigRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {

    suspend fun run(): BackupVerifyOutcome = withContext(Dispatchers.IO) {
        if (!config.isConfigured()) {
            return@withContext BackupVerifyOutcome.HardFail("Server sync non configurato")
        }
        val serverUrl = config.serverUrl()
        val token = config.bearerToken()
        val appVersion = BuildConfig.VERSION_NAME

        val localFiles = (
            fileManager.getDir("exercises").listFiles { f -> f.extension == "md" }?.toList().orEmpty() +
                fileManager.getDir("routines").listFiles { f -> f.extension == "md" }?.toList().orEmpty()
            ).filter { it.isFile }
        if (localFiles.isEmpty()) {
            return@withContext BackupVerifyOutcome.HardFail("Nessun esercizio/routine da verificare.")
        }

        fun cat(f: File) = f.parentFile?.name ?: ""
        fun rel(f: File) = "${cat(f)}/${f.name}"

        // 1. Diff against the current manifest.
        val manifest0 = when (val m = restoreApi.fetchManifest(serverUrl, token)) {
            is RestoreResult.Manifest -> m.entries.associate { it.relPath to it.contentHash }
            is RestoreResult.Failure -> return@withContext BackupVerifyOutcome.HardFail("Manifest non recuperato: ${m.reason}")
            is RestoreResult.FileBytes -> return@withContext BackupVerifyOutcome.HardFail("Risposta inattesa dal server (manifest).")
        }

        data class Local(val file: File, val bytes: ByteArray, val hash: String)
        val locals = localFiles.map {
            val b = it.readBytes()
            Local(it, b, repoLedgerRepository.hashOf(b))
        }
        val toPush = locals.filter { manifest0[rel(it.file)] != it.hash }

        // 2. Push the diffs directly, one POST per file, recording the server's word.
        val exPushed = mutableListOf<String>()
        val rtPushed = mutableListOf<String>()
        val pushFailed = mutableListOf<String>()
        var storedCount = 0
        var duplicateCount = 0
        var pushErrCount = 0
        val tmp = File.createTempFile("repo-verify", ".md", appContext.cacheDir)
        try {
            for (l in toPush) {
                tmp.writeBytes(l.bytes)
                when (val r = repoSyncApi.postUpsert(serverUrl, token, rel(l.file), l.hash, appVersion, tmp)) {
                    is SyncResult.Success -> {
                        if (r.status == "duplicate") duplicateCount++ else storedCount++
                        if (cat(l.file) == "exercises") exPushed += l.file.name else rtPushed += l.file.name
                    }
                    is SyncResult.Failure -> {
                        pushErrCount++
                        pushFailed += "${l.file.name} — ${r.reason.take(80)}"
                    }
                }
            }
        } finally {
            tmp.delete()
        }

        // Keep the local ledger in step with the server for the files we just pushed OK,
        // so the next real sync doesn't re-send them. Best-effort.
        runCatching {
            for (l in toPush) {
                if (pushFailed.none { it.startsWith(l.file.name) }) {
                    repoLedgerRepository.markRestored(rel(l.file), l.hash)
                }
            }
        }

        // 3. Re-fetch the manifest and read every file back.
        val manifest1 = when (val m = restoreApi.fetchManifest(serverUrl, token)) {
            is RestoreResult.Manifest -> m.entries.associate { it.relPath to it.contentHash }
            is RestoreResult.Failure -> return@withContext BackupVerifyOutcome.HardFail(
                "Push completato ($storedCount inviati) ma manifest di verifica non recuperato: ${m.reason}"
            )
            is RestoreResult.FileBytes -> return@withContext BackupVerifyOutcome.HardFail("Risposta inattesa dal server (manifest).")
        }

        val missingAfter = mutableListOf<String>()
        val hashMismatch = mutableListOf<String>()
        val readBackMismatch = mutableListOf<String>()
        var verifiedIdentical = 0
        for (l in locals) {
            val serverHash = manifest1[rel(l.file)]
            when {
                serverHash == null -> missingAfter += l.file.name
                serverHash != l.hash -> hashMismatch += l.file.name
                else -> {
                    val got = restoreApi.fetchFile(serverUrl, token, rel(l.file))
                    if (got is RestoreResult.FileBytes && got.bytes.contentEquals(l.bytes)) verifiedIdentical++
                    else readBackMismatch += l.file.name
                }
            }
        }

        val serverSummary = buildString {
            append("$storedCount file accettati (stored)")
            if (duplicateCount > 0) append(", $duplicateCount già presenti (duplicate)")
            if (pushErrCount > 0) append(", $pushErrCount rifiutati/non inviati")
            append(". Manifest: ${manifest1.count { it.key.startsWith("exercises/") || it.key.startsWith("routines/") }} file schede/routine sul server")
            append(", $verifiedIdentical riletti identici")
            if (hashMismatch.isNotEmpty()) append(", ${hashMismatch.size} con hash diverso")
            if (missingAfter.isNotEmpty()) append(", ${missingAfter.size} ancora mancanti")
            append(".")
        }

        BackupVerifyOutcome.Done(
            BackupVerifyReport(
                exercisesPushed = exPushed.sorted(),
                routinesPushed = rtPushed.sorted(),
                exercisesUnchanged = locals.count { cat(it.file) == "exercises" } - exPushed.size,
                routinesUnchanged = locals.count { cat(it.file) == "routines" } - rtPushed.size,
                pushFailed = pushFailed,
                missingAfter = missingAfter,
                hashMismatch = hashMismatch,
                readBackMismatch = readBackMismatch,
                verifiedIdentical = verifiedIdentical,
                serverSummary = serverSummary,
            )
        )
    }
}
