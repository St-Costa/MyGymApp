package com.mygymapp.data.parser

import com.mygymapp.data.model.DEFAULT_BW_LOAD_PERCENT
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.LoadMode

object ExerciseParser {

    fun fromMarkdown(content: String): Exercise {
        val doc = MarkdownParser.parse(content)
        val fm = doc.frontmatter
        val isBodyweight = fm["isBodyweight"] as? Boolean ?: false
        // loadMode is the source of truth going forward; legacy files only ever had
        // isBodyweight, so absence of the new key falls back to it. "assisted" is a new
        // string that only ever appears once this feature has been used to save an exercise.
        val loadMode = when (fm["loadMode"]?.toString()) {
            "assisted" -> LoadMode.ASSISTED
            "bodyweight" -> LoadMode.BODYWEIGHT
            "manual" -> LoadMode.MANUAL
            else -> if (isBodyweight) LoadMode.BODYWEIGHT else LoadMode.MANUAL
        }
        return Exercise(
            id = fm["id"]?.toString() ?: "",
            name = fm["name"]?.toString() ?: "",
            type = ExerciseType.fromString(fm["type"]?.toString() ?: "forza"),
            bodypart = fm["bodypart"]?.toString() ?: "",
            link = fm["link"]?.toString() ?: "",
            notes = doc.body,
            defaultRepRangeMin = fm["defaultRepRangeMin"]?.toString()?.toIntOrNull() ?: 8,
            defaultRepRangeMax = fm["defaultRepRangeMax"]?.toString()?.toIntOrNull() ?: 12,
            created = fm["created"]?.toString() ?: "",
            updated = fm["updated"]?.toString() ?: "",
            isBodyweight = loadMode == LoadMode.BODYWEIGHT,
            // Bodyweight exercises always carry a load percent; legacy files written before the
            // field existed migrate to the default (see Exercise.bwLoadPercent). Non-bodyweight
            // exercises keep 0.
            bwLoadPercent = when (loadMode) {
                LoadMode.BODYWEIGHT ->
                    fm["bwLoadPercent"]?.toString()?.toIntOrNull() ?: DEFAULT_BW_LOAD_PERCENT
                else -> 0
            },
            loadMode = loadMode,
        )
    }

    fun toMarkdown(exercise: Exercise): String {
        val frontmatter = linkedMapOf<String, Any?>(
            "id" to exercise.id,
            "name" to exercise.name,
            "type" to exercise.type.toFileString(),
            "bodypart" to exercise.bodypart,
            "link" to exercise.link,
            "defaultRepRangeMin" to exercise.defaultRepRangeMin,
            "defaultRepRangeMax" to exercise.defaultRepRangeMax,
            "created" to exercise.created,
            "updated" to exercise.updated,
        ).apply {
            // isBodyweight (not loadMode) is the authority here, same as before this field
            // existed — a caller that builds Exercise(isBodyweight = true, ...) without also
            // setting loadMode must still round-trip as bodyweight. loadMode only adds the
            // ASSISTED case on top; BODYWEIGHT is always driven by isBodyweight.
            if (exercise.isBodyweight) {
                // Keep writing isBodyweight too — older app builds and the server-side
                // analysis (ANALYSIS_SPEC.md) still read that key, not loadMode.
                put("isBodyweight", true)
                put("bwLoadPercent", exercise.bwLoadPercent)
                put("loadMode", "bodyweight")
            } else if (exercise.loadMode == LoadMode.ASSISTED) {
                put("loadMode", "assisted")
            }
            // LoadMode.MANUAL (and not bodyweight/assisted): omit both keys, the default.
        }
        return MarkdownParser.serialize(frontmatter, exercise.notes)
    }
}
