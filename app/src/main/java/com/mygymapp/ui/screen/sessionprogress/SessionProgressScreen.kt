package com.mygymapp.ui.screen.sessionprogress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.TonnageLineChart
import com.mygymapp.ui.components.trimpColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionProgressScreen(
    justCompleted: Boolean = false,
    onBack: () -> Unit,
    onDone: () -> Unit = onBack,
    onNavigateHome: () -> Unit,
    viewModel: SessionProgressViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reached right after finishing a workout: force the summary to be seen before Home,
    // instead of letting it get buried under the exercise list like before.
    if (justCompleted) {
        androidx.activity.compose.BackHandler { onDone() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (justCompleted) "Sessione completata" else uiState.routineName) },
                navigationIcon = {
                    if (!justCompleted) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateHome) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            FullscreenLoading()
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (justCompleted) {
                Text(
                    text = uiState.routineName,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            // Calories + TRIMP summary (if recorded)
            if (uiState.sessionCalories > 0 || uiState.sessionTrimp > 0 || uiState.sessionSteps != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                                "${uiState.sessionCalories.toInt()}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color(0xFFFF9800),
                            )
                            Text("kcal", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${uiState.sessionTrimp.toInt()}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = trimpColor(uiState.sessionTrimp),
                            )
                            Text("TRIMP", style = MaterialTheme.typography.bodySmall)
                        }
                        if (uiState.vo2max > 0) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "%.1f".format(uiState.vo2max),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                Text("VO2max", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        // Display-only — read fresh from Health Connect for this session's
                        // own time window, never saved onto the session or synced (the
                        // figure that IS synced is a whole-day average, a different number
                        // entirely — see SessionProgressUiState.sessionSteps).
                        if (uiState.sessionSteps != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${uiState.sessionSteps}",
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                Text("passi", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // Total tonnage chart — no filter/selector, just the trend across recent sessions
            if (uiState.sessionTonnage.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Tonnellaggio totale: ${"%.1f".format(uiState.sessionTonnage.lastOrNull() ?: 0.0)} kg",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TonnageLineChart(
                            data = uiState.sessionTonnage,
                            labels = uiState.sessionLabels,
                            modifier = Modifier.fillMaxWidth(),
                            secondaryData = if (uiState.sessionBestE1RM.size == uiState.sessionTonnage.size) {
                                uiState.sessionBestE1RM
                            } else null,
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Nessun dato disponibile", style = MaterialTheme.typography.bodyLarge)
                }
            }

            // Cardio trend charts — one small chart per metric, only when it has data.
            // Deep ECG-derived charts (avg HR from ECG, RMSSD, SDNN, Poincaré ratio) and
            // the arrhythmia/AFib anomaly callouts were removed: that analysis now runs
            // server-side on the uploaded raw waveform, not on the phone — see
            // docs/SYNC.md "Fourth record type: raw ECG". Only metrics computed from live
            // HR/readiness tracking remain here.
            val hasCardioData = listOf(
                uiState.hrrSeries, uiState.vo2maxSeries, uiState.restingHrSeries, uiState.cardiacDriftSeries,
            ).any { it.data.isNotEmpty() }

            if (hasCardioData) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Cardio", style = MaterialTheme.typography.titleMedium)

                        CardioChart("HRR (recupero 60s)", "bpm", uiState.hrrSeries)
                        CardioChart("VO2max", "", uiState.vo2maxSeries)
                        CardioChart("HR a riposo", "bpm", uiState.restingHrSeries)
                        CardioChart("Deriva cardiaca", "bpm/min", uiState.cardiacDriftSeries)
                    }
                }
            }

            if (justCompleted) {
                androidx.compose.material3.Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Fatto")
                }
            }
        }
    }
}

@Composable
private fun CardioChart(title: String, unit: String, series: ChartSeries) {
    if (series.data.isEmpty()) return
    val last = series.data.last()
    val lastText = if (last == last.toLong().toDouble()) last.toLong().toString() else "%.1f".format(last)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (unit.isBlank()) "$title: $lastText" else "$title: $lastText $unit",
            style = MaterialTheme.typography.titleSmall,
        )
        TonnageLineChart(
            data = series.data,
            labels = series.labels,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
