package com.mygymapp.ui.screen.strengthexercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.components.AutoSaveTextField
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.MediaPreview
import com.mygymapp.ui.components.ScrollPickerInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrengthExerciseScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: StrengthExerciseViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val completionSaved by viewModel.completionSaved.collectAsState()
    LaunchedEffect(completionSaved) {
        if (completionSaved) onComplete()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.exercise?.name ?: "Exercise") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            FullscreenLoading(padding)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Media (image or YouTube thumbnail)
                MediaPreview(link = uiState.exercise?.link ?: "")

                // Description (auto-save)
                AutoSaveTextField(
                    value = uiState.description,
                    onValueChange = viewModel::updateDescription,
                    onSave = viewModel::saveDescription,
                    label = "Description",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )

                // Sets header
                val repRangeText = remember(uiState.repRangeMin, uiState.repRangeMax) {
                    if (uiState.repRangeMin > 0 && uiState.repRangeMax > 0) {
                        "${uiState.repRangeMin} - ${uiState.repRangeMax}"
                    } else "Reps"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        repRangeText,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "Kg",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                HorizontalDivider()

                // Set rows
                uiState.sets.forEachIndexed { index, set ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Reps picker (tap only, no scroll)
                        ScrollPickerInput(
                            value = set.reps,
                            onValueChange = { viewModel.updateReps(index, it.toInt()) },
                            buttonStep = 1.0,
                            isModified = set.repsModified,
                            enableScroll = false,
                            modifier = Modifier.weight(1f),
                        )

                        // Weight picker (tap + long-press +10/-10 per second, no scroll)
                        ScrollPickerInput(
                            value = set.weight,
                            onValueChange = { viewModel.updateWeight(index, it.toDouble()) },
                            buttonStep = 1.0,
                            isDecimal = true,
                            isModified = set.weightModified,
                            enableScroll = false,
                            longPressRepeatStep = 10.0,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (index < uiState.sets.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Complete button
                Button(
                    onClick = { viewModel.completeExercise() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Complete Exercise")
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}
