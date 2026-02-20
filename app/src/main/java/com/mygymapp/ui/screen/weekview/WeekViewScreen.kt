package com.mygymapp.ui.screen.weekview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekViewScreen(
    onNavigateToRoutine: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: WeekViewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadWeek()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Week View") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = uiState.days, key = { it.dayKey }) { day ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = day.dayName,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = if (day.isToday) FontWeight.ExtraBold else FontWeight.SemiBold,
                        ),
                        color = if (day.isToday)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onBackground,
                    )

                    if (day.routines.isEmpty()) {
                        Text(
                            text = "Rest day",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    } else {
                        day.routines.forEach { routine ->
                            Card(
                                onClick = { onNavigateToRoutine(routine.id) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                                border = if (day.isToday) {
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                                },
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    Text(
                                        text = routine.name,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = "${routine.exercises.size} exercises",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
