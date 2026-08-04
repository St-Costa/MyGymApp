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
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    viewModel: SessionProgressViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.routineName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // Calories + TRIMP summary (if recorded)
            if (uiState.sessionCalories > 0 || uiState.sessionTrimp > 0) {
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

            // Cardio trend charts — one small chart per metric, only when it has data
            val hasCardioData = listOf(
                uiState.avgHrSeries, uiState.hrrSeries, uiState.vo2maxSeries, uiState.rmssdSeries,
                uiState.sdnnSeries, uiState.poincareRatioSeries, uiState.restingHrSeries, uiState.cardiacDriftSeries,
            ).any { it.data.isNotEmpty() }
            val anomalies = uiState.ecgPacCount + uiState.ecgPauseCount + uiState.ecgIrregularBeats

            if (hasCardioData || anomalies > 0 || uiState.afibSuspicionEpisodes > 0) {
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

                        CardioChart("HR medio", "bpm", uiState.avgHrSeries)
                        CardioChart("HRR (recupero 60s)", "bpm", uiState.hrrSeries)
                        CardioChart("VO2max", "", uiState.vo2maxSeries)
                        CardioChart("HRV — RMSSD", "ms", uiState.rmssdSeries)
                        CardioChart("HRV — SDNN", "ms", uiState.sdnnSeries)
                        CardioChart("Poincare ratio", "", uiState.poincareRatioSeries)
                        CardioChart("HR a riposo", "bpm", uiState.restingHrSeries)
                        CardioChart("Deriva cardiaca", "bpm/min", uiState.cardiacDriftSeries)

                        if (anomalies > 0) {
                            Text(
                                "PAC: ${uiState.ecgPacCount} • Pause: ${uiState.ecgPauseCount} • Irregolari: ${uiState.ecgIrregularBeats} — non diagnostico, consulta un medico se persiste.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (uiState.afibSuspicionEpisodes > 0) {
                            Text(
                                "AFib screening: ${uiState.afibSuspicionEpisodes} episodio/i sospetto/i — non diagnostico, consulta un medico se ricorrente.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFEF5350),
                            )
                        }
                    }
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
