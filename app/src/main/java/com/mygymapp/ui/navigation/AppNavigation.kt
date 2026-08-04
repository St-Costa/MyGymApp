package com.mygymapp.ui.navigation

import androidx.compose.runtime.Composable
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
import com.mygymapp.ui.screen.weekview.WeekViewScreen
import com.mygymapp.ui.screen.activeroutine.ActiveRoutineScreen
import com.mygymapp.ui.screen.strengthexercise.StrengthExerciseScreen
import com.mygymapp.ui.screen.stretchexercise.StretchExerciseScreen
import com.mygymapp.ui.screen.superset.SupersetScreen
import com.mygymapp.ui.screen.sessionprogress.SessionProgressScreen
import com.mygymapp.ui.screen.heartrate.HeartRateScreen
import com.mygymapp.ui.screen.options.OptionsScreen

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Main.route,
    ) {
        composable(Screen.Main.route) {
            MainScreen(
                onNavigateToWeekView = { navController.navigate(Screen.WeekView.route) },
                onNavigateToExercises = { navController.navigate(Screen.ExerciseList.route) },
                onNavigateToRoutines = { navController.navigate(Screen.RoutineList.route) },
                onNavigateToHeartRate = { navController.navigate(Screen.HeartRate.route) },
                onNavigateToOptions = { navController.navigate(Screen.Options.route) },
                onNavigateToSessionProgress = { sessionId, date ->
                    navController.navigate(Screen.SessionProgress.createRoute(sessionId, date))
                },
            )
        }

        composable(Screen.WeekView.route) {
            WeekViewScreen(
                onNavigateToRoutine = { routineId ->
                    navController.navigate(Screen.ActiveRoutine.createRoute(routineId))
                },
                onBack = { navController.popBackStack() },
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
                onPickExercise = { navController.navigate(Screen.ExercisePicker.route) },
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

            // Observe superset completion result (comma-separated exerciseId1,exerciseId2)
            val completedSupersetIds = backStackEntry.savedStateHandle.get<String>("completedSupersetIds")
            LaunchedEffect(completedSupersetIds) {
                if (completedSupersetIds != null) {
                    completedSupersetIds.split(",").forEach { id ->
                        viewModel.markExerciseCompleted(id)
                    }
                    backStackEntry.savedStateHandle.remove<String>("completedSupersetIds")
                }
            }

            ActiveRoutineScreen(
                onNavigateToExercise = { sessionId, exerciseId, isStretch ->
                    val route = if (isStretch) {
                        Screen.StretchExercise.createRoute(sessionId, exerciseId)
                    } else {
                        Screen.StrengthExercise.createRoute(sessionId, exerciseId)
                    }
                    navController.navigate(route)
                },
                onNavigateToSuperset = { sessionId, exerciseId1, exerciseId2 ->
                    navController.navigate(Screen.Superset.createRoute(sessionId, exerciseId1, exerciseId2))
                },
                onBack = { navController.popBackStack() },
                onNavigateHome = {
                    navController.popBackStack(Screen.Main.route, inclusive = false)
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
            val exerciseId = backStackEntry.arguments?.getString("exerciseId") ?: ""
            StrengthExerciseScreen(
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
            route = Screen.StretchExercise.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("exerciseId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val exerciseId = backStackEntry.arguments?.getString("exerciseId") ?: ""
            StretchExerciseScreen(
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
                navArgument("exerciseId1") { type = NavType.StringType },
                navArgument("exerciseId2") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val exerciseId1 = backStackEntry.arguments?.getString("exerciseId1") ?: ""
            val exerciseId2 = backStackEntry.arguments?.getString("exerciseId2") ?: ""
            SupersetScreen(
                onComplete = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("completedSupersetIds", "$exerciseId1,$exerciseId2")
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.HeartRate.route) {
            HeartRateScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Options.route) {
            OptionsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.ExercisePicker.route) {
            ExerciseListScreen(
                pickerMode = true,
                onNavigateToEdit = {},
                onNavigateToNew = {},
                onExercisePicked = { exerciseId ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("pickedExerciseId", exerciseId)
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
            ),
        ) {
            SessionProgressScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                    }
                },
            )
        }
    }
}
