package com.mygymapp.ui.screen.sessionprogress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
                .padding(16.dp),
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
                                color = androidx.compose.ui.graphics.Color(0xFFFF9800),
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

            // ECG analysis + cardiac drift card
            if (uiState.ecgBeats > 0 || uiState.cardiacDriftBpmMin != 0.0) {
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
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "ECG Analysis",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (uiState.ecgBeats > 0) {
                            Text(
                                "${uiState.ecgBeats} beats • avg ${uiState.ecgAvgHr.toInt()} BPM • RMSSD ${"%.0f".format(uiState.ecgSessionRmssd)} ms",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val anomalies = uiState.ecgPacCount + uiState.ecgPauseCount + uiState.ecgIrregularBeats
                            if (anomalies > 0) {
                                Text(
                                    "PAC: ${uiState.ecgPacCount} • Pauses: ${uiState.ecgPauseCount} • Irregular: ${uiState.ecgIrregularBeats}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Not diagnostic — consult a physician if persistent.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "No anomalies detected.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (uiState.cardiacDriftBpmMin != 0.0) {
                            val drift = uiState.cardiacDriftBpmMin
                            val driftLabel = when {
                                drift > 1.0 -> "high — consider hydration/heat"
                                drift > 0.5 -> "moderate — mild dehydration likely"
                                drift > -0.5 -> "normal"
                                else -> "negative (HR dropped)"
                            }
                            Text(
                                "Cardiac drift: ${"%+.2f".format(drift)} BPM/min ($driftLabel)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (uiState.restingHr > 0 || uiState.hrr60s > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            if (uiState.restingHr > 0) {
                                Text(
                                    "Resting HR: ${uiState.restingHr} BPM",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (uiState.hrr60s > 0) {
                                val hrrLabel = when {
                                    uiState.hrr60s < 12 -> "low — poor recovery"
                                    uiState.hrr60s < 20 -> "ok"
                                    uiState.hrr60s < 30 -> "good"
                                    else -> "excellent"
                                }
                                Text(
                                    "HRR (1 min): ${uiState.hrr60s.toInt()} BPM ($hrrLabel)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        if (uiState.sdnn > 0 || uiState.pnn50 > 0) {
                            Text(
                                "HRV: SDNN ${uiState.sdnn.toInt()} ms · pNN50 ${"%.1f".format(uiState.pnn50)}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        if (uiState.poincareSd1 > 0) {
                            Text(
                                "Poincare: SD1 ${uiState.poincareSd1.toInt()} · SD2 ${uiState.poincareSd2.toInt()} · ratio ${"%.2f".format(uiState.poincareRatio)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        if (uiState.afibSuspicionEpisodes > 0) {
                            Text(
                                "AFib screening: ${uiState.afibSuspicionEpisodes} suspicious episode(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFEF5350),
                            )
                            Text(
                                "Not diagnostic — consult a physician if recurring.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (uiState.sessionTonnage.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Nessun dato disponibile", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
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
                        val crossRoutineFilters = listOf("kcal", "TRIMP", "VO2max")
                        val filters = listOf("Totale") + uiState.sessionTonnageByBodypart.keys.toList() + crossRoutineFilters
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filters) { filter ->
                                FilterChip(
                                    selected = filter == uiState.selectedChartFilter,
                                    onClick = { viewModel.selectFilter(filter) },
                                    label = { Text(filter) },
                                )
                            }
                        }

                        val chartData: List<Double>
                        val chartLabels: List<String>
                        val chartTitle: String

                        when (uiState.selectedChartFilter) {
                            "kcal" -> {
                                chartData = uiState.allSessionCalories
                                chartLabels = uiState.allSessionLabels
                                chartTitle = "kcal: ${(chartData.lastOrNull() ?: 0.0).toInt()} (all routines)"
                            }
                            "TRIMP" -> {
                                chartData = uiState.allSessionTrimp
                                chartLabels = uiState.allSessionLabels
                                chartTitle = "TRIMP: ${(chartData.lastOrNull() ?: 0.0).toInt()} (all routines)"
                            }
                            "VO2max" -> {
                                chartData = uiState.allSessionVo2max.filter { it > 0 }
                                chartLabels = uiState.allSessionLabels.zip(uiState.allSessionVo2max)
                                    .filter { it.second > 0 }.map { it.first }
                                chartTitle = "VO2max: ${"%.1f".format(chartData.lastOrNull() ?: 0.0)} (all routines)"
                            }
                            "Totale" -> {
                                chartData = uiState.sessionTonnage
                                chartLabels = uiState.sessionLabels
                                chartTitle = "Totale: ${"%.1f".format(chartData.lastOrNull() ?: 0.0)} kg"
                            }
                            else -> {
                                chartData = uiState.sessionTonnageByBodypart[uiState.selectedChartFilter] ?: uiState.sessionTonnage
                                chartLabels = uiState.sessionLabels
                                chartTitle = "${uiState.selectedChartFilter}: ${"%.1f".format(chartData.lastOrNull() ?: 0.0)} kg"
                            }
                        }

                        Text(
                            text = chartTitle,
                            style = MaterialTheme.typography.titleMedium,
                        )

                        if (chartData.isNotEmpty()) {
                            TonnageLineChart(
                                data = chartData,
                                labels = chartLabels,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
