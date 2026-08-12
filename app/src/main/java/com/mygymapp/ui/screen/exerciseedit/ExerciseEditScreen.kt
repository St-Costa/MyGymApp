package com.mygymapp.ui.screen.exerciseedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.components.BodyPartAutocomplete
import com.mygymapp.ui.components.DeleteConfirmationDialog
import com.mygymapp.ui.components.MediaPreview
import com.mygymapp.ui.components.RoundStepButton
import com.mygymapp.ui.theme.CardioColor
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
    val scope = rememberCoroutineScope()

    // Intercept back gesture and top-bar back button: save first, then navigate.
    val navigateBack: () -> Unit = { scope.launch { viewModel.saveNow(); onBack() } }
    BackHandler { navigateBack() }

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
        DeleteConfirmationDialog(
            itemName = uiState.name,
            itemType = "exercise",
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteExercise()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isNew) "New Exercise" else "Edit Exercise") },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
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
                FilterChip(
                    selected = uiState.type == ExerciseType.CARDIO,
                    onClick = { viewModel.onTypeChange(ExerciseType.CARDIO) },
                    label = { Text("Cardio") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CardioColor.copy(alpha = 0.2f),
                        selectedLabelColor = CardioColor,
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
                        RoundStepButton("-") { viewModel.onRepMinChange(uiState.defaultRepRangeMin - 1) }
                        Text(
                            text = uiState.defaultRepRangeMin.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.Center,
                        )
                        RoundStepButton("+") { viewModel.onRepMinChange(uiState.defaultRepRangeMin + 1) }
                        Text(
                            text = "–",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                        RoundStepButton("-") { viewModel.onRepMaxChange(uiState.defaultRepRangeMax - 1) }
                        Text(
                            text = uiState.defaultRepRangeMax.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.Center,
                        )
                        RoundStepButton("+") { viewModel.onRepMaxChange(uiState.defaultRepRangeMax + 1) }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Corpo libero", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Nessun peso esterno per natura (es. plank, push-up). " +
                                "I set restano conteggiati come lavoro svolto anche a peso 0.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.isBodyweight,
                        onCheckedChange = viewModel::onBodyweightChange,
                    )
                }
            }

            // Cardio exercises live in their own dedicated "Cardio" section (ExerciseListScreen)
            // instead of being grouped by muscle group — bodypart is meaningless for them, so
            // the field is hidden rather than shown-but-unused (see ExerciseEditViewModel /
            // ExerciseListViewModel, which force bodypart = "" for CARDIO on save).
            if (uiState.type != ExerciseType.CARDIO) {
                BodyPartAutocomplete(
                    value = uiState.bodypart,
                    onValueChange = viewModel::onBodypartChange,
                    existingBodyparts = uiState.existingBodyparts,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = uiState.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text("Notes / Description") },
                minLines = 3,
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

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

