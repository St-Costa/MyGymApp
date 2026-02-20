package com.mygymapp.data.parser

import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseType

object ExerciseParser {

    fun fromMarkdown(content: String): Exercise {
        val doc = MarkdownParser.parse(content)
        val fm = doc.frontmatter
        return Exercise(
            id = fm["id"]?.toString() ?: "",
            name = fm["name"]?.toString() ?: "",
            type = ExerciseType.fromString(fm["type"]?.toString() ?: "forza"),
            bodypart = fm["bodypart"]?.toString() ?: "",
            link = fm["link"]?.toString() ?: "",
            notes = doc.body,
            created = fm["created"]?.toString() ?: "",
            updated = fm["updated"]?.toString() ?: "",
        )
    }

    fun toMarkdown(exercise: Exercise): String {
        val frontmatter = linkedMapOf<String, Any?>(
            "id" to exercise.id,
            "name" to exercise.name,
            "type" to exercise.type.toFileString(),
            "bodypart" to exercise.bodypart,
            "link" to exercise.link,
            "created" to exercise.created,
            "updated" to exercise.updated,
        )
        return MarkdownParser.serialize(frontmatter, exercise.notes)
    }
}
