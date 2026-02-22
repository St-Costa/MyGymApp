package com.mygymapp.ui.screen.exerciseedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.components.BodyPartAutocomplete
import com.mygymapp.ui.components.MediaPreview
import com.mygymapp.ui.theme.ForzaColor
import com.mygymapp.ui.theme.StretchColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseEditScreen(
    exerciseId: String?,
    onBack: () -> Unit,
    viewModel: ExerciseEditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Debounce link preview: wait 800ms after the user stops typing before loading
    var previewLink by remember { mutableStateOf(uiState.link) }
    LaunchedEffect(uiState.link) {
        val current = uiState.link.trim()
        if (current.isBlank()) {
            previewLink = ""
        } else {
            delay(800)
            previewLink = current
        }
    }

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onBack()
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete exercise?") },
            text = { Text("\"${uiState.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteExercise()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isNew) "New Exercise" else "Edit Exercise") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!uiState.isNew) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete exercise",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Exercise Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Type toggle
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterChip(
                    selected = uiState.type == ExerciseType.FORZA,
                    onClick = { viewModel.onTypeChange(ExerciseType.FORZA) },
                    label = { Text("Strength") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ForzaColor.copy(alpha = 0.2f),
                        selectedLabelColor = ForzaColor,
                    ),
                )
                FilterChip(
                    selected = uiState.type == ExerciseType.STRETCH,
                    onClick = { viewModel.onTypeChange(ExerciseType.STRETCH) },
                    label = { Text("Stretch") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StretchColor.copy(alpha = 0.2f),
                        selectedLabelColor = StretchColor,
                    ),
                )
            }

            if (uiState.type == ExerciseType.FORZA) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Default rep range:", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RepRangeStepButton("-") { viewModel.onRepMinChange(uiState.defaultRepRangeMin - 1) }
                        Text(
                            text = uiState.defaultRepRangeMin.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.Center,
                        )
                        RepRangeStepButton("+") { viewModel.onRepMinChange(uiState.defaultRepRangeMin + 1) }
                        Text(
                            text = "–",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                        RepRangeStepButton("-") { viewModel.onRepMaxChange(uiState.defaultRepRangeMax - 1) }
                        Text(
                            text = uiState.defaultRepRangeMax.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.Center,
                        )
                        RepRangeStepButton("+") { viewModel.onRepMaxChange(uiState.defaultRepRangeMax + 1) }
                    }
                }
            }

            BodyPartAutocomplete(
                value = uiState.bodypart,
                onValueChange = viewModel::onBodypartChange,
                existingBodyparts = uiState.existingBodyparts,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.link,
                onValueChange = viewModel::onLinkChange,
                label = { Text("Image / YouTube Link") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            MediaPreview(link = previewLink, showErrorText = true)

            OutlinedTextField(
                value = uiState.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text("Notes / Description") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun RepRangeStepButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(32.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
