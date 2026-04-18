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
            completedAt = fm["completedAt"]?.toString() ?: "",
            totalTonnage = (fm["totalTonnage"] as? Number)?.toDouble() ?: 0.0,
            tonnageByBodypart = parseTonnageMap(fm["tonnageByBodypart"]),
            sessionCalories = (fm["sessionCalories"] as? Number)?.toDouble() ?: 0.0,
            sessionTrimp = (fm["sessionTrimp"] as? Number)?.toDouble() ?: 0.0,
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
                "sets" to sets,
            )
        }

        val frontmatter = linkedMapOf<String, Any?>(
            "id" to session.id,
            "routineId" to session.routineId,
            "routineName" to session.routineName,
            "date" to session.date,
            "completedAt" to session.completedAt,
            "totalTonnage" to session.totalTonnage,
            "tonnageByBodypart" to session.tonnageByBodypart,
            "sessionCalories" to session.sessionCalories,
            "sessionTrimp" to session.sessionTrimp,
            "exercises" to exerciseList,
        )
        return MarkdownParser.serialize(frontmatter, session.notes)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTonnageMap(raw: Any?): Map<String, Double> {
        val map = raw as? Map<String, Any> ?: return emptyMap()
        return map.mapValues { (_, v) -> (v as? Number)?.toDouble() ?: 0.0 }
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
