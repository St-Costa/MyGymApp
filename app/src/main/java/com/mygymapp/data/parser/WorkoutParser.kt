package com.mygymapp.data.parser

import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession

object WorkoutParser {

    fun fromMarkdown(content: String): WorkoutSession {
        val doc = MarkdownParser.parse(content)
        val fm = doc.frontmatter
        return WorkoutSession(
            id = fm["id"]?.toString() ?: "",
            routineId = fm["routineId"]?.toString() ?: "",
            routineName = fm["routineName"]?.toString() ?: "",
            date = fm["date"]?.toString() ?: "",
            startedAt = fm["startedAt"]?.toString() ?: "",
            completedAt = fm["completedAt"]?.toString() ?: "",
            bodyWeightKg = (fm["bodyWeightKg"] as? Number)?.toDouble() ?: 0.0,
            totalTonnage = (fm["totalTonnage"] as? Number)?.toDouble() ?: 0.0,
            tonnageByBodypart = parseTonnageMap(fm["tonnageByBodypart"]),
            sessionCalories = (fm["sessionCalories"] as? Number)?.toDouble() ?: 0.0,
            sessionTrimp = (fm["sessionTrimp"] as? Number)?.toDouble() ?: 0.0,
            vo2max = (fm["vo2max"] as? Number)?.toDouble() ?: 0.0,
            readiness = fm["readiness"]?.toString() ?: "",
            readinessLnRmssd = (fm["readinessLnRmssd"] as? Number)?.toDouble() ?: 0.0,
            hrrPerSet = parseDoubleList(fm["hrrPerSet"]),
            ecgBeats = (fm["ecgBeats"] as? Number)?.toInt() ?: 0,
            ecgDurationSec = (fm["ecgDurationSec"] as? Number)?.toDouble() ?: 0.0,
            ecgAvgHr = (fm["ecgAvgHr"] as? Number)?.toDouble() ?: 0.0,
            ecgSessionRmssd = (fm["ecgSessionRmssd"] as? Number)?.toDouble() ?: 0.0,
            ecgPacCount = (fm["ecgPacCount"] as? Number)?.toInt() ?: 0,
            ecgPauseCount = (fm["ecgPauseCount"] as? Number)?.toInt() ?: 0,
            ecgIrregularBeats = (fm["ecgIrregularBeats"] as? Number)?.toInt() ?: 0,
            cardiacDriftBpmMin = (fm["cardiacDriftBpmMin"] as? Number)?.toDouble() ?: 0.0,
            restingHr = (fm["restingHr"] as? Number)?.toInt() ?: 0,
            hrr60s = (fm["hrr60s"] as? Number)?.toDouble() ?: 0.0,
            sdnn = (fm["sdnn"] as? Number)?.toDouble() ?: 0.0,
            pnn50 = (fm["pnn50"] as? Number)?.toDouble() ?: 0.0,
            poincareSd1 = (fm["poincareSd1"] as? Number)?.toDouble() ?: 0.0,
            poincareSd2 = (fm["poincareSd2"] as? Number)?.toDouble() ?: 0.0,
            poincareRatio = (fm["poincareRatio"] as? Number)?.toDouble() ?: 0.0,
            afibSuspicionEpisodes = (fm["afibSuspicionEpisodes"] as? Number)?.toInt() ?: 0,
            exercises = parseExercises(fm["exercises"]),
            notes = doc.body,
        )
    }

