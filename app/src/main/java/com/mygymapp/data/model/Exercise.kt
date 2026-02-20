package com.mygymapp.data.model

data class Exercise(
    val id: String,
    val name: String,
    val type: ExerciseType,
    val bodypart: String,
    val link: String = "",
    val notes: String = "",
    val created: String = "",
    val updated: String = "",
)

enum class ExerciseType {
    FORZA,
    STRETCH;

    companion object {
        fun fromString(value: String): ExerciseType =
            when (value.lowercase()) {
                "stretch" -> STRETCH
                else -> FORZA
            }
    }

    fun toFileString(): String = when (this) {
        FORZA -> "forza"
        STRETCH -> "stretch"
    }
}
