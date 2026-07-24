package com.mygymapp.ui.screen.exerciselist

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.components.EmptyStateBox
import com.mygymapp.ui.components.ExerciseCard
import com.mygymapp.ui.components.FullscreenLoading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseListScreen(
    pickerMode: Boolean = false,
    onNavigateToEdit: (String?) -> Unit,
    onNavigateToNew: () -> Unit,
    onExercisePicked: (String) -> Unit = {},
    onBack: () -> Unit,
    viewModel: ExerciseListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pickerMode) "Pick Exercise" else "Exercises") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!pickerMode) {
                ExtendedFloatingActionButton(
                    onClick = onNavigateToNew,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("New Exercise", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search exercises") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
            )
            when {
                uiState.isLoading -> FullscreenLoading(PaddingValues())
                uiState.exercisesByBodypart.isEmpty() -> EmptyStateBox(
                    message = if (uiState.searchQuery.isNotBlank()) {
                        "No exercises match \"${uiState.searchQuery}\"."
                    } else {
                        "No exercises yet.\nTap + to create one."
                    },
                    paddingValues = PaddingValues(),
                )
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                    uiState.exercisesByBodypart.forEach { (bodypart, exercises) ->
                        item(key = "header_$bodypart") {
                            Text(
                                text = bodypart,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(
                            items = exercises,
                            key = { it.id },
                        ) { exercise ->
                            ExerciseCard(
                                exercise = exercise,
                                onClick = {
                                    if (pickerMode) {
                                        onExercisePicked(exercise.id)
                                    } else {
                                        onNavigateToEdit(exercise.id)
                                    }
                                },
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}
