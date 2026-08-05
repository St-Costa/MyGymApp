package com.mygymapp.data.polar

import com.mygymapp.data.parser.MarkdownParser
import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists each 60s readiness measurement as its own file — `gymdata/readiness/{id}.md` —
 * so it survives past the in-memory `PolarManager.readinessResult` StateFlow and can be
 * synced to the self-hosted server immediately, independent of whether the user goes on
 * to complete a workout session that day. Same Markdown+YAML convention as every other
 * repository (see STORAGE.md), reusing [MarkdownParser] rather than a bespoke format.
 */
@Singleton
class ReadinessRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val mutex = Mutex()

    private fun dir(): File = fileManager.getDir("readiness")

    private fun fileFor(id: String): File = File(dir(), "$id.md")

    suspend fun save(
        readiness: String,
        lnRmssd: Double,
        restingHr: Int,
        vo2max: Double,
        recommendation: String,
    ): ReadinessEvent = withContext(Dispatchers.IO) {
        mutex.withLock {
            val event = ReadinessEvent(
                id = UUID.randomUUID().toString().take(8),
                measuredAt = LocalDateTime.now().toString(),
                readiness = readiness,
                lnRmssd = lnRmssd,
                restingHr = restingHr,
                vo2max = vo2max,
                recommendation = recommendation,
            )
            fileFor(event.id).writeText(toMarkdown(event))
            event
        }
    }

    /** Absolute file for an already-known event, e.g. for the sync worker to read+hash. */
    fun fileFor(event: ReadinessEvent): File = fileFor(event.id)

    suspend fun getById(id: String): ReadinessEvent? = withContext(Dispatchers.IO) {
        val file = fileFor(id)
        if (!file.exists()) return@withContext null
        runCatching { fromMarkdown(file.readText(), id) }.getOrNull()
    }

    suspend fun getAll(): List<ReadinessEvent> = withContext(Dispatchers.IO) {
        dir().listFiles()?.filter { it.extension == "md" }?.mapNotNull { file ->
            runCatching { fromMarkdown(file.readText(), file.nameWithoutExtension) }.getOrNull()
        }?.sortedBy { it.measuredAt } ?: emptyList()
    }

    private fun toMarkdown(e: ReadinessEvent): String = MarkdownParser.serialize(
        frontmatter = linkedMapOf(
            "id" to e.id,
            "measuredAt" to e.measuredAt,
            "readiness" to e.readiness,
            "lnRmssd" to e.lnRmssd,
            "restingHr" to e.restingHr,
            "vo2max" to e.vo2max,
            "recommendation" to e.recommendation,
        ),
        body = "",
    )

    private fun fromMarkdown(content: String, fallbackId: String): ReadinessEvent {
        val doc = MarkdownParser.parse(content)
        val fm = doc.frontmatter
        return ReadinessEvent(
            id = fm["id"] as? String ?: fallbackId,
            measuredAt = fm["measuredAt"] as? String ?: "",
            readiness = fm["readiness"] as? String ?: "",
            lnRmssd = (fm["lnRmssd"] as? Number)?.toDouble() ?: 0.0,
            restingHr = (fm["restingHr"] as? Number)?.toInt() ?: 0,
            vo2max = (fm["vo2max"] as? Number)?.toDouble() ?: 0.0,
            recommendation = fm["recommendation"] as? String ?: "",
        )
    }
}
