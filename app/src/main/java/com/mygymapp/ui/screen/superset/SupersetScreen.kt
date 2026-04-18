package com.mygymapp.ui.screen.superset

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.components.AutoSaveTextField
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.HeartRateBar
import com.mygymapp.ui.components.MediaPreview
import com.mygymapp.ui.components.ScrollPickerInput
import com.mygymapp.ui.service.StopwatchService
import com.mygymapp.ui.theme.ForzaColor
import com.mygymapp.ui.theme.StretchColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupersetScreen(
    onComplete: () -> Unit,
    onBack: () -> Unit,
    viewModel: SupersetViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val completionSaved by viewModel.completionSaved.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(completionSaved) {
        if (completionSaved) onComplete()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Superset") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            FullscreenLoading(padding)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Header showing exercise names
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExerciseLabel(
                        name = uiState.exercise1?.name ?: "",
                        type = uiState.exercise1?.type ?: ExerciseType.FORZA,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    ExerciseLabel(
                        name = uiState.exercise2?.name ?: "",
                        type = uiState.exercise2?.type ?: ExerciseType.FORZA,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Exercise info cards (media + notes)
                uiState.exercise1?.let { ex ->
                    ExerciseInfoCard(
                        exercise = ex,
                        description = uiState.description1,
                        onDescriptionChange = viewModel::updateDescription1,
                        onDescriptionSave = viewModel::saveDescription1,
                    )
                }
                uiState.exercise2?.let { ex ->
                    ExerciseInfoCard(
                        exercise = ex,
                        description = uiState.description2,
                        onDescriptionChange = viewModel::updateDescription2,
                        onDescriptionSave = viewModel::saveDescription2,
                    )
                }

                // Heart rate + recovery semaphore
                HeartRateBar()

                // Single stopwatch shown once if at least one exercise is STRETCH
                val hasStretch = uiState.exercise1?.type == ExerciseType.STRETCH
                    || uiState.exercise2?.type == ExerciseType.STRETCH
                if (hasStretch) {
                    val formatted = remember(uiState.elapsedSeconds) {
                        "%02d:%02d".format(uiState.elapsedSeconds / 60, uiState.elapsedSeconds % 60)
                    }
                    Text(
                        text = formatted,
                        style = MaterialTheme.typography.displaySmall,
                        textAlign = TextAlign.Center,
                        color = if (uiState.isStopwatchRunning)
                            MaterialTheme.colorScheme.secondary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val wasRunning = uiState.isStopwatchRunning
                            viewModel.toggleStopwatch()
                            val svcIntent = Intent(context, StopwatchService::class.java).apply {
                                action = if (wasRunning) StopwatchService.ACTION_STOP else StopwatchService.ACTION_START
                            }
                            if (wasRunning) context.startService(svcIntent) else context.startForegroundService(svcIntent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isStopwatchRunning)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.secondary,
                        ),
                    ) {
                        Text(if (uiState.isStopwatchRunning) "Stop Stopwatch" else "Start Stopwatch")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.primary, thickness = 1.dp)

                // Interleaved set items grouped in pairs (one card per superset round)
                uiState.sets.chunked(2).forEachIndexed { roundIndex, roundSets ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            roundSets.forEachIndexed { localIndex, setUi ->
                                val listIndex = roundIndex * 2 + localIndex
                                SupersetSetItem(
                                    setUi = setUi,
                                    repRangeMin = if (setUi.exerciseIndex == 0) uiState.repRangeMin1 else uiState.repRangeMin2,
                                    repRangeMax = if (setUi.exerciseIndex == 0) uiState.repRangeMax1 else uiState.repRangeMax2,
                                    onUpdateReps = { viewModel.updateReps(listIndex, it) },
                                    onUpdateWeight = { viewModel.updateWeight(listIndex, it) },
                                    onToggleDone = { viewModel.toggleSetDone(listIndex) },
                                    onConfirmReps = { viewModel.confirmReps(listIndex) },
                                    onConfirmWeight = { viewModel.confirmWeight(listIndex) },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { viewModel.completeSuperset() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Complete Superset")
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun ExerciseInfoCard(
    exercise: Exercise,
    description: String,
    onDescriptionChange: (String) -> Unit,
    onDescriptionSave: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = exercise.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (exercise.link.isNotBlank()) {
                MediaPreview(link = exercise.link)
            }
            AutoSaveTextField(
                value = description,
                onValueChange = onDescriptionChange,
                onSave = onDescriptionSave,
                label = "Description",
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }
    }
}

@Composable
private fun ExerciseLabel(
    name: String,
    type: ExerciseType,
    modifier: Modifier = Modifier,
) {
    val color = when (type) {
        ExerciseType.FORZA -> ForzaColor
        ExerciseType.STRETCH -> StretchColor
    }
    Text(
        text = name,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun SupersetSetItem(
    setUi: SupersetSetUi,
    repRangeMin: Int,
    repRangeMax: Int,
    onUpdateReps: (Int) -> Unit,
    onUpdateWeight: (Double) -> Unit,
    onToggleDone: () -> Unit,
    onConfirmReps: () -> Unit = {},
    onConfirmWeight: () -> Unit = {},
) {
    val borderColor = when (setUi.exerciseType) {
        ExerciseType.FORZA -> ForzaColor
        ExerciseType.STRETCH -> StretchColor
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header: exercise name only
            Text(
                text = setUi.exerciseName,
                style = MaterialTheme.typography.titleSmall,
                color = borderColor,
                modifier = Modifier.fillMaxWidth(),
            )

            when (setUi.exerciseType) {
                ExerciseType.FORZA -> {
                    val rangeLabel = if (repRangeMin > 0 && repRangeMax > 0) {
                        "$repRangeMin–$repRangeMax"
                    } else "Reps"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = rangeLabel,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                            ScrollPickerInput(
                                value = setUi.reps,
                                onValueChange = { onUpdateReps(it.toInt()) },
                                buttonStep = 1.0,
                                isModified = setUi.repsModified,
                                enableScroll = false,
                                onConfirm = onConfirmReps,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Kg",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                            ScrollPickerInput(
                                value = setUi.weight,
                                onValueChange = { onUpdateWeight(it.toDouble()) },
                                buttonStep = 1.0,
                                isDecimal = true,
                                isModified = setUi.weightModified,
                                enableScroll = false,
                                longPressRepeatStep = 10.0,
                                onConfirm = onConfirmWeight,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                ExerciseType.STRETCH -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${setUi.timeSeconds}s",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Checkbox(
                            checked = setUi.done,
                            onCheckedChange = { if (!setUi.done) onToggleDone() },
                        )
                    }
                }
            }
        }
    }
}
