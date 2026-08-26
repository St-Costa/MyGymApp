package com.mygymapp.ui.screen.exerciselist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.components.EmptyStateBox
import com.mygymapp.ui.components.ExerciseCard
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.theme.accentColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(ExerciseType.FORZA, ExerciseType.CARDIO, ExerciseType.STRETCH).forEach { type ->
                    val color = type.accentColor()
                    val selected = uiState.selectedType == type
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.onTypeFilterChange(type) },
                        label = {
                            Text(
                                when (type) {
                                    ExerciseType.FORZA -> "Strength"
                                    ExerciseType.CARDIO -> "Cardio"
                                    ExerciseType.STRETCH -> "Stretch"
                                }
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = color,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = color,
                            selectedBorderColor = color,
                        ),
                    )
                }
            }
            if (uiState.availableBodyparts.isNotEmpty()) {
                // "Bodypart" always stays put and toggles the choices row below it. Picking
                // one adds a second chip to its right showing that bodypart, in selected
                // style; tapping that chip clears the filter (chip disappears). Tapping
                // "Bodypart" again re-opens the choices row (minus whichever is already
                // selected) to swap to a different bodypart.
                var choicesExpanded by remember { mutableStateOf(false) }
                val selectedBodypart = uiState.selectedBodypart
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { choicesExpanded = !choicesExpanded },
                        label = { Text("Bodypart") },
                    )
                    if (selectedBodypart != null) {
                        FilterChip(
                            selected = true,
                            onClick = {
                                viewModel.onBodypartFilterChange(selectedBodypart) // clears (toggle-off)
                                choicesExpanded = false
                            },
                            label = { Text(selectedBodypart) },
                        )
                    }
                }
                if (choicesExpanded) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        uiState.availableBodyparts
                            .filter { it != selectedBodypart }
                            .forEach { bodypart ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        viewModel.onBodypartFilterChange(bodypart)
                                        choicesExpanded = false
                                    },
                                    label = { Text(bodypart) },
                                )
                            }
                    }
                }
            }
            val isEmpty = uiState.cardioExercises.isEmpty() && uiState.exercisesByBodypart.isEmpty()
            when {
                uiState.isLoading -> FullscreenLoading(PaddingValues())
                isEmpty -> EmptyStateBox(
                    message = if (uiState.searchQuery.isNotBlank()) {
                        "No exercises match \"${uiState.searchQuery}\"."
                    } else {
                        "No exercises yet.\nTap + to create one."
                    },
                    paddingValues = PaddingValues(),
                )
                else -> {
                    // Keying the scroll state itself on the filter — rather than scrolling an
                    // existing state back to 0 in a LaunchedEffect after the fact — means the
                    // list is composed at offset 0 in the SAME frame the filtered content
                    // changes. The LaunchedEffect approach instead drew one frame with the new
                    // (shorter) content at the old scroll offset, then snapped to 0 a frame
                    // later — a visible flash where "Cardio" briefly seemed to load in late.
                    val listState = remember(uiState.selectedType, uiState.selectedBodypart, uiState.searchQuery) {
                        LazyListState()
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                    // Cardio always first, as its own fixed section — never grouped by
                    // bodypart (see ExerciseListViewModel).
                    if (uiState.cardioExercises.isNotEmpty()) {
                        item(key = "header_cardio") {
                            Text(
                                text = "Cardio",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(
                            items = uiState.cardioExercises,
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
