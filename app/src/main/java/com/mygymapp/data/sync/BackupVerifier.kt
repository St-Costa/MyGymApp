package com.mygymapp.data.sync

import com.mygymapp.BuildConfig
import com.mygymapp.data.parser.MarkdownParser
import com.mygymapp.data.repository.FileManager
import com.mygymapp.data.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One changed exercise/routine, rendered git-diffstat-style: `<displayName>  -+++`. */
data class BackupDiffEntry(
    /** Human name from the file's `name:` frontmatter (falls back to the de-slugged filename). */
    val displayName: String,
    /** Lines added vs. the version the server had before this push (all lines, for a brand-new file). */
    val added: Int,
    /** Lines removed vs. the previous server version (0 for a brand-new file). */
    val removed: Int,
)

/** Which section a [BackupError] is rendered under. */
enum class BackupErrorCategory { EXERCISE, ROUTINE, SESSION }

/**
 * One file the server rejected or that failed to transfer. Shown as a red row *inside its
 * own section* (Esercizi / Routine / Sessioni), same shape as a diff row but with the raw
 * filename: `push-rt-b997ec72.md → HTTP 422: contentHash mismatch`.
 */
data class BackupError(
    val category: BackupErrorCategory,
    /** Full filename, e.g. `calf-raise-ex-7ca58254.md` — errors use the raw name, not `name:`. */
    val fileName: String,
    /** HTTP code + any server message, or the exception summary. */
    val detail: String,
)

