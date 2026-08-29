package com.mygymapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.screen.main.MainScreen
import com.mygymapp.ui.screen.exerciselist.ExerciseListScreen
import com.mygymapp.ui.screen.exerciseedit.ExerciseEditScreen
import com.mygymapp.ui.screen.routinelist.RoutineListScreen
import com.mygymapp.ui.screen.routineedit.RoutineEditScreen
import com.mygymapp.ui.screen.routineedit.RoutineEditViewModel
import com.mygymapp.ui.screen.activeroutine.ActiveRoutineScreen
import com.mygymapp.ui.screen.strengthexercise.StrengthExerciseScreen
import com.mygymapp.ui.screen.stretchexercise.StretchExerciseScreen
import com.mygymapp.ui.screen.cardioexercise.CardioExerciseScreen
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.screen.superset.SupersetScreen
import com.mygymapp.ui.screen.sessionprogress.SessionProgressScreen
import com.mygymapp.ui.screen.heartrate.HeartRateScreen
import com.mygymapp.ui.screen.options.OptionsScreen
import com.mygymapp.ui.util.MAX_SUPERSET_SIZE

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Main.route,
        // Surface Compose testTags as Android resource-ids so UiAutomator (baseline-profile
        // generator + macrobenchmark, docs/CHANGELOG.md § Baseline Profile) can wait on
        // GITGRAPH_TEST_TAG. No-op for the normal app; nothing else reads these.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
    ) {
        composable(Screen.Main.route) {
            MainScreen(
                onNavigateToExercises = { navController.navigate(Screen.ExerciseList.route) },
                onNavigateToRoutines = { navController.navigate(Screen.RoutineList.route) },
                onNavigateToHeartRate = { navController.navigate(Screen.HeartRate.route) },
                onNavigateToOptions = { navController.navigate(Screen.Options.route) },
                onNavigateToSessionProgress = { sessionId, date ->
                    navController.navigate(Screen.SessionProgress.createRoute(sessionId, date))
                },
                onNavigateToRoutine = { routineId ->
                    navController.navigate(Screen.ActiveRoutine.createRoute(routineId))
                },
            )
        }

        composable(Screen.ExerciseList.route) {
            ExerciseListScreen(
                onNavigateToEdit = { id ->
                    navController.navigate(Screen.ExerciseEdit.createRoute(id))
                },
                onNavigateToNew = {
                    navController.navigate(Screen.ExerciseEdit.createRoute())
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.ExerciseEdit.route,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; defaultValue = "" }
            ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.takeIf { it.isNotBlank() }
            ExerciseEditScreen(
                exerciseId = id,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.RoutineList.route) {
            RoutineListScreen(
                onNavigateToEdit = { id ->
                    navController.navigate(Screen.RoutineEdit.createRoute(id))
                },
                onNavigateToNew = {
                    navController.navigate(Screen.RoutineEdit.createRoute())
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.RoutineEdit.route,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; defaultValue = "" }
            ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.takeIf { it.isNotBlank() }
            val viewModel: RoutineEditViewModel = hiltViewModel()

            // Observe picker result
            val pickedId = backStackEntry.savedStateHandle.get<String>("pickedExerciseId")
            LaunchedEffect(pickedId) {
                if (pickedId != null) {
                    viewModel.addExercise(pickedId)
                    backStackEntry.savedStateHandle.remove<String>("pickedExerciseId")
                }
            }

            RoutineEditScreen(
                routineId = id,
                onBack = { navController.popBackStack() },
                onPickExercise = { navController.navigate("exercises/pick") },
                viewModel = viewModel,
            )
        }

        composable(
            route = Screen.ActiveRoutine.route,
            arguments = listOf(
                navArgument("routineId") { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val viewModel: com.mygymapp.ui.screen.activeroutine.ActiveRoutineViewModel = hiltViewModel()

            // Observe single-exercise completion result
            val completedExId = backStackEntry.savedStateHandle.get<String>("completedExerciseId")
            LaunchedEffect(completedExId) {
                if (completedExId != null) {
                    viewModel.markExerciseCompleted(completedExId)
                    backStackEntry.savedStateHandle.remove<String>("completedExerciseId")
                }
            }

            // Observe superset completion result (comma-separated chain member exerciseIds)
            val completedSupersetIds = backStackEntry.savedStateHandle.get<String>("completedSupersetIds")
            LaunchedEffect(completedSupersetIds) {
                if (completedSupersetIds != null) {
                    completedSupersetIds.split(",").forEach { id ->
                        viewModel.markExerciseCompleted(id)
                    }
                    backStackEntry.savedStateHandle.remove<String>("completedSupersetIds")
                }
            }

            // Observe "Switch exercise" results (docs/CONVENTIONS.md#switch-exercise), format
            // "oldExerciseId,newExerciseId" — the exercise screen already applied the switch to
            // the session file itself (via its own switchExercise call), but this VM's in-memory
            // _uiState.exercises list still shows the old exercise until told about the swap, so
            // it never reflects the new one when the user navigates back. One key covers both
            // Strength/Stretch and each Superset side, since they're all the same "one slot
            // changed exerciseId" event from this screen's point of view.
            val switchedIds = backStackEntry.savedStateHandle.get<String>("switchedExerciseIds")
            LaunchedEffect(switchedIds) {
                if (switchedIds != null) {
                    val (oldId, newId) = switchedIds.split(",", limit = 2)
                    viewModel.applyExerciseSwitch(oldId, newId)
                    backStackEntry.savedStateHandle.remove<String>("switchedExerciseIds")
                }
            }

            ActiveRoutineScreen(
                onNavigateToExercise = { sessionId, exerciseId, type ->
                    val route = when (type) {
                        ExerciseType.STRETCH -> Screen.StretchExercise.createRoute(sessionId, exerciseId)
                        ExerciseType.CARDIO -> Screen.CardioExercise.createRoute(sessionId, exerciseId)
                        ExerciseType.FORZA -> Screen.StrengthExercise.createRoute(sessionId, exerciseId)
                    }
                    navController.navigate(route)
                },
                onNavigateToSuperset = { sessionId, exerciseIds ->
                    navController.navigate(Screen.Superset.createRoute(sessionId, exerciseIds))
                },
                onBack = { navController.popBackStack() },
                onSessionRegistered = { sessionId, date ->
                    navController.navigate(Screen.SessionProgress.createRoute(sessionId, date, justCompleted = true)) {
                        // Replace the active-routine screen so back from the summary goes to Home,
                        // not back into the just-finished session.
                        popUpTo(Screen.Main.route) { inclusive = false }
                    }
                },
                viewModel = viewModel,
            )
        }

        composable(
            route = Screen.StrengthExercise.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("exerciseId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            val exerciseId = backStackEntry.arguments?.getString("exerciseId") ?: ""
            val viewModel: com.mygymapp.ui.screen.strengthexercise.StrengthExerciseViewModel = hiltViewModel()

            // Observe the filtered picker's result (see ExercisePicker composable below) and
            // apply the switch on this screen's own VM before re-navigating.
            val pickedId = backStackEntry.savedStateHandle.get<String>("pickedExerciseId")
            LaunchedEffect(pickedId) {
                if (pickedId != null) {
                    viewModel.switchExercise(pickedId)
                    backStackEntry.savedStateHandle.remove<String>("pickedExerciseId")
                }
            }

            StrengthExerciseScreen(
                onBack = { navController.popBackStack() },
                onComplete = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("completedExerciseId", exerciseId)
                    navController.popBackStack()
                },
                onSwitchExercise = { bodypart, type, excludeIds ->
                    navController.navigate(Screen.ExercisePicker.createRoute(bodypart, type, excludeIds))
                },
                onSwitched = { newExerciseId ->
                    // Tell the ActiveRoutine screen (previous back-stack entry) about the swap
                    // before re-navigating — its _uiState.exercises was built once at session
                    // start and won't otherwise learn the slot's exerciseId changed.
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("switchedExerciseIds", "$exerciseId,$newExerciseId")
                    // Re-navigate to the same screen with the new exerciseId: init{} loads all
                    // state (history, rep range, name) one-shot from SavedStateHandle, so a
                    // fresh VM instance is simpler and safer than mutating state in place.
                    navController.navigate(Screen.StrengthExercise.createRoute(sessionId, newExerciseId)) {
                        popUpTo(Screen.StrengthExercise.createRoute(sessionId, exerciseId)) { inclusive = true }
                    }
                },
                viewModel = viewModel,
            )
        }

        composable(
            route = Screen.StretchExercise.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("exerciseId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            val exerciseId = backStackEntry.arguments?.getString("exerciseId") ?: ""
            val viewModel: com.mygymapp.ui.screen.stretchexercise.StretchExerciseViewModel = hiltViewModel()

            val pickedId = backStackEntry.savedStateHandle.get<String>("pickedExerciseId")
            LaunchedEffect(pickedId) {
                if (pickedId != null) {
                    viewModel.switchExercise(pickedId)
                    backStackEntry.savedStateHandle.remove<String>("pickedExerciseId")
                }
            }

            StretchExerciseScreen(
                onBack = { navController.popBackStack() },
                onComplete = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("completedExerciseId", exerciseId)
                    navController.popBackStack()
                },
                onSwitchExercise = { bodypart, type, excludeIds ->
                    navController.navigate(Screen.ExercisePicker.createRoute(bodypart, type, excludeIds))
                },
                onSwitched = { newExerciseId ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("switchedExerciseIds", "$exerciseId,$newExerciseId")
                    navController.navigate(Screen.StretchExercise.createRoute(sessionId, newExerciseId)) {
                        popUpTo(Screen.StretchExercise.createRoute(sessionId, exerciseId)) { inclusive = true }
                    }
                },
                viewModel = viewModel,
            )
        }

        composable(
            route = Screen.CardioExercise.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("exerciseId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val exerciseId = backStackEntry.arguments?.getString("exerciseId") ?: ""
            CardioExerciseScreen(
                onBack = { navController.popBackStack() },
                onComplete = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("completedExerciseId", exerciseId)
                    navController.popBackStack()
                },
            )
        }

        composable(
            route = Screen.Superset.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("exerciseIds") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            val exerciseIds = (backStackEntry.arguments?.getString("exerciseIds") ?: "")
                .split(",").filter { it.isNotBlank() }
            val viewModel: com.mygymapp.ui.screen.superset.SupersetViewModel = hiltViewModel()

            // A member's picker result is stored under its own 1-based key ("pickedExerciseIdSideN",
            // set by the picker composable below, keyed on the member the picker was opened for)
            // so a switch on one member can never be misapplied to another member's slot.
            exerciseIds.indices.forEach { i ->
                val key = "pickedExerciseIdSide${i + 1}"
                val picked = backStackEntry.savedStateHandle.get<String>(key)
                LaunchedEffect(picked) {
                    if (picked != null) {
                        viewModel.switchExerciseAt(i, picked)
                        backStackEntry.savedStateHandle.remove<String>(key)
                    }
                }
            }

            SupersetScreen(
                onComplete = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("completedSupersetIds", exerciseIds.joinToString(","))
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                onSwitchExercise = { bodypart, type, excludeIds, side ->
                    navController.navigate(
                        Screen.ExercisePicker.createRoute(bodypart, type, excludeIds, resultKeySide = side)
                    )
                },
                onSwitched = onSwitched@{ side, newExerciseId ->
                    // Tell the ActiveRoutine screen about the swap — same reasoning as the
                    // Strength/Stretch onSwitched above. `side` is 1-based.
                    val memberIndex = side - 1
                    val oldExerciseId = exerciseIds.getOrNull(memberIndex) ?: return@onSwitched
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("switchedExerciseIds", "$oldExerciseId,$newExerciseId")
                    // Re-navigate to the same Superset route with only the switched member's id
                    // replaced — the other members' ids (and thus their own VM state) are unaffected.
                    val newIds = exerciseIds.toMutableList().also { it[memberIndex] = newExerciseId }
                    navController.navigate(
                        Screen.Superset.createRoute(sessionId, newIds)
                    ) {
                        popUpTo(Screen.Superset.createRoute(sessionId, exerciseIds)) { inclusive = true }
                    }
                },
                viewModel = viewModel,
            )
        }

        composable(Screen.HeartRate.route) {
            HeartRateScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.ScaleDebug.route) {
            com.mygymapp.ui.screen.scale.ScaleDebugScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.PolarDebug.route) {
            com.mygymapp.ui.screen.polar.PolarDebugScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.SessionSummaryPreview.route) {
            com.mygymapp.ui.screen.sessionprogress.SessionSummaryPreviewScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Options.route) {
            OptionsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToScaleDebug = { navController.navigate(Screen.ScaleDebug.route) },
                onNavigateToPolarDebug = { navController.navigate(Screen.PolarDebug.route) },
                onNavigateToSummaryPreview = { navController.navigate(Screen.SessionSummaryPreview.route) },
            )
        }

        composable(
            route = Screen.ExercisePicker.route,
            arguments = listOf(
                navArgument("bodypart") { type = NavType.StringType; defaultValue = "" },
                navArgument("type") { type = NavType.StringType; defaultValue = "" },
                navArgument("excludeIds") { type = NavType.StringType; defaultValue = "" },
                navArgument("resultKeySide") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { backStackEntry ->
            // Which key to write the result under: superset sides use their own key so a
            // switch on side 1 never gets applied to side 2's slot (see Superset composable
            // above); everything else (plain RoutineEdit picker, Strength/Stretch switch)
            // uses the shared "pickedExerciseId" key.
            val resultKeySide = backStackEntry.arguments?.getInt("resultKeySide") ?: 0
            val resultKey = when (resultKeySide) {
                in 1..MAX_SUPERSET_SIZE -> "pickedExerciseIdSide$resultKeySide"
                else -> "pickedExerciseId"
            }
            ExerciseListScreen(
                pickerMode = true,
                onNavigateToEdit = {},
                onNavigateToNew = {},
                onExercisePicked = { exerciseId ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(resultKey, exerciseId)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.SessionProgress.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("date") { type = NavType.StringType },
                navArgument("justCompleted") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { backStackEntry ->
            val justCompleted = backStackEntry.arguments?.getBoolean("justCompleted") ?: false
            SessionProgressScreen(
                justCompleted = justCompleted,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack(Screen.Main.route, inclusive = false) },
                onNavigateHome = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                    }
                },
            )
        }
    }
}
