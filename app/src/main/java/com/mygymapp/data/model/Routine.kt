package com.mygymapp.data.model

data class Routine(
    val id: String,
    val name: String,
    val day: String = "",
    val enabled: Boolean = true,
    val exercises: List<RoutineExercise> = emptyList(),
    val notes: String = "",
    val created: String = "",
    val updated: String = "",
)

data class RoutineExercise(
    val exerciseId: String,
    val sets: Int,
    val repRangeMin: Int = 0,
    val repRangeMax: Int = 0,
    val timePerSetSeconds: Int = 0,
    val supersetWithNext: Boolean = false,
)