/**
 * Structured result of a full-store backup round-trip (docs/BACKUP.md §3.7).
 *
 * [exercises] / [routines]: files this run actually pushed, each with its line diffstat.
 * [exercisesMatching]/[exercisesLocal] etc. drive the `(X/Y allineate)` count in each
 * section header — Y is how many such files exist on the phone, X how many are byte-for-
 * byte on the server after this run. Sessions (`history/**/*.md`) are count-only.
 * [sessionsChanged] names the differing sessions (backfill case).
 * [errors]: each rendered under its own section; all also written to `gymdata/logs/app.log`.
 */
data class BackupVerifyReport(
    val exercises: List<BackupDiffEntry> = emptyList(),
    val routines: List<BackupDiffEntry> = emptyList(),
    val exercisesLocal: Int = 0,
    val exercisesMatching: Int = 0,
    val routinesLocal: Int = 0,
    val routinesMatching: Int = 0,
    val sessionsLocal: Int = 0,
    val sessionsMatching: Int = 0,
    val sessionsChanged: List<String> = emptyList(),
    val errors: List<BackupError> = emptyList(),
    /** Wall time of the whole round-trip (manifest → pushes → read-back). */
    val elapsedMs: Long = 0,
    /** Total bytes actually uploaded this run (sum of the pushed files' sizes). */
    val bytesUploaded: Long = 0,
) {
    fun errorsOf(cat: BackupErrorCategory) = errors.filter { it.category == cat }

    val allGood: Boolean
        get() = errors.isEmpty() &&
            exercisesMatching == exercisesLocal &&
            routinesMatching == routinesLocal &&
            sessionsMatching == sessionsLocal
}

sealed interface BackupVerifyOutcome {
    data class HardFail(val reason: String) : BackupVerifyOutcome
    data class Done(val report: BackupVerifyReport) : BackupVerifyOutcome
}

/**
 * The full-store backup round-trip, shared by Options ("Verifica backup sul server") and
 * the end-of-session summary. See docs/BACKUP.md §3.7:
 *
 * 1. **Diff** — `GET /v1/manifest`; compare `sha256(local)` for every exercise/routine
 *    `.md` on disk against the server's hash.
 * 2. **Push** — for each differing file: fetch the server's *previous* copy
 *    (`GET /v1/file`) for a line diffstat, then `POST /v1/repo` `op:"upsert"` directly
 *    (awaited), recording the server's per-file answer.
 * 3. **Read-back** — re-fetch the manifest, then `GET /v1/file` for each exercise/routine
 *    and compare bytes. Sessions (`history/**/*.md`) are count-only — matched by manifest
 *    hash, no per-file read-back.
 *
 * No delete, nothing synthetic.
 */
@Singleton
class BackupVerifier @Inject constructor(
    private val fileManager: FileManager,
    private val repoLedgerRepository: RepoLedgerRepository,
    private val repoSyncApi: RepoSyncApi,
    private val restoreApi: RestoreApi,
    private val config: SyncConfigRepository,
    private val appLogger: AppLogger,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {
    private companion object {
        const val TAG = "BackupVerifier"
    }

    suspend fun run(): BackupVerifyOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        if (!config.isConfigured()) {
            return@withContext BackupVerifyOutcome.HardFail("Server sync non configurato")
        }
        val serverUrl = config.serverUrl()
        val token = config.bearerToken()
        val appVersion = BuildConfig.VERSION_NAME

        val exerciseFiles = fileManager.getDir("exercises").listFiles { f -> f.extension == "md" }?.toList().orEmpty()
        val routineFiles = fileManager.getDir("routines").listFiles { f -> f.extension == "md" }?.toList().orEmpty()
        val sessionFiles = collectSessionFiles()
        val repoFiles = (exerciseFiles + routineFiles).filter { it.isFile }
        if (repoFiles.isEmpty() && sessionFiles.isEmpty()) {
            return@withContext BackupVerifyOutcome.HardFail("Niente da verificare.")
        }

        fun cat(f: File) = f.parentFile?.name ?: ""
        fun rel(f: File) = "${cat(f)}/${f.name}"
        fun errCat(f: File) =
            if (cat(f) == "exercises") BackupErrorCategory.EXERCISE else BackupErrorCategory.ROUTINE

        // 1. Diff against the current manifest.
        val manifest0 = when (val m = restoreApi.fetchManifest(serverUrl, token)) {
            is RestoreResult.Manifest -> m.entries.associate { it.relPath to it.contentHash }
            is RestoreResult.Failure -> return@withContext BackupVerifyOutcome.HardFail("Manifest non recuperato: ${m.reason}")
            is RestoreResult.FileBytes -> return@withContext BackupVerifyOutcome.HardFail("Risposta inattesa dal server (manifest).")
        }

        data class Local(val file: File, val bytes: ByteArray, val hash: String)
        val locals = repoFiles.map {
            val b = it.readBytes()
            Local(it, b, repoLedgerRepository.hashOf(b))
        }
        val toPush = locals.filter { manifest0[rel(it.file)] != it.hash }

        // 2. Push each diff directly. Before the POST, pull the server's previous copy for a
        //    line diffstat.
        val exEntries = mutableListOf<BackupDiffEntry>()
        val rtEntries = mutableListOf<BackupDiffEntry>()
        val errors = mutableListOf<BackupError>()
        val pushedRel = mutableSetOf<String>()
        var bytesUploaded = 0L
        val tmp = File.createTempFile("repo-verify", ".md", appContext.cacheDir)
        try {
            for (l in toPush) {
                val relPath = rel(l.file)
                val onServerBefore = manifest0.containsKey(relPath)
                val diffstat = if (onServerBefore) {
                    when (val old = restoreApi.fetchFile(serverUrl, token, relPath)) {
                        is RestoreResult.FileBytes -> lineDiffStat(old.bytes, l.bytes)
                        else -> lineDiffStat(ByteArray(0), l.bytes) // couldn't fetch old — treat as all-added
                    }
                } else {
                    lineDiffStat(ByteArray(0), l.bytes)
                }

                tmp.writeBytes(l.bytes)
                when (val r = repoSyncApi.postUpsert(serverUrl, token, relPath, l.hash, appVersion, tmp)) {
                    is SyncResult.Success -> {
                        pushedRel += relPath
                        bytesUploaded += l.bytes.size
                        val entry = BackupDiffEntry(displayName(l.file, l.bytes), diffstat.first, diffstat.second)
                        if (cat(l.file) == "exercises") exEntries += entry else rtEntries += entry
                    }
                    is SyncResult.Failure -> {
                        errors += BackupError(errCat(l.file), l.file.name, r.reason.take(160))
                        appLogger.w(TAG, "push failed ${l.file.name}: ${r.reason}")
                    }
                }
            }
        } finally {
            tmp.delete()
        }

        // Keep the local ledger in step with the server for files we just pushed OK.
        runCatching {
            for (l in toPush) if (rel(l.file) in pushedRel) repoLedgerRepository.markRestored(rel(l.file), l.hash)
        }

        // 3. Re-fetch the manifest, verify exercise/routine bytes, count sessions.
        val manifest1 = when (val m = restoreApi.fetchManifest(serverUrl, token)) {
            is RestoreResult.Manifest -> m.entries.associate { it.relPath to it.contentHash }
            is RestoreResult.Failure -> return@withContext BackupVerifyOutcome.HardFail(
                "Push completato ma manifest di verifica non recuperato: ${m.reason}"
            )
            is RestoreResult.FileBytes -> return@withContext BackupVerifyOutcome.HardFail("Risposta inattesa dal server (manifest).")
        }

        var exMatching = 0
        var rtMatching = 0
        for (l in locals) {
            val relPath = rel(l.file)
            val serverHash = manifest1[relPath]
            val ok = when {
                serverHash == null -> {
                    errors += BackupError(errCat(l.file), l.file.name, "assente dal manifest dopo il push"); false
                }
                serverHash != l.hash -> {
                    errors += BackupError(errCat(l.file), l.file.name, "hash sul server diverso da quello locale"); false
                }
                else -> {
                    val got = restoreApi.fetchFile(serverUrl, token, relPath)
                    if (got is RestoreResult.FileBytes && got.bytes.contentEquals(l.bytes)) true
                    else {
                        errors += BackupError(
                            errCat(l.file),
                            l.file.name,
                            (got as? RestoreResult.Failure)?.reason ?: "rilettura non identica",
                        )
                        false
                    }
                }
            }
            if (ok) { if (cat(l.file) == "exercises") exMatching++ else rtMatching++ }
        }

        // Sessions — count-only, matched by manifest hash.
        val sessionLocalByRel = sessionFiles.associate { f ->
            sessionRel(f) to repoLedgerRepository.hashOf(f.readBytes())
        }
        var sessionsMatching = 0
        val sessionsChanged = mutableListOf<String>()
        for ((relPath, localHash) in sessionLocalByRel) {
            if (manifest1[relPath] == localHash) sessionsMatching++
            else sessionsChanged += relPath.substringAfterLast('/').removeSuffix(".md")
        }

        val exLocal = locals.count { cat(it.file) == "exercises" }
        val rtLocal = locals.count { cat(it.file) == "routines" }
        val report = BackupVerifyReport(
            exercises = exEntries.sortedBy { it.displayName.lowercase() },
            routines = rtEntries.sortedBy { it.displayName.lowercase() },
            exercisesLocal = exLocal,
            exercisesMatching = exMatching,
            routinesLocal = rtLocal,
            routinesMatching = rtMatching,
            sessionsLocal = sessionFiles.size,
            sessionsMatching = sessionsMatching,
            sessionsChanged = sessionsChanged.sorted(),
            errors = errors,
            elapsedMs = System.currentTimeMillis() - startedAt,
            bytesUploaded = bytesUploaded,
        )
        if (errors.isNotEmpty()) {
            appLogger.w(TAG, "verify finished with ${errors.size} error(s): " +
                errors.joinToString("; ") { "${it.fileName} -> ${it.detail}" })
        } else {
            appLogger.i(TAG, "verify OK: ${exEntries.size} esercizi + ${rtEntries.size} routine inviati, " +
                "$sessionsMatching/${sessionFiles.size} sessioni allineate")
        }
        BackupVerifyOutcome.Done(report)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun collectSessionFiles(): List<File> {
        val history = File(fileManager.root, "history")
        if (!history.isDirectory) return emptyList()
        return history.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .filterNot { it.path.contains("/_") } // skip _stats, _idx, _gitgraph
            .toList()
    }

    /** relPath of a session as the server keys it: `YYYY/MM/<file>.md`. */
    private fun sessionRel(f: File): String {
        val month = f.parentFile?.name ?: ""
        val year = f.parentFile?.parentFile?.name ?: ""
        return "$year/$month/${f.name}"
    }

    /** `name:` from the frontmatter, else the filename de-slugged (id stripped). */
    private fun displayName(file: File, bytes: ByteArray): String {
        val fromYaml = runCatching {
            MarkdownParser.parse(String(bytes)).frontmatter["name"]?.toString()?.trim()
        }.getOrNull()
        if (!fromYaml.isNullOrBlank()) return fromYaml
        // "calf-raise-ex-7ca58254.md" -> "calf raise"
        return file.name
            .removeSuffix(".md")
            .replace(Regex("""-(ex|rt)-[0-9a-f]{8}$"""), "")
            .replace('-', ' ')
            .trim()
            .ifBlank { file.name.removeSuffix(".md") }
    }

}

/**
 * git-style line diffstat between [old] and [new] bytes: a line present in old but not new
 * (by count) is a removal, present in new but not old is an addition. Multiset difference,
 * not an LCS — good enough for a small YAML file and cheap. A brand-new file (`old` empty)
 * counts every line as added, at least 1.
 *
 * Top-level + `internal` so it's unit-testable without constructing a [BackupVerifier].
 */
internal fun lineDiffStat(old: ByteArray, new: ByteArray): Pair<Int, Int> {
    if (old.isEmpty()) return String(new).count { it == '\n' }.coerceAtLeast(1) to 0
    val oldCounts = HashMap<String, Int>()
    for (l in String(old).split('\n')) oldCounts[l] = (oldCounts[l] ?: 0) + 1
    val newCounts = HashMap<String, Int>()
    for (l in String(new).split('\n')) newCounts[l] = (newCounts[l] ?: 0) + 1
    var added = 0
    var removed = 0
    for ((line, n) in newCounts) added += (n - (oldCounts[line] ?: 0)).coerceAtLeast(0)
    for ((line, n) in oldCounts) removed += (n - (newCounts[line] ?: 0)).coerceAtLeast(0)
    return added to removed
}
