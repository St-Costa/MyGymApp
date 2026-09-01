package com.mygymapp.data.repository

import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseStats
import com.mygymapp.data.model.GitgraphHistory
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.parser.ExerciseStatsCalculator
import com.mygymapp.data.parser.ExerciseStatsParser
import com.mygymapp.data.parser.GitgraphHistoryCalculator
import com.mygymapp.data.parser.GitgraphHistoryParser
import com.mygymapp.data.parser.WorkoutParser
import com.mygymapp.data.sync.SyncLedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages workout session files in `history/YYYY/MM/`.
 *
 * ## File naming
 * `YYYY-MM-DD_{routineId}_{sessionId}.md`
 *
 * The routineId (e.g. `rt-a1b2c3d4`) is embedded directly in the filename, so all sessions
 * for a given routine can be found with a cheap filename filter — no YAML parsing needed.
 *
 * ## Exercise index
 * `history/_idx/{exerciseId}.idx` — newline-separated paths relative to `history/`,
 * one entry per session that contains that exercise.  Maintained automatically on every
 * save/delete so exercise-based queries never need a full directory scan.
 *
 * ## Exercise stats sidecar
 * `history/_stats/{exerciseId}.yaml` — a per-exercise materialized view (most recent real
 * sets + all-time tonnage PR + "has prior tonnage" flag, split by slot context). Refreshed
 * incrementally on [save] of a completed session and rebuilt from a full per-exercise scan on
 * [delete] / [runMaintenance] cleanup / schema-version mismatch. Lets the exercise screens
 * skip parsing dozens of session files per open. See [ExerciseStats].
 *
 * ## Migration
 * Old-format files (`YYYY-MM-DD_{routineSlug}-{sessionId}.md`) are renamed and the exercise
 * index is rebuilt on first run, guarded by `history/_idx/.migrated`. The stats sidecars are
 * wiped at the same time and regenerated lazily on first read.
 */
