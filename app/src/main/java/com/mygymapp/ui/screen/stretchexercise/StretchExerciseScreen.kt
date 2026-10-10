package com.mygymapp.ui.screen.stretchexercise

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
import android.content.Intent
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.components.CollapsibleAutoSaveDescription
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.HeartRateBar
import com.mygymapp.ui.components.MediaPreview
import com.mygymapp.ui.service.StopwatchService
import com.mygymapp.ui.theme.OnSkipped
import com.mygymapp.ui.theme.SkippedColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StretchExerciseScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    onSwitchExercise: (bodypart: String, type: String, excludeIds: Set<String>) -> Unit = { _, _, _ -> },
    onSwitched: (newExerciseId: String) -> Unit = {},
    viewModel: StretchExerciseViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    // One-shot: collect the completion event, don't observe the boolean flag (a
    // LaunchedEffect(flag) can re-fire navigation on recompositions with flag=true).
    LaunchedEffect(Unit) {
        viewModel.completionEvent.collect { onComplete() }
    }
    val switchedExerciseId by viewModel.switchedExerciseId.collectAsState()
    LaunchedEffect(switchedExerciseId) {
        switchedExerciseId?.let { onSwitched(it) }
    }
    // Same mirror as the strength screen: the swap icon is offered only while no set is
    // toggled done (the button still reads "Salta").
    val anyDone = uiState.sets.any { it.done }

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
                    if (uiState.switchEligible && !anyDone) {
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

                // Description — collassata a una riga quando vuota (un tap: espandi+focus).
                CollapsibleAutoSaveDescription(
                    value = uiState.description,
                    onValueChange = viewModel::updateDescription,
                    onSave = viewModel::saveDescription,
                    label = "Description",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                // Heart rate + recovery semaphore
                HeartRateBar()

                // Countdown timer display — runs down the set duration and loops
                // automatically at zero (StopwatchService buzzes: every 30s in-loop,
                // three times fast on each loop completion). Red in the last 5s.
                val remaining = uiState.remainingSeconds
                val formatted = remember(remaining) { "%02d:%02d".format(remaining / 60, remaining % 60) }
                Text(
                    text = formatted,
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                    color = when {
                        uiState.isStopwatchRunning && remaining <= 5 -> MaterialTheme.colorScheme.error
                        uiState.isStopwatchRunning -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${uiState.countdownDuration}s · loop ${uiState.loopCount}",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Countdown button — the duration travels to StopwatchService so the
                // loop (and its buzzes) keeps running with the screen off.
                val context = LocalContext.current
                Button(
                    onClick = {
                        val wasRunning = uiState.isStopwatchRunning
                        viewModel.toggleStopwatch()
                        val intent = Intent(context, StopwatchService::class.java).apply {
                            action = if (wasRunning) {
                                StopwatchService.ACTION_STOP
                            } else {
                                StopwatchService.ACTION_START
                            }
                            if (!wasRunning) {
                                putExtra(
                                    StopwatchService.EXTRA_DURATION_SECONDS,
                                    uiState.countdownDuration,
                                )
                            }
                        }
                        if (wasRunning) {
                            context.startService(intent)
                        } else {
                            context.startForegroundService(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.85f).align(Alignment.CenterHorizontally),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isStopwatchRunning)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    com.mygymapp.ui.components.ActionButtonText(
                        if (uiState.isStopwatchRunning) "Stop" else "Start"
                    )
                }

                // Sets
                Text("Sets", style = MaterialTheme.typography.titleLarge)

                uiState.sets.forEachIndexed { index, set ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${set.timeSeconds}s",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Checkbox(
                            checked = set.done,
                            onCheckedChange = {
                                if (!set.done) viewModel.toggleSetDone(index)
                            },
                        )
                    }
                    if (index < uiState.sets.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Label reflects what tapping records: no set toggled done -> closes as
                // "skipped" (grey border + X, excluded from history); any set done -> closes
                // as performed. See StretchExerciseViewModel.completeExercise().
                // (anyDone is hoisted above — the swap icon mirrors it.)
                Button(
                    onClick = { viewModel.completeExercise() },
                    modifier = Modifier.fillMaxWidth(0.85f).align(Alignment.CenterHorizontally),
                    colors = if (anyDone) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = SkippedColor,
                            contentColor = OnSkipped,
                        )
                    },
                ) {
                    com.mygymapp.ui.components.ActionButtonText(if (anyDone) "Complete" else "Skip")
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}