    fun toMarkdown(session: WorkoutSession): String {
        val exerciseList = session.exercises.map { ex ->
            val sets = ex.sets.map { set ->
                when (set) {
                    is ExerciseSet.Strength -> linkedMapOf<String, Any?>(
                        "reps" to set.reps,
                        "weight" to set.weight,
                    )
                    is ExerciseSet.Stretch -> linkedMapOf<String, Any?>(
                        "timeSeconds" to set.timeSeconds,
                        "done" to set.done,
                    )
                }
            }
            linkedMapOf<String, Any?>(
                "exerciseId" to ex.exerciseId,
                "exerciseName" to ex.exerciseName,
                "bodypart" to ex.bodypart,
                "type" to ex.type.toFileString(),
                "completed" to ex.completed,
            ).apply {
                if (ex.excludeFromTonnage) put("excludeFromTonnage", true)
                if (ex.isDaily) put("isDaily", true)
                put("sets", sets)
            }
        }

        val frontmatter = linkedMapOf<String, Any?>(
            "id" to session.id,
            "routineId" to session.routineId,
            "routineName" to session.routineName,
            "date" to session.date,
            "startedAt" to session.startedAt,
            "completedAt" to session.completedAt,
            "totalTonnage" to session.totalTonnage,
            "tonnageByBodypart" to session.tonnageByBodypart.filterValues { it > 0.0 },
            "sessionCalories" to session.sessionCalories,
            "sessionTrimp" to session.sessionTrimp,
            "vo2max" to session.vo2max,
        ).apply {
            if (session.bodyWeightKg > 0.0) put("bodyWeightKg", session.bodyWeightKg)
            // ECG/HRV/recovery fields: omit when not computed (zero) to keep YAML lean
            if (session.readiness.isNotBlank()) put("readiness", session.readiness)
            if (session.readinessLnRmssd > 0.0) put("readinessLnRmssd", session.readinessLnRmssd)
            if (session.hrrPerSet.isNotEmpty()) put("hrrPerSet", session.hrrPerSet)
            if (session.ecgBeats > 0) put("ecgBeats", session.ecgBeats)
            if (session.ecgDurationSec > 0.0) put("ecgDurationSec", session.ecgDurationSec)
            if (session.ecgAvgHr > 0.0) put("ecgAvgHr", session.ecgAvgHr)
            if (session.ecgSessionRmssd > 0.0) put("ecgSessionRmssd", session.ecgSessionRmssd)
            if (session.ecgPacCount > 0) put("ecgPacCount", session.ecgPacCount)
            if (session.ecgPauseCount > 0) put("ecgPauseCount", session.ecgPauseCount)
            if (session.ecgIrregularBeats > 0) put("ecgIrregularBeats", session.ecgIrregularBeats)
            if (session.cardiacDriftBpmMin != 0.0) put("cardiacDriftBpmMin", session.cardiacDriftBpmMin)
            if (session.restingHr > 0) put("restingHr", session.restingHr)
            if (session.hrr60s > 0.0) put("hrr60s", session.hrr60s)
            if (session.sdnn > 0.0) put("sdnn", session.sdnn)
            if (session.pnn50 > 0.0) put("pnn50", session.pnn50)
            if (session.poincareSd1 > 0.0) put("poincareSd1", session.poincareSd1)
            if (session.poincareSd2 > 0.0) put("poincareSd2", session.poincareSd2)
            if (session.poincareRatio > 0.0) put("poincareRatio", session.poincareRatio)
            if (session.afibSuspicionEpisodes > 0) put("afibSuspicionEpisodes", session.afibSuspicionEpisodes)
            put("exercises", exerciseList)
        }
        return MarkdownParser.serialize(frontmatter, session.notes)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTonnageMap(raw: Any?): Map<String, Double> {
        val map = raw as? Map<String, Any> ?: return emptyMap()
        return map.mapValues { (_, v) -> (v as? Number)?.toDouble() ?: 0.0 }
    }

    private fun parseDoubleList(raw: Any?): List<Double> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? Number)?.toDouble() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseExercises(raw: Any?): List<WorkoutExercise> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<String, Any> ?: return@mapNotNull null
            val type = ExerciseType.fromString(map["type"]?.toString() ?: "forza")
            WorkoutExercise(
                exerciseId = map["exerciseId"]?.toString() ?: return@mapNotNull null,
                exerciseName = map["exerciseName"]?.toString() ?: "",
                bodypart = map["bodypart"]?.toString() ?: "",
                type = type,
                completed = map["completed"] as? Boolean ?: false,
                sets = parseSets(map["sets"], type),
                excludeFromTonnage = map["excludeFromTonnage"] as? Boolean ?: false,
                isDaily = map["isDaily"] as? Boolean ?: false,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSets(raw: Any?, type: ExerciseType): List<ExerciseSet> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<String, Any> ?: return@mapNotNull null
            when (type) {
                ExerciseType.FORZA -> ExerciseSet.Strength(
                    reps = (map["reps"] as? Number)?.toInt() ?: 0,
                    weight = (map["weight"] as? Number)?.toDouble() ?: 0.0,
                )
                ExerciseType.STRETCH -> ExerciseSet.Stretch(
                    timeSeconds = (map["timeSeconds"] as? Number)?.toInt() ?: 0,
                    done = map["done"] as? Boolean ?: false,
                )
            }
        }
    }
}
