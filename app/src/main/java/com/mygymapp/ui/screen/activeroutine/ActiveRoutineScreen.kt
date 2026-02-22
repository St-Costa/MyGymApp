package com.mygymapp.ui.screen.activeroutine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.components.AutoSaveTextField
import com.mygymapp.ui.components.TonnageLineChart
import com.mygymapp.ui.theme.ForzaColor
import com.mygymapp.ui.theme.GitgraphGreen
import com.mygymapp.ui.theme.GitgraphRed
import com.mygymapp.ui.theme.StretchColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveRoutineScreen(
    onNavigateToExercise: (sessionId: String, exerciseId: String, isStretch: Boolean) -> Unit,
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
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = { viewModel.registerRoutine() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("Registra routine")
                }
            }
        },
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
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
                        maxLines = 4,
                    )
                }

                // Exercises
                itemsIndexed(
                    items = uiState.exercises,
                    key = { _, ex -> ex.exerciseId },
                ) { _, exercise ->
                    ExerciseRow(
                        exercise = exercise,
                        onClick = {
                            if (!exercise.completed) {
                                onNavigateToExercise(
                                    uiState.sessionId,
                                    exercise.exerciseId,
                                    exercise.type == ExerciseType.STRETCH,
                                )
                            }
                        },
                    )
                }

                // Progress section (shown when all completed)
                if (uiState.allCompleted) {
                    item(key = "progress") {
                        ProgressSection(
                            totalTonnage = uiState.totalTonnage,
                            previousTonnage = uiState.previousTonnage,
                            sessionTonnage = uiState.sessionTonnage,
                            sessionTonnageByBodypart = uiState.sessionTonnageByBodypart,
                            sessionLabels = uiState.sessionLabels,
                            selectedFilter = uiState.selectedChartFilter,
                            isLoadingChart = uiState.isLoadingChart,
                            onFilterSelected = { viewModel.selectChartFilter(it) },
                        )
                    }
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
    val borderColor = when (exercise.type) {
        ExerciseType.FORZA -> ForzaColor
        ExerciseType.STRETCH -> StretchColor
    }

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
            }
        }
    }
}

@Composable
private fun ProgressSection(
    totalTonnage: Double,
    previousTonnage: Double?,
    sessionTonnage: List<Double>,
    sessionTonnageByBodypart: Map<String, List<Double>>,
    sessionLabels: List<String>,
    selectedFilter: String,
    isLoadingChart: Boolean,
    onFilterSelected: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
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
                // Filter chips
                val filters = listOf("Totale") + sessionTonnageByBodypart.keys.toList()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters) { filter ->
                        FilterChip(
                            selected = filter == selectedFilter,
                            onClick = { onFilterSelected(filter) },
                            label = { Text(filter) },
                        )
                    }
                }

                // Chart title: current session's tonnage for the selected filter
                val chartData = if (selectedFilter == "Totale") {
                    sessionTonnage
                } else {
                    sessionTonnageByBodypart[selectedFilter] ?: sessionTonnage
                }
                val currentValue = chartData.lastOrNull() ?: 0.0
                Text(
                    text = "$selectedFilter: %.1f kg".format(currentValue),
                    style = MaterialTheme.typography.titleMedium,
                )

                TonnageLineChart(
                    data = chartData,
                    labels = sessionLabels,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
