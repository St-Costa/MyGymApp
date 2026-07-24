package com.mygymapp.ui.screen.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.components.GitgraphView

@Composable
fun MainScreen(
    onNavigateToWeekView: () -> Unit,
    onNavigateToExercises: () -> Unit,
    onNavigateToRoutines: () -> Unit,
    onNavigateToHeartRate: () -> Unit,
    onNavigateToOptions: () -> Unit,
    onNavigateToSessionProgress: (sessionId: String, date: String) -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadGitgraph()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToOptions,
            ) {
                if (uiState.isSeedingData) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Settings, contentDescription = "Opzioni")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Gitgraph
            GitgraphView(
                days = uiState.gitgraphDays,
                todayIndex = uiState.todayIndex,
                tonnageChanges = uiState.gitgraphTonnageChanges,
                lastWeekRoutineNames = uiState.lastWeekRoutineNames,
                powerliftingWeeks = uiState.powerliftingWeeks,
                onLastRowCellClick = { col ->
                    val sessionId = uiState.lastWeekSessionIds.getOrNull(col)
                    val date = uiState.lastWeekSessionDates.getOrNull(col)
                    if (sessionId != null && date != null) {
                        onNavigateToSessionProgress(sessionId, date)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.weight(1f))

            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onNavigateToWeekView,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text("Week View", fontSize = 22.sp)
                }
                Button(
                    onClick = onNavigateToExercises,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text("Exercises", fontSize = 22.sp)
                }
                Button(
                    onClick = onNavigateToRoutines,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text("Routines", fontSize = 22.sp)
                }
                Button(
                    onClick = onNavigateToHeartRate,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Icon(Icons.Default.FavoriteBorder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Heart Rate", fontSize = 22.sp)
                }
            }
            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}