@Singleton
class WorkoutRepository @Inject constructor(
    private val fileManager: FileManager,
    private val syncLedgerRepository: SyncLedgerRepository,
) {
    private val mutex = Mutex()

    // ─── History tree traversal ──────────────────────────────────────────────

    /** `history/` root. */
    private fun historyRoot(): File = File(fileManager.root, "history")

    /** Reserved sub-directory names under `history/` that are not `YYYY` session buckets. */
    private fun isReservedHistoryDir(name: String): Boolean =
        name == "_idx" || name == "_stats"

    /**
     * Every `history/YYYY/MM/` directory that currently exists, in no particular order. The one
     * place the "walk year dirs, skip `_idx`/`_stats`, then month dirs" shape lives — callers
     * (`getRoutineSessionFiles`, `getAllCompletedSessions`, migration, maintenance) iterate this
     * instead of re-deriving the reserved-name skip each time (miss one and a reserved dir gets
     * parsed as a session bucket).
     */
    private fun historyMonthDirs(): List<File> {
        val root = historyRoot()
        if (!root.exists()) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && !isReservedHistoryDir(it.name) }
            ?.flatMap { yearDir -> yearDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList() }
            ?: emptyList()
    }

    /** Every `*.md` session file under `history/YYYY/MM/`. */
    private fun historySessionFiles(): List<File> =
        historyMonthDirs().flatMap { dir ->
            dir.listFiles()?.filter { it.extension == "md" }?.toList() ?: emptyList()
        }

    // ─── File naming ──────────────────────────────────────────────────────────

    /**
     * `YYYY-MM-DD_{routineId}_{sessionId}.md`
     * Uses IDs (not human-readable slugs) so files can be located by ID without parsing.
     */
    private fun sessionFileName(session: WorkoutSession): String =
        "${session.date}_${session.routineId}_${session.id}.md"

    // ─── Exercise index ───────────────────────────────────────────────────────

    private fun indexDir(): File = File(File(fileManager.root, "history"), "_idx")

    /**
     * Adds [relPath] (relative to `history/`) to the index for [exerciseId].
     * Deduplicates. Must be called with [mutex] held.
     */
    private fun addToExerciseIndex(exerciseId: String, relPath: String) {
        val idxFile = File(indexDir().also { it.mkdirs() }, "$exerciseId.idx")
        val lines = if (idxFile.exists())
            idxFile.readLines().filter { it.isNotBlank() }.toMutableSet()
        else
            mutableSetOf()
        if (lines.add(relPath)) {
            idxFile.writeText(lines.joinToString("\n"))
        }
    }

    /**
     * Accumulates (exerciseId, relPath) pairs in-memory so that each .idx file is read
     * and written at most once, regardless of how many times it is touched during
     * migration or rebuild. Must be called with [mutex] held.
     */
    private class ExerciseIndexBatch {
        val perFile = HashMap<String, MutableSet<String>>() // exerciseId -> relPaths
        fun add(exerciseId: String, relPath: String) {
            perFile.getOrPut(exerciseId) { mutableSetOf() }.add(relPath)
        }
    }

    private fun flushExerciseIndexBatch(batch: ExerciseIndexBatch) {
        if (batch.perFile.isEmpty()) return
        val dir = indexDir().also { it.mkdirs() }
        for ((exerciseId, newPaths) in batch.perFile) {
            val idxFile = File(dir, "$exerciseId.idx")
            val merged = if (idxFile.exists())
                idxFile.readLines().filter { it.isNotBlank() }.toMutableSet()
            else
                mutableSetOf()
            val before = merged.size
            merged.addAll(newPaths)
            if (merged.size != before) {
                idxFile.writeText(merged.joinToString("\n"))
            }
        }
    }

    /**
     * Removes [relPath] from the index for [exerciseId].
     * Must be called with [mutex] held.
     */
    private fun removeFromExerciseIndex(exerciseId: String, relPath: String) {
        val idxFile = File(indexDir(), "$exerciseId.idx")
        if (!idxFile.exists()) return
        val updated = idxFile.readLines().filter { it.isNotBlank() && it != relPath }
        idxFile.writeText(updated.joinToString("\n"))
    }

    // ─── Exercise stats sidecar ──────────────────────────────────────────────

    private fun statsDir(): File = File(File(fileManager.root, "history"), "_stats")

    private fun statsFile(exerciseId: String): File =
        File(statsDir(), "$exerciseId.yaml")

    /** Reads and parses the sidecar for [exerciseId], or null if absent/unparseable. */
    private fun readStatsSidecar(exerciseId: String): ExerciseStats? {
        val f = statsFile(exerciseId)
        if (!f.exists()) return null
        return try {
            ExerciseStatsParser.fromYaml(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun writeStatsSidecar(stats: ExerciseStats) {
        val dir = statsDir().also { it.mkdirs() }
        File(dir, "${stats.exerciseId}.yaml").writeText(ExerciseStatsParser.toYaml(stats))
    }

    /**
     * Computes [exerciseId]'s stats from a full scan of that one exercise's history (via the
     * exercise index — bounded by how many sessions contain it, not the whole tree). Pure
     * read: no lock needed, and several of these can run concurrently on different exercises.
     */
    private fun computeExerciseStats(exerciseId: String): ExerciseStats {
        val sessions = getExerciseSessionFiles(exerciseId).mapNotNull { file ->
            try {
                WorkoutParser.fromMarkdown(file.readText())
            } catch (_: Exception) {
                null
            }
        }
        return ExerciseStatsCalculator.rebuild(exerciseId, sessions)
    }

    /**
     * [computeExerciseStats] + persist. Used on delete/cleanup and to backfill a missing or
     * stale sidecar. Must be called with [mutex] held (writes the sidecar).
     */
    private fun rebuildExerciseStats(exerciseId: String): ExerciseStats =
        computeExerciseStats(exerciseId).also { writeStatsSidecar(it) }

    /**
     * The exercise stats for [exerciseId] — the fast read the exercise screens use instead of
     * scanning history. Returns a cheap parse of the sidecar when it exists and matches the
     * current schema; otherwise computes it from a per-exercise scan (**outside** the write
     * lock, so concurrent first-time reads for different exercises of a superset / routine
     * don't serialize), then takes the lock only to persist it so the next call is fast.
     */
    suspend fun getExerciseStats(exerciseId: String): ExerciseStats =
        withContext(Dispatchers.IO) {
            readUsableSidecar(exerciseId)
                ?: run {
                    val computed = computeExerciseStats(exerciseId)
                    // Re-check under the lock: another caller (or a concurrent save) may have
                    // written a fresh sidecar while we were scanning — prefer that, it can only
                    // be newer. Otherwise persist ours.
                    mutex.withLock {
                        readUsableSidecar(exerciseId) ?: computed.also { writeStatsSidecar(it) }
                    }
                }
        }

    /**
     * A parsed sidecar only if it is safe to serve: current schema **and** for the exercise we
     * asked about. A mismatched `exerciseId` (a stray/copied file, a future bug) must not be
     * handed back as another exercise's stats — same guard [ExerciseStatsCalculator.merge] applies.
     */
    private fun readUsableSidecar(exerciseId: String): ExerciseStats? =
        readStatsSidecar(exerciseId)?.takeIf {
            it.schemaVersion == ExerciseStats.SCHEMA_VERSION && it.exerciseId == exerciseId
        }

    // ─── Home gitgraph cache ─────────────────────────────────────────────────

    private fun gitgraphCacheFile(): File =
        File(File(fileManager.root, "history"), "_gitgraph.yaml")

    /** The Monday that starts the oldest of the 4 history week-rows, for [today]. */
    private fun gitgraphWindowStart(today: LocalDate): LocalDate {
        val currentWeekMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return currentWeekMonday.minusWeeks(4)
    }

    private fun readGitgraphCache(): GitgraphHistory? {
        val f = gitgraphCacheFile()
        if (!f.exists()) return null
        return try {
            GitgraphHistoryParser.fromYaml(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun writeGitgraphCache(history: GitgraphHistory) {
        gitgraphCacheFile().parentFile?.mkdirs()
        gitgraphCacheFile().writeText(GitgraphHistoryParser.toYaml(history))
    }

    private fun deleteGitgraphCache() {
        gitgraphCacheFile().delete()
    }

    /**
     * Computes the 28-day history window from scratch: parses the visible 4 weeks plus a
     * 2-month lookback (so the oldest visible days still have a same-routine "previous" to
     * compare against — mirrors `MainViewModel`'s old `lookbackSessions`). Pure read, no lock.
     */
    private suspend fun computeGitgraphHistory(windowStart: LocalDate): GitgraphHistory {
        val windowEnd = windowStart.plusDays((GitgraphHistory.DAY_COUNT - 1).toLong())
        val windowSessions = getSessionsInRange(windowStart, windowEnd)
        val lookback = getSessionsInRange(windowStart.minusMonths(2), windowStart.minusDays(1))
        return GitgraphHistoryCalculator.buildWindow(
            windowStartMonday = windowStart,
            sessionsInWindow = windowSessions,
            priorSessionsByRoutine = lookback
                .filter { it.completedAt.isNotBlank() }
                .groupBy { it.routineId },
        )
    }

    /**
     * The home gitgraph's 4 history rows (28 day squares for the 4 weeks *before* the current
     * week) — the fast read `MainViewModel` uses instead of parsing ~3 months of session
     * files on every home open. Serves the cached YAML when it covers the right window and
     * matches the schema; otherwise recomputes the full window (once per week, as the window
     * slides, or after a delete / debug-seed drops the cache) and persists it.
     *
     * The current week's row and the "today" cell are NOT part of this — `MainViewModel`
     * builds those from a small current-week query and [GitgraphHistoryCalculator.dayCell].
     */
    suspend fun getGitgraphHistory(today: LocalDate = LocalDate.now()): GitgraphHistory =
        withContext(Dispatchers.IO) {
            val windowStart = gitgraphWindowStart(today)
            val cached = readGitgraphCache()
            if (cached != null &&
                cached.schemaVersion == GitgraphHistory.SCHEMA_VERSION &&
                cached.windowStartMonday == windowStart.toString() &&
                cached.isComplete()
            ) {
                return@withContext cached
            }
            val computed = computeGitgraphHistory(windowStart)
            mutex.withLock {
                // Re-check under the lock: a concurrent save/recompute may have written a
                // cache for the same window while we were scanning.
                val fresh = readGitgraphCache()
                if (fresh != null &&
                    fresh.schemaVersion == GitgraphHistory.SCHEMA_VERSION &&
                    fresh.windowStartMonday == windowStart.toString() &&
                    fresh.isComplete()
                ) {
                    fresh
                } else {
                    computed.also { writeGitgraphCache(it) }
                }
            }
        }

    /**
     * Recompute-and-persist the gitgraph cache for the current window. Called from
     * `MainViewModel` after events the cache can't self-heal from cheaply — a routine list
     * change (routine renamed / re-scheduled) or a debug-data reseed.
     */
    suspend fun refreshGitgraphHistory(today: LocalDate = LocalDate.now()) =
        withContext(Dispatchers.IO) {
            val windowStart = gitgraphWindowStart(today)
            val computed = computeGitgraphHistory(windowStart)
            mutex.withLock { writeGitgraphCache(computed) }
        }

    /**
     * Parse one real session file so snakeyaml + [WorkoutParser] are class-loaded and
     * JIT-warmed before the home screen's first genuine query needs them. On a cold start
     * that first full-session parse was ~1s of the home load; kicked from `MyGymApp.onCreate`
     * in parallel with everything else, it overlaps that cost instead of stacking it.
     * Cheap and best-effort — silently does nothing if there's no history yet.
     */
    suspend fun warmUpParsers() = withContext(Dispatchers.IO) {
        runCatching {
            // The single newest session file by name (date-prefixed, so name order == chrono order).
            val newest = historySessionFiles().maxByOrNull { it.name }
            newest?.let { WorkoutParser.fromMarkdown(it.readText()) }
        }
        Unit
    }

    /**
     * Returns the session [File]s listed in the index for [exerciseId].
     * Stale entries (deleted files) are silently filtered out.
     * Safe to call without [mutex] (read-only, small files).
     */
    private fun getExerciseSessionFiles(exerciseId: String): List<File> {
        val historyRoot = File(fileManager.root, "history")
        val idxFile = File(indexDir(), "$exerciseId.idx")
        if (!idxFile.exists()) return emptyList()
        return idxFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { relPath ->
                File(historyRoot, relPath).takeIf { it.exists() }
            }
    }

    /**
     * Returns all session [File]s belonging to [routineId] by filename pattern.
     *
     * Format: `YYYY-MM-DD_{routineId}_{sessionId}.md` → split on `_`, `parts[1] == routineId`.
     * No YAML parsing required.
     */
    private fun getRoutineSessionFiles(routineId: String): List<File> =
        historySessionFiles().filter { file ->
            file.nameWithoutExtension.split("_")
                .let { parts -> parts.size >= 3 && parts[1] == routineId }
        }

    /** Relative path from `history/` root for a given [session] file. */
    private fun relPath(session: WorkoutSession): String {
        val date = LocalDate.parse(session.date)
        return "${date.year}/${date.monthValue.toString().padStart(2, '0')}/${sessionFileName(session)}"
    }

    /**
     * Public variant of [relPath], for callers outside this repository that need to
     * locate a session's file without duplicating the naming scheme — e.g. the sync
     * ledger (`data/sync/`), which stores this alongside each queued session ID.
     */
    fun relPathFor(session: WorkoutSession): String = relPath(session)

    /** Absolute [File] for [session], derived the same way [save] locates it. */
    fun fileFor(session: WorkoutSession): File {
        val date = LocalDate.parse(session.date)
        val dir = fileManager.getHistoryDir(date.year, date.monthValue)
        return File(dir, sessionFileName(session))
    }

    // ─── Migration ────────────────────────────────────────────────────────────

    /**
     * One-time migration (guarded by `_idx/.migrated` sentinel):
     * - Renames old-format files (`YYYY-MM-DD_{slug}-{id}.md`, 2 `_`-segments) to new format.
     * - Rebuilds the entire exercise index from scratch.
     *
     * Old format has exactly 2 underscore-separated segments (date + slug-id).
     * New format has exactly 3 (date + routineId + sessionId).
     */
    suspend fun migrateOldSessionFiles() = withContext(Dispatchers.IO) {
        val idxDir = indexDir().also { it.mkdirs() }
        val sentinel = File(idxDir, ".migrated")
        if (sentinel.exists()) return@withContext

        mutex.withLock {
            // Rebuild index from scratch
            idxDir.listFiles()?.filter { it.extension == "idx" }?.forEach { it.delete() }
            // Drop every stats sidecar — they regenerate lazily on first read, and this is the
            // simplest way to guarantee none survives a rename/reindex with a stale relPath or
            // an old schema.
            statsDir().listFiles()?.filter { it.extension == "yaml" }?.forEach { it.delete() }
            // Same for the home gitgraph cache — recomputed on next home open.
            deleteGitgraphCache()

            val batch = ExerciseIndexBatch()
            historyMonthDirs().forEach { monthDir ->
                val yearName = monthDir.parentFile?.name ?: return@forEach
                // snapshot to avoid ConcurrentModification during rename
                val files = monthDir.listFiles()?.filter { it.extension == "md" }?.toList() ?: return@forEach
                files.forEach { file ->
                    try {
                        val session = WorkoutParser.fromMarkdown(file.readText())
                        val newFileName = sessionFileName(session)
                        val currentFile = if (file.name != newFileName) {
                            val dst = File(monthDir, newFileName)
                            if (file.renameTo(dst)) dst else file
                        } else {
                            file
                        }
                        val rel = "$yearName/${monthDir.name}/${currentFile.name}"
                        session.exercises.forEach { ex ->
                            batch.add(ex.exerciseId, rel)
                        }
                    } catch (_: Exception) { /* Skip malformed */ }
                }
            }
            flushExerciseIndexBatch(batch)
            sentinel.createNewFile()
        }
    }

    // ─── Core CRUD ────────────────────────────────────────────────────────────

    suspend fun save(session: WorkoutSession): WorkoutSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = if (session.id.isBlank()) {
                session.copy(id = UUID.randomUUID().toString().take(8))
            } else {
                session
            }

            val date = LocalDate.parse(updated.date)
            val dir = fileManager.getHistoryDir(date.year, date.monthValue)
            val fileName = sessionFileName(updated)
            File(dir, fileName).writeText(WorkoutParser.toMarkdown(updated))

            // Keep exercise index up to date — one read+write per distinct exerciseId
            val rel = "${date.year}/${date.monthValue.toString().padStart(2, '0')}/$fileName"
            val batch = ExerciseIndexBatch()
            updated.exercises.forEach { ex -> batch.add(ex.exerciseId, rel) }
            flushExerciseIndexBatch(batch)

            // Refresh the per-exercise stats sidecars. Only a completed session carries data
            // worth folding in; an in-progress save (autosave, back-out) leaves them untouched.
            // Incremental merge — no history scan — so this stays cheap on the save path.
            if (updated.completedAt.isNotBlank()) {
                updated.exercises.map { it.exerciseId }.distinct().forEach { exId ->
                    val merged = ExerciseStatsCalculator.merge(exId, readStatsSidecar(exId), updated)
                    writeStatsSidecar(merged)
                }
                invalidateGitgraphCacheIfInWindow(date)
            }

            updated
        }
    }

    /**
     * Drop the home gitgraph cache when a completed session lands in (or is removed from) the
     * range that [computeGitgraphHistory] reads — the 28-day visible window **plus** the 2-month
     * lookback before it (used for the oldest visible days' "previous session" comparison, so a
     * back-dated edit in that lookback can still shift a visible day's %/status).
     *
     * Normally a no-op: sessions are registered *today*, in the current week, outside both. But
     * a back-dated edit, or the window not having slid yet, can put `date` in range. Must be
     * called with [mutex] held.
     */
    private fun invalidateGitgraphCacheIfInWindow(date: LocalDate) {
        val cached = readGitgraphCache() ?: return
        val start = runCatching { LocalDate.parse(cached.windowStartMonday) }.getOrNull() ?: return
        val lookbackStart = start.minusMonths(2)
        val end = start.plusDays((GitgraphHistory.DAY_COUNT - 1).toLong())
        if (!date.isBefore(lookbackStart) && !date.isAfter(end)) {
            deleteGitgraphCache()
        }
    }

    suspend fun delete(session: WorkoutSession) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val date = LocalDate.parse(session.date)
            val dir = fileManager.getHistoryDir(date.year, date.monthValue)
            val fileName = sessionFileName(session)
            val file = File(dir, fileName)

            // Remove from exercise index
            val rel = "${date.year}/${date.monthValue.toString().padStart(2, '0')}/$fileName"
            session.exercises.forEach { ex -> removeFromExerciseIndex(ex.exerciseId, rel) }

            if (file.exists()) {
                file.delete()
            } else {
                // Fallback: old-format file still present — find by session ID in content
                dir.listFiles()?.firstOrNull { f ->
                    f.extension == "md" && try {
                        WorkoutParser.fromMarkdown(f.readText()).id == session.id
                    } catch (_: Exception) { false }
                }?.delete()
            }

            // A completed session may have held an exercise's PR or its most-recent sets — the
            // incremental sidecar can't "un-merge", so rebuild each affected sidecar from
            // what's left on disk. Skip this for a session that was never completed: `save()`
            // only ever folds a completed session into a sidecar (see `merge`), so an
            // incomplete one — a ghost session backed out of, in particular — contributed
            // nothing and its deletion invalidates nothing. This matters: ghost cleanup runs
            // on every back-out, and a 15-exercise rebuild there (each a full history scan)
            // was blocking the *next* routine open's save() on the shared mutex.
            if (session.completedAt.isNotBlank()) {
                session.exercises.map { it.exerciseId }.distinct().forEach { rebuildExerciseStats(it) }
                invalidateGitgraphCacheIfInWindow(LocalDate.parse(session.date))
            }
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    suspend fun getSessionsInRange(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<WorkoutSession> = withContext(Dispatchers.IO) {
        // Collect the in-range files by filename first (no I/O beyond listing), then parse
        // them in parallel — session .md files carry the full nested exercise/set YAML, so a
        // month's worth is meaningfully faster read concurrently than one at a time.
        val inRange = mutableListOf<File>()
        var current = startDate.withDayOfMonth(1)
        val end = endDate.withDayOfMonth(1)
        while (!current.isAfter(end)) {
            val dir = File(
                fileManager.root,
                "history/${current.year}/${current.monthValue.toString().padStart(2, '0')}"
            )
            dir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                val fileDate = runCatching {
                    LocalDate.parse(file.name.take(10), DateTimeFormatter.ISO_LOCAL_DATE)
                }.getOrNull()
                if (fileDate != null && !fileDate.isBefore(startDate) && !fileDate.isAfter(endDate)) {
                    inRange.add(file)
                }
            }
            current = current.plusMonths(1)
        }
        coroutineScope {
            inRange
                .map { file -> async { runCatching { WorkoutParser.fromMarkdown(file.readText()) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
                .sortedBy { it.date }
        }
    }

    /**
     * Returns the most recent completed session for [routineId], optionally before [beforeDate].
     *
     * The filename is `YYYY-MM-DD_{routineId}_{sessionId}.md`, so its date prefix sorts in the
     * same order as the day the session belongs to. We only parse from the newest filenames
     * inward until we hit a completed one — normally the very first — instead of parsing every
     * session that routine ever had. A handful of extra candidates covers the rare case of two
     * sessions of the same routine on one day (their `completedAt` breaks the tie once parsed).
     */
    suspend fun getLastSessionForRoutine(
        routineId: String,
        beforeDate: LocalDate? = null,
    ): WorkoutSession? =
        withContext(Dispatchers.IO) {
            // Newest filename first (date prefix sorts chronologically).
            val files = getRoutineSessionFiles(routineId).sortedByDescending { it.name }
            var best: WorkoutSession? = null
            for ((i, file) in files.withIndex()) {
                val session = try {
                    WorkoutParser.fromMarkdown(file.readText()).takeIf { it.completedAt.isNotBlank() }
                } catch (_: Exception) { null }
                if (session != null &&
                    (beforeDate == null || LocalDate.parse(session.date).isBefore(beforeDate)) &&
                    (best == null || session.completedAt > best!!.completedAt)
                ) {
                    best = session
                }
                // Once a completed session is found, only a file with the same date prefix
                // (a same-day sibling) could still have a later completedAt — stop otherwise.
                if (best != null) {
                    val datePrefix = file.name.take(10)
                    val nextSameDay = files.getOrNull(i + 1)?.name?.startsWith(datePrefix) == true
                    if (!nextSameDay) break
                }
            }
            best
        }

    /**
     * Finds a session by [sessionId] and [date].
     * Fast path: the session ID is the last `_`-separated segment of the new filename format.
     */
    suspend fun getSession(sessionId: String, date: LocalDate): WorkoutSession? =
        withContext(Dispatchers.IO) {
            val dir = fileManager.getHistoryDir(date.year, date.monthValue)
            if (!dir.exists()) return@withContext null

            // Fast path: new format ends with `_{sessionId}.md`
            dir.listFiles()?.filter { file ->
                file.extension == "md" && (
                    file.nameWithoutExtension.endsWith("_$sessionId") ||  // new format
                    file.nameWithoutExtension.endsWith("-$sessionId")      // old format pre-migration
                )
            }?.forEach { file ->
                try {
                    val session = WorkoutParser.fromMarkdown(file.readText())
                    if (session.id == sessionId) return@withContext session
                } catch (_: Exception) { }
            }

            // Fallback: full scan (edge cases / unusual IDs)
            dir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                try {
                    val session = WorkoutParser.fromMarkdown(file.readText())
                    if (session.id == sessionId) return@withContext session
                } catch (_: Exception) { }
            }
            null
        }

    /**
     * Returns completed sessions containing [exerciseId], newest first.
     * Uses the exercise index: O(sessions for that exercise) instead of O(all files).
     */
    suspend fun getSessionsForExercise(
        exerciseId: String,
        maxSessions: Int = 30,
    ): List<WorkoutSession> = withContext(Dispatchers.IO) {
        getExerciseSessionFiles(exerciseId)
            .mapNotNull { file ->
                try {
                    WorkoutParser.fromMarkdown(file.readText()).takeIf { session ->
                        session.completedAt.isNotBlank() &&
                            session.exercises.any { it.exerciseId == exerciseId }
                    }
                } catch (_: Exception) { null }
            }
            .sortedByDescending { it.completedAt }
            .take(maxSessions)
    }

    /**
     * All completed sessions across the entire `history/` tree, unfiltered. Used only by
     * the sync "Resync all" action (`OptionsScreen`) to re-enqueue every session for
     * server delivery — not for anything performance-sensitive, so a full scan is fine.
     */
    suspend fun getAllCompletedSessions(): List<WorkoutSession> = withContext(Dispatchers.IO) {
        historySessionFiles().mapNotNull { file ->
            try {
                WorkoutParser.fromMarkdown(file.readText()).takeIf { it.completedAt.isNotBlank() }
            } catch (_: Exception) { null } // Skip malformed
        }
    }

    // ─── Maintenance ─────────────────────────────────────────────────────────

    /** Result of [runMaintenance]: counts of what was cleaned up. */
    data class MaintenanceResult(val ghostsDeleted: Int, val prunedDeleted: Int, val orphanEcgDeleted: Int)

    /**
     * True if a session was never registered: `completedAt` is blank. Nothing else matters —
     * per an explicit product decision, a session you left without tapping "Termina" is gone
     * at the next launch no matter how much was logged into it. The app cannot tell a crash
     * from a deliberate exit at boot time, and the chosen trade-off is "always clean up"
     * (see [deleteUnfinalizedSessions] and the on-exit check in
     * ActiveRoutineViewModel.onCleared()).
     */
    private fun isGhostSession(session: WorkoutSession): Boolean =
        session.completedAt.isBlank()

    /** Result of [deleteUnfinalizedSessions]: how many ghost `.md` (and paired `.ecg`) went. */
    data class GhostSweepResult(val ghostsDeleted: Int, val ecgDeleted: Int)

    /**
     * Unthrottled boot sweep: delete EVERY session with a blank `completedAt`, plus its
     * `ecg/{id}.ecg` sidecar. Runs on every single launch (unlike [runMaintenance], which is
     * throttled) because "I closed the app mid-session" must self-heal by the next start, not
     * up to 12h later. Cheap: only unfinalized sessions are touched, and there are normally
     * zero or one. A stats-sidecar rebuild is never needed — a ghost has no completed work
     * that could have fed one. Mirrors ActiveRoutineViewModel.onCleared()'s on-exit check for
     * the case where onCleared() never got to run (process kill, crash).
     */
    suspend fun deleteUnfinalizedSessions(): GhostSweepResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            var ghostsDeleted = 0
            val deletedSessionIds = mutableSetOf<String>()
            historyMonthDirs().forEach { monthDir ->
                val yearName = monthDir.parentFile?.name ?: return@forEach
                monthDir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                    val session = try {
                        WorkoutParser.fromMarkdown(file.readText())
                    } catch (_: Exception) {
                        return@forEach // malformed — leave it for runMaintenance to reap
                    }
                    if (session.completedAt.isNotBlank()) return@forEach

                    val rel = "$yearName/${monthDir.name}/${file.name}"
                    session.exercises.forEach { removeFromExerciseIndex(it.exerciseId, rel) }
                    if (file.delete()) {
                        ghostsDeleted++
                        deletedSessionIds.add(session.id)
                    }
                }
            }

            var ecgDeleted = 0
            val ecgDir = fileManager.getDir("ecg")
            if (ecgDir.exists()) {
                ecgDir.listFiles()?.filter { it.extension == "ecg" }?.forEach { file ->
                    if (file.nameWithoutExtension in deletedSessionIds && file.delete()) ecgDeleted++
                }
            }

            if (ghostsDeleted > 0) deleteGitgraphCache()
            GhostSweepResult(ghostsDeleted, ecgDeleted)
        }
    }

    /**
     * Throttled boot maintenance: old-session pruning (>3 months) and orphan-ECG cleanup, in a
     * SINGLE walk over `history/` that parses each `.md` once. This is the expensive pass —
     * it re-parses the whole session history and can rebuild stats sidecars — so it is
     * throttled to at most once every [MIN_INTERVAL_HOURS] hours via an mtime sentinel.
     *
     * Ghost-session cleanup is NOT throttled and does not live here — see
     * [deleteUnfinalizedSessions], which runs on every launch. This pass still reaps any
     * unfinalized session it happens to see (via [isGhostSession]) as a cheap backstop, but
     * the unthrottled sweep is what actually guarantees "closed mid-session ⇒ gone next start".
     *
     * Pass [force] to bypass the throttle (e.g. a manual "clean up now" action, if ever added).
     */
    suspend fun runMaintenance(
        cutoffDate: LocalDate,
        force: Boolean = false,
    ): MaintenanceResult = withContext(Dispatchers.IO) {
        val sentinel = File(indexDir().also { it.mkdirs() }, ".last_maintenance")
        if (!force && sentinel.exists()) {
            val ageHours = (System.currentTimeMillis() - sentinel.lastModified()) / 3_600_000.0
            if (ageHours < MIN_INTERVAL_HOURS) return@withContext MaintenanceResult(0, 0, 0)
        }

        mutex.withLock {
            var ghostsDeleted = 0
            var prunedDeleted = 0
            val validSessionIds = mutableSetOf<String>()
            // Exercises whose history shrank this run — their sidecars are rebuilt at the end.
            val staleStatsExerciseIds = mutableSetOf<String>()

            run {
                historyMonthDirs().forEach { monthDir ->
                    val yearName = monthDir.parentFile?.name ?: return@forEach
                    monthDir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                        val rel = "$yearName/${monthDir.name}/${file.name}"

                        // Malformed filename date -> discard outright, same as before.
                        val fileDate = try {
                            LocalDate.parse(file.name.take(10), DateTimeFormatter.ISO_LOCAL_DATE)
                        } catch (_: Exception) {
                            file.delete()
                            return@forEach
                        }

                        // One parse serves all three checks below.
                        val session = try {
                            WorkoutParser.fromMarkdown(file.readText())
                        } catch (_: Exception) {
                            null
                        }

                        val isPruneCandidate = fileDate.isBefore(cutoffDate)
                        val isGhost = session != null && isGhostSession(session)

                        if (isPruneCandidate || isGhost) {
                            // A ghost session has no completed work, so it never fed a stats
                            // sidecar — only a *completed* pruned session can invalidate one.
                            val affectsStats =
                                session != null && session.completedAt.isNotBlank()
                            session?.exercises?.forEach { ex ->
                                removeFromExerciseIndex(ex.exerciseId, rel)
                                if (affectsStats) staleStatsExerciseIds.add(ex.exerciseId)
                            }
                            file.delete()
                            if (isGhost) ghostsDeleted++ else prunedDeleted++
                        } else {
                            val id = file.nameWithoutExtension.substringAfterLast("_")
                            if (id.isNotBlank()) validSessionIds.add(id)
                        }
                    }
                }
            }

            // Orphan ECG raws: any `.ecg` file whose sessionId has no surviving session file.
            val ecgDir = fileManager.getDir("ecg")
            var orphanEcgDeleted = 0
            if (ecgDir.exists()) {
                ecgDir.listFiles()?.filter { it.extension == "ecg" }?.forEach { file ->
                    if (file.nameWithoutExtension !in validSessionIds) {
                        if (file.delete()) orphanEcgDeleted++
                    }
                }
            }

            // Ghost/pruned sessions removed above may have been the source of a sidecar's PR or
            // "previous" — rebuild each affected one from surviving history. A ghost session by
            // definition has no completed work, so in practice this rarely changes anything,
            // but pruning old sessions genuinely can.
            staleStatsExerciseIds.forEach { rebuildExerciseStats(it) }

            // A pruned session (>3 months old) can fall in the gitgraph cache's 2-month
            // lookback and thus change the oldest visible days' "previous" comparison. Ghosts
            // never can. Drop the cache only when something was actually pruned.
            if (prunedDeleted > 0) deleteGitgraphCache()

            sentinel.writeText("")
            MaintenanceResult(ghostsDeleted, prunedDeleted, orphanEcgDeleted)
        }
    }

    companion object {
        private const val MIN_INTERVAL_HOURS = 12
    }

    /**
     * Updates [exerciseName] in every session that references [exerciseId].
     * Uses the exercise index — only touches files that actually contain the exercise.
     * Called from [ExerciseRepository] after a rename.
     */
    suspend fun updateExerciseNameInHistory(exerciseId: String, newName: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                getExerciseSessionFiles(exerciseId).forEach { file ->
                    try {
                        val session = WorkoutParser.fromMarkdown(file.readText())
                        if (session.exercises.any { it.exerciseId == exerciseId }) {
                            val updated = session.copy(
                                exercises = session.exercises.map { ex ->
                                    if (ex.exerciseId == exerciseId) ex.copy(exerciseName = newName)
                                    else ex
                                }
                            )
                            file.writeText(WorkoutParser.toMarkdown(updated))
                            // Content changed under an already-synced session — requeue it
                            // so the server gets the renamed content too. See docs/SYNC.md §3.4.
                            syncLedgerRepository.requeueIfChanged(session.id, file)
                        }
                    } catch (_: Exception) { /* Skip */ }
                }
            }
        }

    /**
     * Updates [routineName] in every session that references [routineId].
     * Uses filename-based lookup — only touches files for that routine.
     * No file rename needed: filenames embed routineId (not the name).
     * Called from [RoutineRepository] after a rename.
     */
    suspend fun updateRoutineNameInHistory(routineId: String, newName: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                var anyChanged = false
                getRoutineSessionFiles(routineId).forEach { file ->
                    try {
                        val session = WorkoutParser.fromMarkdown(file.readText())
                        if (session.routineName != newName) {
                            file.writeText(WorkoutParser.toMarkdown(session.copy(routineName = newName)))
                            syncLedgerRepository.requeueIfChanged(session.id, file)
                            anyChanged = true
                        }
                    } catch (_: Exception) { /* Skip */ }
                }
                // The gitgraph cache denormalizes routineName per day square — drop it so the
                // renamed name shows on the home. MainViewModel also reloads on this signal.
                if (anyChanged) deleteGitgraphCache()
            }
        }
}
