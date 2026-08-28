package com.mygymapp.data.repository

import android.content.Context
import com.mygymapp.data.model.FIXED_DAILY_ROUTINE_ID
import com.mygymapp.data.model.FIXED_DAILY_ROUTINE_NAME
import com.mygymapp.data.model.Routine
import com.mygymapp.data.parser.RoutineParser
import com.mygymapp.data.sync.RepoLedgerRepository
import com.mygymapp.data.sync.RepoSyncWorker
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
class RoutineRepository @Inject constructor(
    private val fileManager: FileManager,
    private val workoutRepository: WorkoutRepository,
    private val repoLedgerRepository: RepoLedgerRepository,
    @ApplicationContext private val appContext: Context,
) {
    private val cache = ConcurrentHashMap<String, Routine>()
    private val mutex = Mutex()
    private var loaded = false

    private fun routinesDir(): File = fileManager.getDir("routines")

    suspend fun getAll(): List<Routine> = withContext(Dispatchers.IO) {
        ensureLoaded()
        cache.values.toList().sortedWith(
            compareByDescending<Routine> { it.id == FIXED_DAILY_ROUTINE_ID }
                .thenBy { it.name.lowercase() },
        )
    }

    suspend fun getById(id: String): Routine? = withContext(Dispatchers.IO) {
        ensureLoaded()
        cache[id]
    }

    suspend fun getByDay(day: String): List<Routine> = withContext(Dispatchers.IO) {
        ensureLoaded()
        cache.values.filter { it.day.equals(day, ignoreCase = true) && it.enabled }
    }

    suspend fun save(routine: Routine): Routine = withContext(Dispatchers.IO) {
        var nameChanged = false
        var newRelPath = ""
        var newBytes: ByteArray? = null
        var obsoleteRelPath: String? = null
        var obsoleteHash = ""
        val saved = mutex.withLock {
            val now = LocalDateTime.now().toString()
            val updated = if (routine.id.isBlank()) {
                routine.copy(
                    id = "rt-" + UUID.randomUUID().toString().replace("-", "").take(8),
                    created = now,
                    updated = now,
                )
            } else {
                routine.copy(updated = now)
            }

            val fileName = slugify(updated.name, updated.id)
            val file = File(routinesDir(), "$fileName.md")

            val oldRoutine = cache[updated.id]
            if (oldRoutine != null) {
                val oldFileName = slugify(oldRoutine.name, oldRoutine.id)
                if (oldFileName != fileName) {
                    val oldFile = File(routinesDir(), "$oldFileName.md")
                    if (oldFile.exists()) obsoleteHash = repoLedgerRepository.hashOf(oldFile)
                    oldFile.delete()
                    obsoleteRelPath = "routines/$oldFileName.md"
                }
                nameChanged = oldRoutine.name != updated.name
            }

            val markdown = RoutineParser.toMarkdown(updated)
            file.writeText(markdown)
            cache[updated.id] = updated
            newRelPath = "routines/$fileName.md"
            newBytes = markdown.toByteArray()
            updated
        }
        if (nameChanged) {
            workoutRepository.updateRoutineNameInHistory(saved.id, saved.name)
        }
        // Full-store backup (docs/BACKUP.md §3.3) — queue the file, tombstone the stale
        // rename path, never blocking.
        newBytes?.let { repoLedgerRepository.requeueIfChanged(newRelPath, it) }
        obsoleteRelPath?.let { repoLedgerRepository.markDeleted(it, obsoleteHash) }
        RepoSyncWorker.Scheduler.runExpedited(appContext)
        saved
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        if (id == FIXED_DAILY_ROUTINE_ID) return@withContext
        var deletedRelPath: String? = null
        var deletedHash = ""
        mutex.withLock {
            val routine = cache.remove(id) ?: return@withLock
            val fileName = slugify(routine.name, routine.id)
            val file = File(routinesDir(), "$fileName.md")
            if (file.exists()) deletedHash = repoLedgerRepository.hashOf(file)
            file.delete()
            deletedRelPath = "routines/$fileName.md"
        }
        deletedRelPath?.let {
            repoLedgerRepository.markDeleted(it, deletedHash)
            RepoSyncWorker.Scheduler.runExpedited(appContext)
        }
    }

    /**
     * One-shot migration: fixes any [RoutineExercise] whose `repRangeMin > repRangeMax`
     * by setting `max = min`. Guarded by a sentinel. Returns count of fixed routines.
     */
    suspend fun fixInvalidRepRanges(): Int = withContext(Dispatchers.IO) {
        val sentinel = File(routinesDir(), ".reprange_fixed")
        if (sentinel.exists()) return@withContext 0
        ensureLoaded()
        var fixedRoutines = 0
        mutex.withLock {
            cache.values.toList().forEach { routine ->
                val needsFix = routine.exercises.any { it.repRangeMin > it.repRangeMax && it.repRangeMax > 0 }
                if (needsFix) {
                    val updatedExercises = routine.exercises.map { ex ->
                        if (ex.repRangeMin > ex.repRangeMax && ex.repRangeMax > 0) {
                            ex.copy(repRangeMax = ex.repRangeMin)
                        } else ex
                    }
                    val updated = routine.copy(exercises = updatedExercises)
                    val fileName = slugify(updated.name, updated.id)
                    val file = File(routinesDir(), "$fileName.md")
                    file.writeText(RoutineParser.toMarkdown(updated))
                    cache[updated.id] = updated
                    fixedRoutines++
                }
            }
            routinesDir().mkdirs()
            sentinel.createNewFile()
        }
        fixedRoutines
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val dir = routinesDir()
            if (dir.exists()) {
                dir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                    try {
                        val routine = RoutineParser.fromMarkdown(file.readText())
                        if (routine.id.isNotBlank()) {
                            cache[routine.id] = routine
                        }
                    } catch (_: Exception) {
                        // Skip malformed files
                    }
                }
            }
            ensureFixedDailyRoutine()
            loaded = true
        }
    }

    /**
     * Ensures the reserved "Fixed daily exercise" container routine always exists. Created
     * once (empty) on first launch and persisted; reloaded from disk on later boots. Must be
     * called while holding [mutex], after the directory scan.
     */
    private fun ensureFixedDailyRoutine() {
        if (cache.containsKey(FIXED_DAILY_ROUTINE_ID)) return
        val now = LocalDateTime.now().toString()
        val routine = Routine(
            id = FIXED_DAILY_ROUTINE_ID,
            name = FIXED_DAILY_ROUTINE_NAME,
            day = "",
            enabled = true,
            exercises = emptyList(),
            created = now,
            updated = now,
        )
        val dir = routinesDir().apply { mkdirs() }
        File(dir, "${slugify(routine.name, routine.id)}.md")
            .writeText(RoutineParser.toMarkdown(routine))
        cache[routine.id] = routine
    }
}
