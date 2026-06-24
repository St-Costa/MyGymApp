package com.mygymapp.ui.screen.activeroutine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.components.AutoSaveTextField
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.HeartRateBar
import com.mygymapp.ui.components.LiveEcgCard
import com.mygymapp.ui.components.TonnageLineChart
import com.mygymapp.ui.components.trimpColor
import com.mygymapp.ui.theme.accentColor
import com.mygymapp.ui.theme.GitgraphGreen
import com.mygymapp.ui.theme.GitgraphRed
import com.mygymapp.ui.util.groupSupersets

// ---------------------------------------------------------------------------
// Exercise grouping (mirrors RoutineEditViewModel's segment model)
// ---------------------------------------------------------------------------

private sealed class ExerciseGroup {
    data class Single(val exercise: ActiveExerciseUi) : ExerciseGroup()
    data class Superset(val ex1: ActiveExerciseUi, val ex2: ActiveExerciseUi) : ExerciseGroup()
}

private fun buildExerciseGroups(exercises: List<ActiveExerciseUi>): List<ExerciseGroup> =
    groupSupersets(
        items = exercises,
        isPairedWithNext = { it.supersetWithNext },
        single = { i -> ExerciseGroup.Single(exercises[i]) },
        pair = { i, j -> ExerciseGroup.Superset(exercises[i], exercises[j]) },
    )

// A superset pair never spans a category boundary, so the first exercise's category is the group's.
private fun ExerciseGroup.category(): SessionExerciseCategory = when (this) {
    is ExerciseGroup.Single -> exercise.category
    is ExerciseGroup.Superset -> ex1.category
}

