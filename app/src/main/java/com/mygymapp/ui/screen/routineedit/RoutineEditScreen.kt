package com.mygymapp.ui.screen.routineedit

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.theme.ForzaColor
import com.mygymapp.ui.theme.StretchColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditScreen(
    routineId: String?,
    onBack: () -> Unit,
    onPickExercise: () -> Unit,
    viewModel: RoutineEditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isNew) "New Routine" else "Edit Routine") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                label = { Text("Routine Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Day picker
            DayPicker(
                selectedDay = uiState.day,
                onDaySelected = viewModel::onDayChange,
            )

            OutlinedTextField(
                value = uiState.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text("Notes") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            // Exercises section
            Text(
                text = "Exercises",
                style = MaterialTheme.typography.titleLarge,
            )

            uiState.exercises.forEachIndexed { index, exerciseUi ->
                RoutineExerciseItem(
                    exercise = exerciseUi,
                    onRemove = { viewModel.removeExercise(index) },
                    onSetsChange = { viewModel.updateExerciseSets(index, it) },
                    onRepMinChange = { viewModel.updateExerciseRepMin(index, it) },
                    onRepMaxChange = { viewModel.updateExerciseRepMax(index, it) },
                    onTimeChange = { viewModel.updateExerciseTime(index, it) },
                )
            }

            OutlinedButton(
                onClick = onPickExercise,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Add Exercise", modifier = Modifier.padding(start = 8.dp))
            }

            Button(
                onClick = viewModel::save,
                enabled = uiState.name.isNotBlank() && !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(if (uiState.isNew) "Create Routine" else "Save Changes")
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(
    selectedDay: String,
    onDaySelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayDay = selectedDay.ifBlank { "Select day" }
        .replaceFirstChar { it.uppercase() }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = displayDay,
            onValueChange = {},
            readOnly = true,
            label = { Text("Day") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DAYS_OF_WEEK.forEach { day ->
                DropdownMenuItem(
                    text = { Text(day.replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onDaySelected(day)
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("No specific day") },
                onClick = {
                    onDaySelected("")
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun RoutineExerciseItem(
    exercise: RoutineExerciseUi,
    onRemove: () -> Unit,
    onSetsChange: (Int) -> Unit,
    onRepMinChange: (Int) -> Unit,
    onRepMaxChange: (Int) -> Unit,
    onTimeChange: (Int) -> Unit,
) {
    val borderColor = when (exercise.exerciseType) {
        ExerciseType.FORZA -> ForzaColor
        ExerciseType.STRETCH -> StretchColor
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = exercise.exerciseName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Sets
            NumberRow(label = "Sets", value = exercise.sets, onValueChange = onSetsChange)

            if (exercise.exerciseType == ExerciseType.FORZA) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Rep range:", style = MaterialTheme.typography.bodyMedium)
                    SmallNumberField(value = exercise.repRangeMin, onValueChange = onRepMinChange)
                    Text("-")
                    SmallNumberField(value = exercise.repRangeMax, onValueChange = onRepMaxChange)
                }
            } else {
                NumberRow(
                    label = "Time per set (sec)",
                    value = exercise.timePerSetSeconds,
                    onValueChange = onTimeChange,
                    step = 5,
                )
            }
        }
    }
}

@Composable
private fun NumberRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    step: Int = 1,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onValueChange(value - step) }) { Text("-") }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(40.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedButton(onClick = { onValueChange(value + step) }) { Text("+") }
    }
}

@Composable
private fun SmallNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(onClick = { onValueChange(value - 1) }) { Text("-") }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(30.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedButton(onClick = { onValueChange(value + 1) }) { Text("+") }
    }
}
