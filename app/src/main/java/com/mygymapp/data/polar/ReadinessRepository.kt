package com.mygymapp.data.polar

import com.mygymapp.data.parser.MarkdownParser
import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
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
        stepsAvgPerDay: Double? = null,
        stepsDaysSpanned: Int? = null,
        stepsPreviousDay: Long? = null,
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
                stepsAvgPerDay = stepsAvgPerDay,
                stepsDaysSpanned = stepsDaysSpanned,
                stepsPreviousDay = stepsPreviousDay,
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

    /**
     * Most recent measurement already taken on [date] (default today), if any — used to
     * skip the automatic on-connect measurement when one was already done today (e.g. the
     * strap disconnects and reconnects). Reuses [getAll] rather than a bespoke index since
     * there's at most one file per day in practice.
     */
    suspend fun getLatestForDate(date: LocalDate = LocalDate.now()): ReadinessEvent? =
        getAll().lastOrNull { event ->
            runCatching { LocalDateTime.parse(event.measuredAt).toLocalDate() == date }
                .getOrDefault(false)
        }

    /**
     * Trailing 7-day average resting HR, used by [HrZoneCalculator] as a backup when
     * today's own reading is missing (readiness skipped, app not opened yet). Resting HR
     * moves only a few bpm day to day in a stable, regularly-training person, so a
     * week-long average is an acceptable stand-in — same reasoning as the sync server's
     * `resting_hr_7day_average` (see ECG_ADVANCED_ANALYSIS.md §6 in the server repo).
     */
    suspend fun getRecentAverageRestingHr(days: Long = 7): Int? {
        val cutoff = LocalDate.now().minusDays(days)
        val recent = getAll().filter { event ->
            runCatching { !LocalDateTime.parse(event.measuredAt).toLocalDate().isBefore(cutoff) }
                .getOrDefault(false) && event.restingHr > 0
        }
        if (recent.isEmpty()) return null
        return recent.map { it.restingHr }.average().toInt()
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
            "stepsAvgPerDay" to e.stepsAvgPerDay,
            "stepsDaysSpanned" to e.stepsDaysSpanned,
            "stepsPreviousDay" to e.stepsPreviousDay,
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
            stepsAvgPerDay = (fm["stepsAvgPerDay"] as? Number)?.toDouble(),
            stepsDaysSpanned = (fm["stepsDaysSpanned"] as? Number)?.toInt(),
            stepsPreviousDay = (fm["stepsPreviousDay"] as? Number)?.toLong(),
        )
    }
}