@Composable
private fun SessionSectionHeader(
    category: SessionExerciseCategory,
    modifier: Modifier = Modifier,
) {
    // Excluded sections (warmup/daily) are muted; the counted workout section is highlighted.
    val label: String
    val color: androidx.compose.ui.graphics.Color
    when (category) {
        SessionExerciseCategory.WARMUP -> {
            label = "WARMUP"; color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        }
        SessionExerciseCategory.DAILY -> {
            label = "DAILY"; color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        }
        SessionExerciseCategory.NORMAL -> {
            label = "WORKOUT"; color = MaterialTheme.colorScheme.primary
        }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(color = color.copy(alpha = 0.4f), modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveRoutineScreen(
    onNavigateToExercise: (sessionId: String, exerciseId: String, isStretch: Boolean) -> Unit,
    onNavigateToSuperset: (sessionId: String, exerciseId1: String, exerciseId2: String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    viewModel: ActiveRoutineViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.sessionRegistered) {
        if (uiState.sessionRegistered) onNavigateHome()
    }

    BackHandler {
        viewModel.abandonSession()
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.routineName.ifBlank { "Workout" }) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.abandonSession()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            FullscreenLoading(padding)
        } else {
            val groups = remember(uiState.exercises) { buildExerciseGroups(uiState.exercises) }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Notes
                item(key = "notes") {
                    AutoSaveTextField(
                        value = uiState.notes,
                        onValueChange = {},
                        onSave = { viewModel.updateNotes(it) },
                        label = "Notes",
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }

                // Exercise groups (singles and supersets), with section headers when the
                // session mixes warmup / daily / workout exercises.
                val hasSections = groups.any { it.category() != SessionExerciseCategory.NORMAL }
                itemsIndexed(
                    items = groups,
                    key = { _, group ->
                        when (group) {
                            is ExerciseGroup.Single -> group.exercise.exerciseId
                            is ExerciseGroup.Superset -> "${group.ex1.exerciseId}_${group.ex2.exerciseId}"
                        }
                    },
                ) { index, group ->
                    val showHeader = hasSections &&
                        (index == 0 || groups[index - 1].category() != group.category())
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showHeader) {
                            SessionSectionHeader(
                                category = group.category(),
                                modifier = if (index == 0) Modifier else Modifier.padding(top = 8.dp),
                            )
                        }
                        when (group) {
                            is ExerciseGroup.Single -> {
                                ExerciseRow(
                                    exercise = group.exercise,
                                    onClick = {
                                        if (!group.exercise.completed) {
                                            onNavigateToExercise(
                                                uiState.sessionId,
                                                group.exercise.exerciseId,
                                                group.exercise.type == ExerciseType.STRETCH,
                                            )
                                        }
                                    },
                                )
                            }
                            is ExerciseGroup.Superset -> {
                                val bothCompleted = group.ex1.completed && group.ex2.completed
                                SupersetGroupRow(
                                    ex1 = group.ex1,
                                    ex2 = group.ex2,
                                    onClick = {
                                        if (!bothCompleted) {
                                            onNavigateToSuperset(
                                                uiState.sessionId,
                                                group.ex1.exerciseId,
                                                group.ex2.exerciseId,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // Heart rate bar (live BPM + kcal + TRIMP + semaphore)
                item(key = "hr_bar") {
                    HeartRateBar()
                }

                // Live ECG card (between HR bar and register button, per user spec)
                item(key = "live_ecg") {
                    LiveEcgCard()
                }

                // Register button — shown before progress chart
                item(key = "register_button") {
                    Button(
                        onClick = { viewModel.registerRoutine() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 8.dp),
                    ) {
                        Text("Registra routine")
                    }
                }

                // Progress section (shown when all completed)
                if (uiState.allCompleted) {
                    item(key = "progress") {
                        ProgressSection(
                            totalTonnage = uiState.totalTonnage,
                            previousTonnage = uiState.previousTonnage,
                            sessionCalories = uiState.sessionCalories,
                            sessionTrimp = uiState.sessionTrimp,
                            vo2max = uiState.vo2max,
                            sessionTonnage = uiState.sessionTonnage,
                            sessionTonnageByBodypart = uiState.sessionTonnageByBodypart,
                            sessionLabels = uiState.sessionLabels,
                            allSessionCalories = uiState.allSessionCalories,
                            allSessionTrimp = uiState.allSessionTrimp,
                            allSessionVo2max = uiState.allSessionVo2max,
                            allSessionLabels = uiState.allSessionLabels,
                            selectedFilter = uiState.selectedChartFilter,
                            isLoadingChart = uiState.isLoadingChart,
                            onFilterSelected = { viewModel.selectChartFilter(it) },
                        )
                    }
                }
            }
        }
    }

    // Only after the session has finished loading, so the popup appears on top of
    // the loaded session and isn't flashed-then-closed while loading completes.
    if (!uiState.isLoading && uiState.isPowerliftingWeek && !uiState.powerliftingDismissed) {
        PowerliftingWeekOverlay(onDismiss = { viewModel.dismissPowerliftingOverlay() })
    }

    } // Box
}

@Composable
private fun PowerliftingWeekOverlay(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(
                    "SETTIMANA POWERLIFTING",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Button(
                    onClick = onDismiss,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("Ho capito")
                }
            }
        }
    }
}

@Composable
private fun ExerciseRow(
    exercise: ActiveExerciseUi,
    onClick: () -> Unit,
) {
    val borderColor = exercise.type.accentColor()

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left side: name + sets — dimmed when completed
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (exercise.completed) 0.4f else 1f),
            ) {
                Text(
                    text = exercise.exerciseName,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (exercise.completed) TextDecoration.LineThrough else null,
                )
                Text(
                    text = "${exercise.setCount} sets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            // Right side: progress % (bright, not dimmed) or nothing
            if (exercise.completed && exercise.tonnageChangePct != null) {
                val color = if (exercise.tonnageChangePct > 0) GitgraphGreen else GitgraphRed
                Text(
                    text = "%+.1f%%".format(exercise.tonnageChangePct),
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                )
            } else if (exercise.completed && exercise.isFirstTimeTonnage) {
                Text(
                    text = "primo dato",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun SupersetGroupRow(
    ex1: ActiveExerciseUi,
    ex2: ActiveExerciseUi,
    onClick: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val bothCompleted = ex1.completed && ex2.completed

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, primaryColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "SUPERSET",
                style = MaterialTheme.typography.labelSmall,
                color = primaryColor,
            )

            SupersetExerciseEntry(exercise = ex1)

            HorizontalDivider(color = primaryColor.copy(alpha = 0.3f), thickness = 1.dp)

            SupersetExerciseEntry(exercise = ex2)
        }
    }
}

@Composable
private fun SupersetExerciseEntry(exercise: ActiveExerciseUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left side: name + sets — dimmed when completed (mirrors ExerciseRow)
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (exercise.completed) 0.4f else 1f),
        ) {
            Text(
                text = exercise.exerciseName,
                style = MaterialTheme.typography.titleSmall,
                textDecoration = if (exercise.completed) TextDecoration.LineThrough else null,
            )
            Text(
                text = "${exercise.setCount} sets",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        // Right side: tonnage % — full brightness (not dimmed)
        if (exercise.completed && exercise.tonnageChangePct != null) {
            val color = if (exercise.tonnageChangePct > 0) GitgraphGreen else GitgraphRed
            Text(
                text = "%+.1f%%".format(exercise.tonnageChangePct),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        } else if (exercise.completed && exercise.isFirstTimeTonnage) {
            Text(
                text = "primo dato",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun ProgressSection(
    totalTonnage: Double,
    previousTonnage: Double?,
    sessionCalories: Double,
    sessionTrimp: Double,
    vo2max: Double,
    sessionTonnage: List<Double>,
    sessionTonnageByBodypart: Map<String, List<Double>>,
    sessionLabels: List<String>,
    allSessionCalories: List<Double>,
    allSessionTrimp: List<Double>,
    allSessionVo2max: List<Double>,
    allSessionLabels: List<String>,
    selectedFilter: String,
    isLoadingChart: Boolean,
    onFilterSelected: (String) -> Unit,
) {
    // Calories + TRIMP + VO2max summary
    if (sessionCalories > 0 || sessionTrimp > 0) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${sessionCalories.toInt()}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = androidx.compose.ui.graphics.Color(0xFFFF9800),
                    )
                    Text("kcal", style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${sessionTrimp.toInt()}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = trimpColor(sessionTrimp),
                    )
                    Text("TRIMP", style = MaterialTheme.typography.bodySmall)
                }
                if (vo2max > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "%.1f".format(vo2max),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text("VO2max", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isLoadingChart) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (sessionTonnage.isNotEmpty()) {
                val crossRoutineFilters = listOf("kcal", "TRIMP", "VO2max")
                // Filter chips: routine-specific tonnage + cross-routine metrics
                val filters = listOf("Totale") + sessionTonnageByBodypart.keys.toList() + crossRoutineFilters
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters) { filter ->
                        FilterChip(
                            selected = filter == selectedFilter,
                            onClick = { onFilterSelected(filter) },
                            label = { Text(filter) },
                        )
                    }
                }

                val isCrossRoutine = selectedFilter in crossRoutineFilters
                val chartData: List<Double>
                val chartLabels: List<String>
                val chartTitle: String

                when (selectedFilter) {
                    "kcal" -> {
                        chartData = allSessionCalories
                        chartLabels = allSessionLabels
                        val current = chartData.lastOrNull() ?: 0.0
                        chartTitle = "kcal: ${current.toInt()} (all routines)"
                    }
                    "TRIMP" -> {
                        chartData = allSessionTrimp
                        chartLabels = allSessionLabels
                        val current = chartData.lastOrNull() ?: 0.0
                        chartTitle = "TRIMP: ${current.toInt()} (all routines)"
                    }
                    "VO2max" -> {
                        chartData = allSessionVo2max.filter { it > 0 }
                        chartLabels = allSessionLabels.zip(allSessionVo2max)
                            .filter { it.second > 0 }.map { it.first }
                        val current = chartData.lastOrNull() ?: 0.0
                        chartTitle = "VO2max: %.1f (all routines)".format(current)
                    }
                    "Totale" -> {
                        chartData = sessionTonnage
                        chartLabels = sessionLabels
                        val current = chartData.lastOrNull() ?: 0.0
                        chartTitle = "Totale: %.1f kg".format(current)
                    }
                    else -> {
                        chartData = sessionTonnageByBodypart[selectedFilter] ?: sessionTonnage
                        chartLabels = sessionLabels
                        val current = chartData.lastOrNull() ?: 0.0
                        chartTitle = "$selectedFilter: %.1f kg".format(current)
                    }
                }

                Text(
                    text = chartTitle,
                    style = MaterialTheme.typography.titleMedium,
                )

                if (chartData.isNotEmpty()) {
                    TonnageLineChart(
                        data = chartData,
                        labels = chartLabels,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
