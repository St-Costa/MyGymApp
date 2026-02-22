package com.mygymapp.data.repository

import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.parser.WorkoutParser
import kotlinx.coroutines.Dispatchers
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
 * ## Migration
 * Old-format files (`YYYY-MM-DD_{routineSlug}-{sessionId}.md`) are renamed and the exercise
 * index is rebuilt on first run, guarded by `history/_idx/.migrated`.
 */
@Singleton
class WorkoutRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val mutex = Mutex()

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
     * Removes [relPath] from the index for [exerciseId].
     * Must be called with [mutex] held.
     */
    private fun removeFromExerciseIndex(exerciseId: String, relPath: String) {
        val idxFile = File(indexDir(), "$exerciseId.idx")
        if (!idxFile.exists()) return
        val updated = idxFile.readLines().filter { it.isNotBlank() && it != relPath }
        idxFile.writeText(updated.joinToString("\n"))
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
    private fun getRoutineSessionFiles(routineId: String): List<File> {
        val historyRoot = File(fileManager.root, "history")
        val result = mutableListOf<File>()
        historyRoot.listFiles()?.forEach { yearDir ->
            if (!yearDir.isDirectory || yearDir.name == "_idx") return@forEach
            yearDir.listFiles()?.forEach { monthDir ->
                if (!monthDir.isDirectory) return@forEach
                monthDir.listFiles()?.filterTo(result) { file ->
                    file.extension == "md" && file.nameWithoutExtension.split("_")
                        .let { parts -> parts.size >= 3 && parts[1] == routineId }
                }
            }
        }
        return result
    }

    /** Relative path from `history/` root for a given [session] file. */
    private fun relPath(session: WorkoutSession): String {
        val date = LocalDate.parse(session.date)
        return "${date.year}/${date.monthValue.toString().padStart(2, '0')}/${sessionFileName(session)}"
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

            val historyRoot = File(fileManager.root, "history")
            if (historyRoot.exists()) {
                historyRoot.listFiles()?.forEach { yearDir ->
                    if (!yearDir.isDirectory || yearDir.name == "_idx") return@forEach
                    yearDir.listFiles()?.forEach { monthDir ->
                        if (!monthDir.isDirectory) return@forEach
                        // snapshot to avoid ConcurrentModification during rename
                        val files = monthDir.listFiles()
                            ?.filter { it.extension == "md" }
                            ?.toList() ?: return@forEach
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
                                val rel = "${yearDir.name}/${monthDir.name}/${currentFile.name}"
                                session.exercises.forEach { ex ->
                                    addToExerciseIndex(ex.exerciseId, rel)
                                }
                            } catch (_: Exception) { /* Skip malformed */ }
                        }
                    }
                }
            }
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

            // Keep exercise index up to date — distinct() avoids redundant file I/O
            // when the same exercise appears multiple times in one session
            val rel = "${date.year}/${date.monthValue.toString().padStart(2, '0')}/$fileName"
            updated.exercises.map { it.exerciseId }.distinct()
                .forEach { exerciseId -> addToExerciseIndex(exerciseId, rel) }

            updated
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
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    suspend fun getSessionsInRange(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<WorkoutSession> = withContext(Dispatchers.IO) {
        val sessions = mutableListOf<WorkoutSession>()
        var current = startDate.withDayOfMonth(1)
        val end = endDate.withDayOfMonth(1)

        while (!current.isAfter(end)) {
            val dir = File(
                fileManager.root,
                "history/${current.year}/${current.monthValue.toString().padStart(2, '0')}"
            )
            if (dir.exists()) {
                dir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                    try {
                        val fileDate = LocalDate.parse(
                            file.name.take(10), DateTimeFormatter.ISO_LOCAL_DATE
                        )
                        if (!fileDate.isBefore(startDate) && !fileDate.isAfter(endDate)) {
                            sessions.add(WorkoutParser.fromMarkdown(file.readText()))
                        }
                    } catch (_: Exception) { /* Skip malformed */ }
                }
            }
            current = current.plusMonths(1)
        }
        sessions.sortedBy { it.date }
    }

    /**
     * Returns the most recent completed session for [routineId].
     * Uses filename-based lookup: O(files for that routine) instead of O(all files).
     */
    suspend fun getLastSessionForRoutine(routineId: String): WorkoutSession? =
        withContext(Dispatchers.IO) {
            getRoutineSessionFiles(routineId)
                .mapNotNull { file ->
                    try {
                        WorkoutParser.fromMarkdown(file.readText())
                            .takeIf { it.completedAt.isNotBlank() }
                    } catch (_: Exception) { null }
                }
                .maxByOrNull { it.completedAt }
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

    // ─── Maintenance ─────────────────────────────────────────────────────────

    /**
     * Deletes all session files whose filename date is before [cutoffDate].
     * Cleans up exercise index entries for deleted files.
     */
    suspend fun pruneOldSessions(cutoffDate: LocalDate) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val historyRoot = File(fileManager.root, "history")
            if (!historyRoot.exists()) return@withLock
            historyRoot.listFiles()?.forEach { yearDir ->
                if (!yearDir.isDirectory || yearDir.name == "_idx") return@forEach
                yearDir.listFiles()?.forEach { monthDir ->
                    if (!monthDir.isDirectory) return@forEach
                    monthDir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                        try {
                            val fileDate = LocalDate.parse(
                                file.name.take(10), DateTimeFormatter.ISO_LOCAL_DATE
                            )
                            if (fileDate.isBefore(cutoffDate)) {
                                try {
                                    val session = WorkoutParser.fromMarkdown(file.readText())
                                    val rel = "${yearDir.name}/${monthDir.name}/${file.name}"
                                    session.exercises.forEach { ex ->
                                        removeFromExerciseIndex(ex.exerciseId, rel)
                                    }
                                } catch (_: Exception) { /* Delete anyway */ }
                                file.delete()
                            }
                        } catch (_: Exception) {
                            file.delete() // Malformed filename — discard
                        }
                    }
                }
            }
        }
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
                getRoutineSessionFiles(routineId).forEach { file ->
                    try {
                        val session = WorkoutParser.fromMarkdown(file.readText())
                        if (session.routineName != newName) {
                            file.writeText(WorkoutParser.toMarkdown(session.copy(routineName = newName)))
                        }
                    } catch (_: Exception) { /* Skip */ }
                }
            }
        }
}
