package com.mygymapp.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Main : Screen("main")
    data object ExerciseList : Screen("exercises")
    data object ExerciseEdit : Screen("exercises/edit?id={id}") {
        fun createRoute(id: String? = null): String =
            if (id != null) "exercises/edit?id=$id" else "exercises/edit"
    }
    data object RoutineList : Screen("routines")
    data object RoutineEdit : Screen("routines/edit?id={id}") {
        fun createRoute(id: String? = null): String =
            if (id != null) "routines/edit?id=$id" else "routines/edit"
    }
    data object ActiveRoutine : Screen("workout/{routineId}") {
        fun createRoute(routineId: String): String = "workout/$routineId"
    }
    data object StrengthExercise : Screen("workout/{sessionId}/strength/{exerciseId}") {
        fun createRoute(sessionId: String, exerciseId: String): String =
            "workout/$sessionId/strength/$exerciseId"
    }
    data object StretchExercise : Screen("workout/{sessionId}/stretch/{exerciseId}") {
        fun createRoute(sessionId: String, exerciseId: String): String =
            "workout/$sessionId/stretch/$exerciseId"
    }
    data object CardioExercise : Screen("workout/{sessionId}/cardio/{exerciseId}") {
        fun createRoute(sessionId: String, exerciseId: String): String =
            "workout/$sessionId/cardio/$exerciseId"
    }
    data object ExercisePicker : Screen(
        "exercises/pick?bodypart={bodypart}&type={type}&excludeIds={excludeIds}&resultKeySide={resultKeySide}"
    ) {
        /**
         * Filtered picker for "Switch exercise" (docs/CONVENTIONS.md#switch-exercise):
         * [bodypart]/[type] restrict candidates to the slot being switched, [excludeIds]
         * (comma-separated exerciseIds) excludes exercises already occupying a slot in the
         * current session. The unfiltered `ExercisePicker.route` (used by RoutineEdit) still
         * works as-is — all query params default to empty/no-op.
         *
         * [resultKeySide]: for the Superset screen only, the 1-based position (1..3) of the
         * chain member that opened the picker — the result is written back under
         * `pickedExerciseIdSide{N}` instead of the plain `pickedExerciseId` key, so the
         * independent slots can never cross-apply a switch meant for another member. 0
         * (default) means "not a superset member" and uses the plain key.
         */
        fun createRoute(
            bodypart: String,
            type: String,
            excludeIds: Set<String>,
            resultKeySide: Int = 0,
        ): String =
            // bodypart is free-text the user typed (BodyPartAutocomplete) — encode it so a
            // name containing '&'/'='/'#' can't corrupt the query string and silently break
            // or misparse the other params (type, excludeIds, resultKeySide).
            "exercises/pick?bodypart=${Uri.encode(bodypart)}&type=${Uri.encode(type)}" +
                "&excludeIds=${Uri.encode(excludeIds.joinToString(","))}&resultKeySide=$resultKeySide"
    }
    data object Superset : Screen("workout/{sessionId}/superset/{exerciseIds}") {
        /** [exerciseIds] holds the 2–3 chain members in execution order, comma-separated. */
        fun createRoute(sessionId: String, exerciseIds: List<String>): String =
            "workout/$sessionId/superset/${exerciseIds.joinToString(",")}"
    }
    data object SessionProgress : Screen("session/{sessionId}/{date}?justCompleted={justCompleted}") {
        fun createRoute(sessionId: String, date: String, justCompleted: Boolean = false): String =
            "session/$sessionId/$date?justCompleted=$justCompleted"
    }
    data object HeartRate : Screen("heartrate")
    data object ScaleDebug : Screen("scale_debug")
    data object PolarDebug : Screen("polar_debug")
    data object SessionSummaryPreview : Screen("session_summary_preview")
    data object Options : Screen("options")
}
