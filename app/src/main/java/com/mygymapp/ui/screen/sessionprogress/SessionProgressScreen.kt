package com.mygymapp.ui.screen.sessionprogress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.TonnageLineChart

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
                        val filters = listOf("Totale") + uiState.sessionTonnageByBodypart.keys.toList()
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filters) { filter ->
                                FilterChip(
                                    selected = filter == uiState.selectedChartFilter,
                                    onClick = { viewModel.selectFilter(filter) },
                                    label = { Text(filter) },
                                )
                            }
                        }

                        val chartData = if (uiState.selectedChartFilter == "Totale") {
                            uiState.sessionTonnage
                        } else {
                            uiState.sessionTonnageByBodypart[uiState.selectedChartFilter]
                                ?: uiState.sessionTonnage
                        }
                        val currentValue = chartData.lastOrNull() ?: 0.0
                        Text(
                            text = "${uiState.selectedChartFilter}: %.1f kg".format(currentValue),
                            style = MaterialTheme.typography.titleMedium,
                        )

                        TonnageLineChart(
                            data = chartData,
                            labels = uiState.sessionLabels,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
