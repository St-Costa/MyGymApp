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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.HeartRateBar
import com.mygymapp.ui.components.HrZoneTraceChart
import com.mygymapp.ui.theme.OnSkipped
import com.mygymapp.ui.theme.SkippedColor

/**
 * The single action button:
 * IDLE ("Inizia") -> RUNNING ("Termina"), which closes the block, marks the
 * exercise completed and exits in one step — no intermediate phase. The DONE state
 * ("Completa") only shows on re-entry when a block was left open by a process
 * kill and closed on load (see CardioExerciseViewModel.init) — normal flow never hits it.
 * See CardioExerciseViewModel's startBlock()/stopBlock()/completeExercise().
 *
 * In IDLE a second, grey "Salta" button sits below the main one — the
 * cardio equivalent of the strength/stretch skip path (completeExercise() with no blocks
 * closes the exercise as completedEmpty). It disappears once a block is running/done.
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
    // One-shot: collect the completion event, don't observe the boolean flag (a
    // LaunchedEffect(flag) can re-fire navigation on recompositions with flag=true).
    LaunchedEffect(Unit) {
        viewModel.completionEvent.collect { onComplete() }
    }

    // Tieni lo schermo acceso solo mentre il blocco cardio è in corso (isBlockRunning).
    // view.keepScreenOn non richiede permessi e viene rilasciato in onDispose,
    // quindi fuori dal cardio il comportamento torna quello di sistema.
    val view = LocalView.current
    DisposableEffect(uiState.isBlockRunning) {
        view.keepScreenOn = uiState.isBlockRunning
        onDispose { view.keepScreenOn = false }
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
                // tap "Termina" explicitly.
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

                // Single action button: IDLE -> RUNNING, and "Termina" both ends the
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
                    modifier = Modifier.fillMaxWidth(0.85f).align(Alignment.CenterHorizontally),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (buttonState) {
                            CardioButtonState.IDLE -> MaterialTheme.colorScheme.secondary
                            CardioButtonState.RUNNING -> MaterialTheme.colorScheme.error
                            CardioButtonState.DONE -> MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    com.mygymapp.ui.components.ActionButtonText(
                        when (buttonState) {
                            CardioButtonState.IDLE -> "Inizia"
                            CardioButtonState.RUNNING -> "Termina"
                            CardioButtonState.DONE -> "Completa"
                        }
                    )
                }

                // Skip path, mirroring the strength/stretch grey "Salta"
                // button: completeExercise() with no blocks closes the exercise as
                // completedEmpty (grey border + X in the active list, excluded from stats).
                // Only meaningful before a block is started — hidden once running/done.
                if (buttonState == CardioButtonState.IDLE) {
                    Button(
                        onClick = { viewModel.completeExercise() },
                        modifier = Modifier.fillMaxWidth(0.85f).align(Alignment.CenterHorizontally),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SkippedColor,
                            contentColor = OnSkipped,
                        ),
                    ) {
                        com.mygymapp.ui.components.ActionButtonText("Salta")
                    }
                }
            }
        }
    }
}
