package com.mygymapp.data.repository

import com.mygymapp.data.model.Routine
import com.mygymapp.data.parser.RoutineParser
import com.mygymapp.data.util.slugify
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
) {
    private val cache = ConcurrentHashMap<String, Routine>()
    private val mutex = Mutex()
    private var loaded = false

    private fun routinesDir(): File = fileManager.getDir("routines")

    suspend fun getAll(): List<Routine> = withContext(Dispatchers.IO) {
        ensureLoaded()
        cache.values.toList().sortedBy { it.name.lowercase() }
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
                    File(routinesDir(), "$oldFileName.md").delete()
                }
                nameChanged = oldRoutine.name != updated.name
            }

            file.writeText(RoutineParser.toMarkdown(updated))
            cache[updated.id] = updated
            updated
        }
        if (nameChanged) {
            workoutRepository.updateRoutineNameInHistory(saved.id, saved.name)
        }
        saved
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val routine = cache.remove(id) ?: return@withLock
            val fileName = slugify(routine.name, routine.id)
            File(routinesDir(), "$fileName.md").delete()
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
            loaded = true
        }
    }
}
