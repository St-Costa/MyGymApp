package com.mygymapp.ui.screen.cardioexercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.HeartRateBar
import com.mygymapp.ui.components.HrZoneTraceChart

/**
 * The single action button:
 * IDLE ("Inizia cardio") -> RUNNING ("Termina cardio"), which closes the block, marks the
 * exercise completed and exits in one step — no intermediate phase. The DONE state
 * ("Completa esercizio") only shows on re-entry when a block was left open by a process
 * kill and closed on load (see CardioExerciseViewModel.init) — normal flow never hits it.
 * See CardioExerciseViewModel's startBlock()/stopBlock()/completeExercise().
 */
private enum class CardioButtonState { IDLE, RUNNING, DONE }

private fun CardioExerciseUiState.buttonState(): CardioButtonState = when {
    isBlockRunning -> CardioButtonState.RUNNING
    completedBlocks.isNotEmpty() -> CardioButtonState.DONE
    else -> CardioButtonState.IDLE
}

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
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Keep the top and bottom controls in fixed positions. The chart receives all
                // remaining space below the HR bar and above the timer/button, so its height
                // adapts to the device without ever pushing either control out of the screen.
                HeartRateBar()

                HrZoneTraceChart(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

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

                // Single action button: IDLE -> RUNNING, and "Termina cardio" both ends the
                // block and completes the exercise (see buttonState() / stopBlock()).
                val buttonState = uiState.buttonState()
                Button(
                    onClick = {
                        when (buttonState) {
                            CardioButtonState.IDLE -> viewModel.startBlock()
                            CardioButtonState.RUNNING -> viewModel.stopBlock()
                            CardioButtonState.DONE -> viewModel.completeExercise()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (buttonState) {
                            CardioButtonState.IDLE -> MaterialTheme.colorScheme.secondary
                            CardioButtonState.RUNNING -> MaterialTheme.colorScheme.error
                            CardioButtonState.DONE -> MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    Text(
                        when (buttonState) {
                            CardioButtonState.IDLE -> "Inizia cardio"
                            CardioButtonState.RUNNING -> "Termina cardio"
                            CardioButtonState.DONE -> "Completa esercizio"
                        }
                    )
                }
            }
        }
    }
}
