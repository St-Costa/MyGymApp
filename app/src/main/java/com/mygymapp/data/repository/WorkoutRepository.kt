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

@Singleton
class WorkoutRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val mutex = Mutex()

    suspend fun save(session: WorkoutSession): WorkoutSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = if (session.id.isBlank()) {
                session.copy(id = UUID.randomUUID().toString().take(8))
            } else {
                session
            }

            val date = LocalDate.parse(updated.date)
            val dir = fileManager.getHistoryDir(date.year, date.monthValue)
            val routineSlug = updated.routineName.lowercase()
                .replace(Regex("[^a-z0-9\\s-]"), "")
                .replace(Regex("\\s+"), "-")
                .trim('-')
                .take(30)
            val fileName = "${updated.date}_${routineSlug}-${updated.id}.md"
            File(dir, fileName).writeText(WorkoutParser.toMarkdown(updated))
            updated
        }
    }

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
                        val datePrefix = file.name.take(10)
                        val fileDate = LocalDate.parse(datePrefix, DateTimeFormatter.ISO_LOCAL_DATE)
                        if (!fileDate.isBefore(startDate) && !fileDate.isAfter(endDate)) {
                            sessions.add(WorkoutParser.fromMarkdown(file.readText()))
                        }
                    } catch (_: Exception) {
                        // Skip malformed files
                    }
                }
            }
            current = current.plusMonths(1)
        }
        sessions.sortedBy { it.date }
    }

    suspend fun getLastSessionForRoutine(routineId: String): WorkoutSession? =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now()
            val candidates = mutableListOf<WorkoutSession>()
            // Collect all completed sessions for this routine in the last 6 months
            for (i in 0..5) {
                val month = today.minusMonths(i.toLong())
                val dir = File(
                    fileManager.root,
                    "history/${month.year}/${month.monthValue.toString().padStart(2, '0')}"
                )
                if (!dir.exists()) continue
                dir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                    try {
                        val session = WorkoutParser.fromMarkdown(file.readText())
                        if (session.routineId == routineId && session.completedAt.isNotBlank()) {
                            candidates.add(session)
                        }
                    } catch (_: Exception) {
                        // Skip
                    }
                }
            }
            // Return the one with the most recent completedAt (ISO format is lexicographically sortable)
            candidates.maxByOrNull { it.completedAt }
        }

    suspend fun delete(session: WorkoutSession) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val date = LocalDate.parse(session.date)
            val dir = fileManager.getHistoryDir(date.year, date.monthValue)
            dir.listFiles()?.firstOrNull { file ->
                try {
                    WorkoutParser.fromMarkdown(file.readText()).id == session.id
                } catch (_: Exception) { false }
            }?.delete()
        }
    }

    suspend fun getSession(sessionId: String, date: LocalDate): WorkoutSession? =
        withContext(Dispatchers.IO) {
            val dir = File(
                fileManager.root,
                "history/${date.year}/${date.monthValue.toString().padStart(2, '0')}"
            )
            if (!dir.exists()) return@withContext null
            dir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                try {
                    val session = WorkoutParser.fromMarkdown(file.readText())
                    if (session.id == sessionId) return@withContext session
                } catch (_: Exception) {
                    // Skip
                }
            }
            null
        }

    suspend fun getSessionsForExercise(
        exerciseId: String,
        maxSessions: Int = 30,
    ): List<WorkoutSession> = withContext(Dispatchers.IO) {
        val sessions = mutableListOf<WorkoutSession>()
        val today = LocalDate.now()

        for (i in 0..11) {
            val month = today.minusMonths(i.toLong())
            val dir = File(
                fileManager.root,
                "history/${month.year}/${month.monthValue.toString().padStart(2, '0')}"
            )
            if (!dir.exists()) continue
            dir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                try {
                    val session = WorkoutParser.fromMarkdown(file.readText())
                    if (session.completedAt.isNotBlank() &&
                        session.exercises.any { it.exerciseId == exerciseId }
                    ) {
                        sessions.add(session)
                    }
                } catch (_: Exception) {
                    // Skip
                }
            }
        }
        sessions.sortedByDescending { it.completedAt }.take(maxSessions)
    }
}
