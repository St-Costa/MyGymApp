package com.mygymapp.ui.navigation

sealed class Screen(val route: String) {
    data object Main : Screen("main")
    data object WeekView : Screen("weekview")
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
    data object ExercisePicker : Screen("exercises/pick")
    data object Superset : Screen("workout/{sessionId}/superset/{exerciseId1}/{exerciseId2}") {
        fun createRoute(sessionId: String, exerciseId1: String, exerciseId2: String): String =
            "workout/$sessionId/superset/$exerciseId1/$exerciseId2"
    }
    data object SessionProgress : Screen("session/{sessionId}/{date}?justCompleted={justCompleted}") {
        fun createRoute(sessionId: String, date: String, justCompleted: Boolean = false): String =
            "session/$sessionId/$date?justCompleted=$justCompleted"
    }
    data object HeartRate : Screen("heartrate")
    data object ScaleDebug : Screen("scale_debug")
    data object Options : Screen("options")
}
