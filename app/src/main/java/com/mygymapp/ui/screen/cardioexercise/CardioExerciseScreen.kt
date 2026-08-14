package com.mygymapp.ui.screen.cardioexercise

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.HeartRateBar
import com.mygymapp.ui.components.HrZoneTraceChart
import com.mygymapp.ui.components.MediaPreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardioExerciseScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: CardioExerciseViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val completionSaved by viewModel.completionSaved.collectAsState()
    LaunchedEffect(completionSaved) {
        if (completionSaved) onComplete()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.exercise?.name ?: "Cardio") },
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

                MediaPreview(link = uiState.exercise?.link ?: "")

                // Heart rate (live, sourced from PolarManager same as every other exercise
                // screen), plus the zone-trace chart — cardio is where zone-holding matters
                HeartRateBar()

                HrZoneTraceChart()

                // Countdown from the configured block duration (RoutineEditScreen) — keeps
                // going negative (overtime) rather than auto-stopping at zero; the user must
                // tap "Termina cardio" explicitly.
                val remaining = uiState.remainingSeconds
                val isOvertime = remaining < 0
                val formatted = remember(remaining) {
                    val abs = kotlin.math.abs(remaining)
                    val sign = if (isOvertime) "+" else ""
                    "$sign%02d:%02d".format(abs / 60, abs % 60)
                }
                Text(
                    text = formatted,
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                    color = when {
                        isOvertime -> MaterialTheme.colorScheme.error
                        uiState.isBlockRunning -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (uiState.configuredDurationSeconds == 0 && !uiState.isBlockRunning) {
                    Text(
                        text = "Nessuna durata impostata per questo esercizio (routine)",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                uiState.liveHr?.let { hr ->
                    Text(
                        text = "$hr BPM",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Button(
                    onClick = {
                        if (uiState.isBlockRunning) viewModel.stopBlock() else viewModel.startBlock()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isBlockRunning)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Text(if (uiState.isBlockRunning) "Termina cardio" else "Inizia cardio")
                }

                // Completed blocks this session
                if (uiState.completedBlocks.isNotEmpty()) {
                    Text("Blocchi completati", style = MaterialTheme.typography.titleLarge)
                    uiState.completedBlocks.forEachIndexed { index, block ->
                        CardioBlockRow(block)
                        if (index < uiState.completedBlocks.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }

                // History across past sessions for this exercise
                if (uiState.history.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Storico", style = MaterialTheme.typography.titleLarge)
                    uiState.history.forEach { entry ->
                        CardioHistoryRow(entry)
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

@Composable
private fun CardioBlockRow(block: ExerciseSet.Cardio) {
    val start = runCatching { java.time.LocalDateTime.parse(block.startedAt) }.getOrNull()
    val end = runCatching { java.time.LocalDateTime.parse(block.endedAt) }.getOrNull()
    val seconds = if (start != null && end != null) {
        java.time.Duration.between(start, end).seconds.toInt().coerceAtLeast(0)
    } else 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "%02d:%02d".format(seconds / 60, seconds % 60),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "media ${block.avgHr} · max ${block.maxHr} BPM",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CardioHistoryRow(entry: CardioHistoryEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(entry.date, style = MaterialTheme.typography.bodyMedium)
        Text(
            "%02d:%02d · media %d BPM".format(
                entry.durationSeconds / 60, entry.durationSeconds % 60, entry.avgHr
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
