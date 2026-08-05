package com.mygymapp.data.repository

import com.mygymapp.data.model.ScaleWeighIn
import com.mygymapp.data.parser.ScaleWeighInParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScaleHistoryRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private fun dirFor(date: LocalDate): File =
        fileManager.getDir("scale/${date.year}/${date.monthValue.toString().padStart(2, '0')}")

    /** One weigh-in per calendar day: saving again on the same day overwrites the previous entry. */
    suspend fun save(
        weightKg: Double,
        bmi: Double,
        bodyFatPercent: Double,
        leanMassPercent: Double,
    ): ScaleWeighIn = saveForDate(LocalDateTime.now(), weightKg, bmi, bodyFatPercent, leanMassPercent)

    suspend fun saveForDate(
        recordedAt: LocalDateTime,
        weightKg: Double,
        bmi: Double,
        bodyFatPercent: Double,
        leanMassPercent: Double,
    ): ScaleWeighIn = withContext(Dispatchers.IO) {
        val date = recordedAt.toLocalDate()
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weighIn = ScaleWeighIn(
            id = dateStr,
            date = dateStr,
            recordedAt = recordedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            weightKg = weightKg,
            bmi = bmi,
            bodyFatPercent = bodyFatPercent,
            leanMassPercent = leanMassPercent,
        )
        val file = File(dirFor(date), "$dateStr.md")
        file.writeText(ScaleWeighInParser.toMarkdown(weighIn))
        weighIn
    }

    suspend fun getWeighInsInRange(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<ScaleWeighIn> = withContext(Dispatchers.IO) {
        val weighIns = mutableListOf<ScaleWeighIn>()
        var current = startDate.withDayOfMonth(1)
        val end = endDate.withDayOfMonth(1)

        while (!current.isAfter(end)) {
            val dir = File(
                fileManager.root,
                "scale/${current.year}/${current.monthValue.toString().padStart(2, '0')}"
            )
            if (dir.exists()) {
                dir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                    try {
                        val fileDate = LocalDate.parse(
                            file.name.take(10), DateTimeFormatter.ISO_LOCAL_DATE
                        )
                        if (!fileDate.isBefore(startDate) && !fileDate.isAfter(endDate)) {
                            weighIns.add(ScaleWeighInParser.fromMarkdown(file.readText()))
                        }
                    } catch (_: Exception) { /* Skip malformed */ }
                }
            }
            current = current.plusMonths(1)
        }
        weighIns.sortedBy { it.recordedAt }
    }
}
