package com.mygymapp.data.model

/** Reserved id of the non-deletable, non-disableable "Fixed daily exercise" container routine. */
const val FIXED_DAILY_ROUTINE_ID = "rt-fixeddaily"
const val FIXED_DAILY_ROUTINE_NAME = "Fixed daily exercise"

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
    val isWarmup: Boolean = false,
)
