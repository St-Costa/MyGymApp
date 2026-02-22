package com.mygymapp.ui.screen.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
    viewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadGitgraph()
    }

    Scaffold { padding ->
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
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.weight(1f))

            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onNavigateToWeekView,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                ) {
                    Text("Week View", fontSize = 28.sp)
                }
                Button(
                    onClick = onNavigateToExercises,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                ) {
                    Text("Exercises", fontSize = 28.sp)
                }
                Button(
                    onClick = onNavigateToRoutines,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                ) {
                    Text("Routines", fontSize = 28.sp)
                }
            }
        }
    }
}
