package com.mygymapp.data.repository

import android.content.Context
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.parser.ExerciseParser
import com.mygymapp.data.sync.RepoLedgerRepository
import com.mygymapp.data.sync.RepoSyncWorker
import com.mygymapp.data.sync.SyncConfigRepository
import com.mygymapp.data.util.slugify
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExerciseRepository @Inject constructor(
    private val fileManager: FileManager,
    private val workoutRepository: WorkoutRepository,
    private val repoLedgerRepository: RepoLedgerRepository,
    private val syncConfigRepository: SyncConfigRepository,
    @ApplicationContext private val appContext: Context,
) {
    private val cache = ConcurrentHashMap<String, Exercise>()
    private val mutex = Mutex()
    private var loaded = false

    @Volatile private var bodypartsCache: List<String>? = null

    private fun exercisesDir(): File = fileManager.getDir("exercises")

    suspend fun getAll(): List<Exercise> = withContext(Dispatchers.IO) {
        ensureLoaded()
        cache.values.toList().sortedBy { it.name.lowercase() }
    }

    suspend fun getById(id: String): Exercise? = withContext(Dispatchers.IO) {
        ensureLoaded()
        cache[id]
    }

    suspend fun save(exercise: Exercise): Exercise = withContext(Dispatchers.IO) {
        var nameChanged = false
        // Populated inside the lock, acted on after it (server-sync bookkeeping, no need to
        // hold the mutex for network-adjacent work — same discipline as [nameChanged]).
        var newRelPath = ""
        var newBytes: ByteArray? = null
        var obsoleteRelPath: String? = null
        var obsoleteHash = ""
        val saved = mutex.withLock {
            val now = LocalDateTime.now().toString()
            val updated = if (exercise.id.isBlank()) {
                exercise.copy(
                    id = "ex-" + UUID.randomUUID().toString().replace("-", "").take(8),
                    created = now,
                    updated = now,
                )
            } else {
                exercise.copy(updated = now)
            }

            val fileName = slugify(updated.name, updated.id)
            val file = File(exercisesDir(), "$fileName.md")

            // Remove old file if name changed
            val oldExercise = cache[updated.id]
            if (oldExercise != null) {
                val oldFileName = slugify(oldExercise.name, oldExercise.id)
                if (oldFileName != fileName) {
                    val oldFile = File(exercisesDir(), "$oldFileName.md")
                    if (oldFile.exists()) obsoleteHash = repoLedgerRepository.hashOf(oldFile)
                    oldFile.delete()
                    obsoleteRelPath = "exercises/$oldFileName.md"
                }
                nameChanged = oldExercise.name != updated.name
            }

            val markdown = ExerciseParser.toMarkdown(updated)
            file.writeText(markdown)
            cache[updated.id] = updated
            bodypartsCache = null
            newRelPath = "exercises/$fileName.md"
            newBytes = markdown.toByteArray()
            updated
        }
        if (nameChanged) {
            workoutRepository.updateExerciseNameInHistory(saved.id, saved.name)
        }
        // Full-store backup (docs/BACKUP.md §3.3): queue the new/changed file in the ledger
        // so it's never lost — this always happens, regardless of the sync toggle, so
        // "Invia dati in coda" / the periodic net / a restore all see it.
        newBytes?.let { repoLedgerRepository.requeueIfChanged(newRelPath, it) }
        obsoleteRelPath?.let { repoLedgerRepository.markDeleted(it, obsoleteHash) }
        // Only kick an *immediate* upload when the sync toggle is on — same rule as the
        // per-session enqueue in ActiveRoutineViewModel.registerRoutine(). With the toggle
        // off, a catalogue edit stays queued locally and rides out on the next end-of-
        // session sync, the 4h periodic net, or "Invia dati in coda".
        if (syncConfigRepository.isEnabled() && syncConfigRepository.isConfigured()) {
            RepoSyncWorker.Scheduler.runExpedited(appContext)
        }
        saved
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        var deletedRelPath: String? = null
        var deletedHash = ""
        mutex.withLock {
            val exercise = cache.remove(id) ?: return@withLock
            val fileName = slugify(exercise.name, exercise.id)
            val file = File(exercisesDir(), "$fileName.md")
            if (file.exists()) deletedHash = repoLedgerRepository.hashOf(file)
            file.delete()
            bodypartsCache = null
            deletedRelPath = "exercises/$fileName.md"
        }
        deletedRelPath?.let {
            repoLedgerRepository.markDeleted(it, deletedHash)
            if (syncConfigRepository.isEnabled() && syncConfigRepository.isConfigured()) {
                RepoSyncWorker.Scheduler.runExpedited(appContext)
            }
        }
    }

    /**
     * One-shot migration: scans all exercises and fixes any where
     * `defaultRepRangeMin > defaultRepRangeMax` by setting `max = min`.
     * Guarded by a sentinel file so it runs at most once. Returns count of fixed exercises.
     */
    suspend fun fixInvalidRepRanges(): Int = withContext(Dispatchers.IO) {
        val sentinel = File(exercisesDir(), ".reprange_fixed")
        if (sentinel.exists()) return@withContext 0
        ensureLoaded()
        var fixed = 0
        mutex.withLock {
            val toFix = cache.values.filter { it.defaultRepRangeMin > it.defaultRepRangeMax }
            toFix.forEach { ex ->
                val updated = ex.copy(defaultRepRangeMax = ex.defaultRepRangeMin)
                val fileName = slugify(updated.name, updated.id)
                val file = File(exercisesDir(), "$fileName.md")
                file.writeText(ExerciseParser.toMarkdown(updated))
                cache[updated.id] = updated
                fixed++
            }
            exercisesDir().mkdirs()
            sentinel.createNewFile()
        }
        fixed
    }

    suspend fun getBodyparts(): List<String> {
        bodypartsCache?.let { return it }
        val computed = getAll().map { it.bodypart }.distinct().sorted()
        bodypartsCache = computed
        return computed
    }

    /**
     * Candidates for "Switch exercise" (see docs/CONVENTIONS.md#switch-exercise): same
     * bodypart AND same type as [current] — CARDIO is excluded upstream by callers, since
     * switch is only offered for FORZA/STRETCH slots — minus [excludeIds] (exercises already
     * occupying a slot in the current session, so a switch can never create a duplicate slot).
     */
    suspend fun getSwitchCandidates(current: Exercise, excludeIds: Set<String>): List<Exercise> =
        getAll().filter { candidate ->
            candidate.id != current.id &&
                candidate.id !in excludeIds &&
                candidate.bodypart == current.bodypart &&
                candidate.type == current.type
        }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val dir = exercisesDir()
            if (dir.exists()) {
                dir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                    try {
                        val exercise = ExerciseParser.fromMarkdown(file.readText())
                        if (exercise.id.isNotBlank()) {
                            cache[exercise.id] = exercise
                        }
                    } catch (_: Exception) {
                        // Skip malformed files
                    }
                }
            }
            loaded = true
        }
    }
}

