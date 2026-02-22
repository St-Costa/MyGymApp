package com.mygymapp.data.repository

import com.mygymapp.data.model.Exercise
import com.mygymapp.data.parser.ExerciseParser
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
) {
    private val cache = ConcurrentHashMap<String, Exercise>()
    private val mutex = Mutex()
    private var loaded = false

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
                    File(exercisesDir(), "$oldFileName.md").delete()
                }
                nameChanged = oldExercise.name != updated.name
            }

            file.writeText(ExerciseParser.toMarkdown(updated))
            cache[updated.id] = updated
            updated
        }
        if (nameChanged) {
            workoutRepository.updateExerciseNameInHistory(saved.id, saved.name)
        }
        saved
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val exercise = cache.remove(id) ?: return@withLock
            val fileName = slugify(exercise.name, exercise.id)
            File(exercisesDir(), "$fileName.md").delete()
        }
    }

    suspend fun getBodyparts(): List<String> {
        return getAll().map { it.bodypart }.distinct().sorted()
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

fun slugify(name: String, id: String): String {
    val slug = name.lowercase()
        .replace(Regex("[^a-z0-9\\s-]"), "")
        .replace(Regex("\\s+"), "-")
        .trim('-')
        .take(40)
    return "$slug-$id"
}
