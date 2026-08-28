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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.ui.graphics.Color
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
import com.mygymapp.ui.theme.SkippedColor
import com.mygymapp.ui.theme.StretchColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupersetScreen(
    onComplete: () -> Unit,
    onBack: () -> Unit,
    // `side` is the 1-based position of the chain member (1..3).
    onSwitchExercise: (bodypart: String, type: String, excludeIds: Set<String>, side: Int) -> Unit =
        { _, _, _, _ -> },
    onSwitched: (side: Int, newExerciseId: String) -> Unit = { _, _ -> },
    viewModel: SupersetViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val completionSaved by viewModel.completionSaved.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(completionSaved) {
        if (completionSaved) onComplete()
    }
    val switchedMember by viewModel.switchedMember.collectAsState()
    LaunchedEffect(switchedMember) {
        switchedMember?.let { (index, id) -> onSwitched(index + 1, id) }
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

                // Header showing exercise names, "+"-separated
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    uiState.members.forEachIndexed { index, member ->
                        if (index > 0) {
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                        ExerciseLabel(
                            name = member.exercise.name,
                            type = member.exercise.type,
                            modifier = Modifier.weight(1f),
                            switchEligible = member.switchEligible,
                            onSwitch = {
                                onSwitchExercise(
                                    member.exercise.bodypart,
                                    member.exercise.type.toFileString(),
                                    uiState.excludeIds,
                                    index + 1,
                                )
                            },
                        )
                    }
                }

                // Exercise info cards (media + notes)
                uiState.members.forEachIndexed { index, member ->
                    ExerciseInfoCard(
                        exercise = member.exercise,
                        description = member.description,
                        onDescriptionChange = { viewModel.updateDescriptionAt(index, it) },
                        onDescriptionSave = { viewModel.saveDescriptionAt(index, it) },
                    )
                }

                // Heart rate + recovery semaphore
                HeartRateBar()

                // Single stopwatch shown once if at least one exercise is STRETCH
                val hasStretch = uiState.members.any { it.exercise.type == ExerciseType.STRETCH }
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

                // Interleaved set items grouped per round (one card per superset round)
                val chainSize = uiState.members.size.coerceAtLeast(1)
                uiState.sets.chunked(chainSize).forEachIndexed { roundIndex, roundSets ->
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
                                val listIndex = roundIndex * chainSize + localIndex
                                val member = uiState.members.getOrNull(setUi.exerciseIndex)
                                SupersetSetItem(
                                    setUi = setUi,
                                    repRangeMin = member?.repRangeMin ?: 0,
                                    repRangeMax = member?.repRangeMax ?: 0,
                                    prReps = member?.prReps ?: 0,
                                    prWeight = member?.prWeight ?: 0.0,
                                    isBodyweight = member?.exercise?.isBodyweight == true,
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

                // Each member closes independently: one with no touched value closes as
                // "skipped" (grey + X, excluded from tonnage), one with any touched value as
                // done. The label reflects the all-untouched case — the whole chain skipped.
                val anyTouched = uiState.sets.any {
                    it.repsTouched || it.weightTouched ||
                        (it.exerciseType == ExerciseType.STRETCH && it.done)
                }
                Button(
                    onClick = { viewModel.completeSuperset() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (anyTouched) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = SkippedColor,
                            contentColor = Color(0xFF1E1E1E),
                        )
                    },
                ) {
                    Text(if (anyTouched) "Completa superset" else "Segna come non eseguito")
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
    switchEligible: Boolean = false,
    onSwitch: () -> Unit = {},
) {
    val color = when (type) {
        ExerciseType.FORZA -> ForzaColor
        ExerciseType.STRETCH -> StretchColor
        // Cardio exercises can never be superset members — see SupersetViewModel.
        ExerciseType.CARDIO -> error("Cardio exercises cannot be superset members")
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            modifier = Modifier.weight(1f, fill = false),
        )
        // "Switch exercise" — each side of a superset is an independent slot (docs/CONVENTIONS.md#switch-exercise).
        if (switchEligible) {
            IconButton(onClick = onSwitch, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Filled.SwapHoriz,
                    contentDescription = "Switch exercise",
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SupersetSetItem(
    setUi: SupersetSetUi,
    repRangeMin: Int,
    repRangeMax: Int,
    prReps: Int = 0,
    prWeight: Double = 0.0,
    // This side's exercise is bodyweight — hide the Kg column / weight picker (load is
    // estimated from body weight at completion, see SupersetViewModel.buildUpdatedSession).
    isBodyweight: Boolean = false,
    onUpdateReps: (Int) -> Unit,
    onUpdateWeight: (Double) -> Unit,
    onToggleDone: () -> Unit,
    onConfirmReps: () -> Unit = {},
    onConfirmWeight: () -> Unit = {},
) {
    val borderColor = when (setUi.exerciseType) {
        ExerciseType.FORZA -> ForzaColor
        ExerciseType.STRETCH -> StretchColor
        ExerciseType.CARDIO -> error("Cardio exercises cannot be superset members")
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

            // PR badge: heaviest set ever logged for this exercise, shown once above its first set
            if (setUi.exerciseType == ExerciseType.FORZA && setUi.setIndex == 0 && prWeight > 0 && !isBodyweight) {
                val prWeightText = remember(prWeight) {
                    if (prWeight == prWeight.toLong().toDouble()) prWeight.toLong().toString() else "%.1f".format(prWeight)
                }
                Text(
                    text = "PR ${prReps}x${prWeightText}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

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
                        if (!isBodyweight) {
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

                ExerciseType.CARDIO -> error("Cardio exercises cannot be superset members")
            }
        }
    }
}
