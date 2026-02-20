package com.mygymapp.ui.screen.strengthexercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.components.AutoSaveTextField
import com.mygymapp.ui.components.ScrollPickerInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrengthExerciseScreen(
    onBack: () -> Unit,
    viewModel: StrengthExerciseViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

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
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Set",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "Reps",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(2f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "Weight (Kg)",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(2f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                HorizontalDivider()

                // Set rows
                val repRangeText = if (uiState.repRangeMin > 0 && uiState.repRangeMax > 0) {
                    "${uiState.repRangeMin}-${uiState.repRangeMax}"
                } else ""

                uiState.sets.forEachIndexed { index, set ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Set number
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(0.8f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )

                        // Reps picker
                        Column(
                            modifier = Modifier.weight(2f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ScrollPickerInput(
                                value = set.reps,
                                onValueChange = { viewModel.updateReps(index, it.toInt()) },
                                scrollStep = 5.0,
                                buttonStep = 1.0,
                            )
                            if (repRangeText.isNotBlank()) {
                                Text(
                                    text = repRangeText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                            }
                        }

                        // Weight picker
                        ScrollPickerInput(
                            value = set.weight,
                            onValueChange = { viewModel.updateWeight(index, it.toDouble()) },
                            scrollStep = 5.0,
                            buttonStep = 1.0,
                            isDecimal = true,
                            modifier = Modifier.weight(2f),
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
                    onClick = {
                        viewModel.completeExercise()
                        onBack()
                    },
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
