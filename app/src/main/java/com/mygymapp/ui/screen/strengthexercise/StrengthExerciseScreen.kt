package com.mygymapp.ui.screen.strengthexercise

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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.components.AutoSaveTextField
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.HeartRateBar
import com.mygymapp.ui.components.MediaPreview
import com.mygymapp.ui.components.ScrollPickerInput
import com.mygymapp.ui.theme.SkippedColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrengthExerciseScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    onSwitchExercise: (bodypart: String, type: String, excludeIds: Set<String>) -> Unit = { _, _, _ -> },
    onSwitched: (newExerciseId: String) -> Unit = {},
    viewModel: StrengthExerciseViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val completionSaved by viewModel.completionSaved.collectAsState()
    LaunchedEffect(completionSaved) {
        if (completionSaved) onComplete()
    }
    val switchedExerciseId by viewModel.switchedExerciseId.collectAsState()
    LaunchedEffect(switchedExerciseId) {
        switchedExerciseId?.let { onSwitched(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.exercise?.name ?: "Exercise") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.switchEligible) {
                        IconButton(onClick = {
                            val exercise = uiState.exercise ?: return@IconButton
                            onSwitchExercise(
                                exercise.bodypart,
                                exercise.type.toFileString(),
                                uiState.excludeIds,
                            )
                        }) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = "Switch exercise")
                        }
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Media (image or YouTube thumbnail)
                MediaPreview(link = uiState.exercise?.link ?: "")

                // Description (auto-save)
                AutoSaveTextField(
                    value = uiState.description,
                    onValueChange = viewModel::updateDescription,
                    onSave = viewModel::saveDescription,
                    label = "Description",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                // Heart rate + recovery semaphore
                HeartRateBar()

                // Sets header
                val repRangeText = remember(uiState.repRangeMin, uiState.repRangeMax) {
                    if (uiState.repRangeMin > 0 && uiState.repRangeMax > 0) {
                        "${uiState.repRangeMin} - ${uiState.repRangeMax}"
                    } else "Reps"
                }

                // Bodyweight exercises carry no user-entered weight — the load is estimated from
                // body weight at completion (see StrengthExerciseViewModel.buildStrengthSets).
                // The Kg column and per-set weight picker are hidden entirely.
                val isBodyweight = uiState.exercise?.isBodyweight == true

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        repRangeText,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    if (!isBodyweight) {
                        Text(
                            "Kg",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }

                HorizontalDivider()

                // All-time PR (best single set by tonnage), e.g. "12 x 80"
                uiState.tonnagePr?.let { pr ->
                    Text(
                        text = "PR: ${pr.reps} x ${formatWeight(pr.weight)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Set rows
                uiState.sets.forEachIndexed { index, set ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Reps picker (tap only, no scroll)
                        ScrollPickerInput(
                            value = set.reps,
                            onValueChange = { viewModel.updateReps(index, it.toInt()) },
                            buttonStep = 1.0,
                            isModified = set.repsModified,
                            enableScroll = false,
                            onConfirm = { viewModel.confirmReps(index) },
                            modifier = Modifier.weight(1f),
                        )

                        // Weight picker — hidden for bodyweight exercises (load is estimated).
                        if (!isBodyweight) {
                            // Weight picker (tap + long-press +10/-10 per second, no scroll)
                            ScrollPickerInput(
                                value = set.weight,
                                onValueChange = { viewModel.updateWeight(index, it.toDouble()) },
                                buttonStep = 1.0,
                                isDecimal = true,
                                isModified = set.weightModified,
                                enableScroll = false,
                                longPressRepeatStep = 10.0,
                                onConfirm = { viewModel.confirmWeight(index) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (index < uiState.sets.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Complete button — its label reflects what tapping it will record: an exercise
                // where no reps/weight value was touched closes as "skipped" (grey border + X
                // in the active list, excluded from tonnage), one where any value was touched
                // closes as done. See StrengthExerciseViewModel.completeExercise().
                val anyTouched = uiState.sets.any { it.repsTouched || it.weightTouched }
                Button(
                    onClick = { viewModel.completeExercise() },
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
                    Text(if (anyTouched) "Completa esercizio" else "Segna come non eseguito")
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

/** "80" for whole kilos, "82.5" for fractional — avoids a redundant ".0". */
private fun formatWeight(weight: Double): String =
    if (weight == weight.toLong().toDouble()) weight.toLong().toString() else weight.toString()
