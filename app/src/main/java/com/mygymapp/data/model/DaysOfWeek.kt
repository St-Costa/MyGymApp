package com.mygymapp.data.model

/**
 * Canonical order of ISO weekday keys used across the app (routine `day` field,
 * pickers, week view). Kept in [data/model] rather than a screen file so both
 * the RoutineEdit ViewModel/Screen and any future consumer can share it.
 */
val DAYS_OF_WEEK = listOf(
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
)
