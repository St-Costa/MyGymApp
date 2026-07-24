package com.mygymapp.data.parser

import com.mygymapp.data.model.Routine
import com.mygymapp.data.model.RoutineExercise

object RoutineParser {

    fun fromMarkdown(content: String): Routine {
        val doc = MarkdownParser.parse(content)
        val fm = doc.frontmatter
        val exercises = parseExercises(fm["exercises"])
        return Routine(
            id = fm["id"]?.toString() ?: "",
            name = fm["name"]?.toString() ?: "",
            day = fm["day"]?.toString() ?: "",
            enabled = fm["enabled"] as? Boolean ?: true,
            exercises = exercises,
            notes = doc.body,
            created = fm["created"]?.toString() ?: "",
            updated = fm["updated"]?.toString() ?: "",
        )
    }

    fun toMarkdown(routine: Routine): String {
        val exerciseList = routine.exercises.map { ex ->
            val map = linkedMapOf<String, Any?>(
                "exerciseId" to ex.exerciseId,
                "sets" to ex.sets,
            )
            if (ex.repRangeMin > 0 || ex.repRangeMax > 0) {
                map["repRangeMin"] = ex.repRangeMin
                map["repRangeMax"] = ex.repRangeMax
            }
            if (ex.timePerSetSeconds > 0) {
                map["timePerSetSeconds"] = ex.timePerSetSeconds
            }
            if (ex.supersetWithNext) {
                map["supersetWithNext"] = true
            }
            if (ex.isWarmup) {
                map["isWarmup"] = true
            }
            map
        }

        val frontmatter = linkedMapOf<String, Any?>(
            "id" to routine.id,
            "name" to routine.name,
            "day" to routine.day,
            "enabled" to routine.enabled,
            "created" to routine.created,
            "updated" to routine.updated,
            "exercises" to exerciseList,
        )
        return MarkdownParser.serialize(frontmatter, routine.notes)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseExercises(raw: Any?): List<RoutineExercise> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<String, Any> ?: return@mapNotNull null
            RoutineExercise(
                exerciseId = map["exerciseId"]?.toString() ?: return@mapNotNull null,
                sets = (map["sets"] as? Number)?.toInt() ?: 1,
                repRangeMin = (map["repRangeMin"] as? Number)?.toInt() ?: 0,
                repRangeMax = (map["repRangeMax"] as? Number)?.toInt() ?: 0,
                timePerSetSeconds = (map["timePerSetSeconds"] as? Number)?.toInt() ?: 0,
                supersetWithNext = map["supersetWithNext"] as? Boolean ?: false,
                isWarmup = map["isWarmup"] as? Boolean ?: false,
            )
        }
    }
}
