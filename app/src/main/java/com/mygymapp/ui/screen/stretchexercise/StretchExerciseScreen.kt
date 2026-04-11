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
import com.mygymapp.ui.components.AutoSaveTextField
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.MediaPreview
import com.mygymapp.ui.service.StopwatchService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StretchExerciseScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: StretchExerciseViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val completionSaved by viewModel.completionSaved.collectAsState()
    LaunchedEffect(completionSaved) {
        if (completionSaved) onComplete()
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

                // Description
                AutoSaveTextField(
                    value = uiState.description,
                    onValueChange = viewModel::updateDescription,
                    onSave = viewModel::saveDescription,
                    label = "Description",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                // Stopwatch timer display
                val elapsed = uiState.elapsedSeconds
                val formatted = remember(elapsed) { "%02d:%02d".format(elapsed / 60, elapsed % 60) }
                Text(
                    text = formatted,
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                    color = if (uiState.isStopwatchRunning)
                        MaterialTheme.colorScheme.secondary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Stopwatch button
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
                        }
                        if (wasRunning) {
                            context.startService(intent)
                        } else {
                            context.startForegroundService(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isStopwatchRunning)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Text(
                        if (uiState.isStopwatchRunning) "Stop Stopwatch" else "Start Stopwatch"
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

                Button(
                    onClick = { viewModel.completeExercise() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Complete Exercise")
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}
