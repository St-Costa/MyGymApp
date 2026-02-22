package com.mygymapp.ui.screen.routineedit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.components.DeleteConfirmationDialog
import com.mygymapp.ui.components.RoundStepButton
import com.mygymapp.ui.theme.ForzaColor
import com.mygymapp.ui.theme.StretchColor
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Drag-and-drop state (now on segment indices)
// ---------------------------------------------------------------------------

private data class DragState(
    val fromIndex: Int,
    val offsetY: Float,
    val targetIndex: Int,
)

/**
 * How much a non-dragged segment at [index] should be visually displaced given
 * that the dragged segment is moving from [from] toward [target].
 */
private fun calcDisplacement(
    index: Int,
    from: Int,
    target: Int,
    draggedItemHeightPx: Float,
    gapPx: Float,
): Float {
    if (index == from) return 0f
    val shift = draggedItemHeightPx + gapPx
    return when {
        from < target && index in (from + 1)..target -> -shift
        from > target && index in target until from -> shift
        else -> 0f
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditScreen(
    routineId: String?,
    onBack: () -> Unit,
    onPickExercise: () -> Unit,
    viewModel: RoutineEditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val navigateBack: () -> Unit = { scope.launch { viewModel.saveNow(); onBack() } }
    BackHandler { navigateBack() }

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onBack()
    }

    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            itemName = uiState.name,
            itemType = "routine",
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteRoutine()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isNew) "New Routine" else "Edit Routine") },
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
                                contentDescription = "Delete routine",
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
                label = { Text("Routine Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

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

            Text(
                text = "Exercises",
                style = MaterialTheme.typography.titleLarge,
            )

            ExerciseDragDropList(
                exercises = uiState.exercises,
                onMoveSegment = viewModel::moveSegment,
                onRemove = viewModel::removeExercise,
                onToggleSuperset = viewModel::toggleSuperset,
                onSetsChange = viewModel::updateExerciseSets,
                onRepMinChange = viewModel::updateExerciseRepMin,
                onRepMaxChange = viewModel::updateExerciseRepMax,
                onTimeChange = viewModel::updateExerciseTime,
            )

            OutlinedButton(
                onClick = onPickExercise,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Add Exercise", modifier = Modifier.padding(start = 8.dp))
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Drag-and-drop exercise list (segment-aware)
// ---------------------------------------------------------------------------

@Composable
private fun ExerciseDragDropList(
    exercises: List<RoutineExerciseUi>,
    onMoveSegment: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onToggleSuperset: (index: Int) -> Unit,
    onSetsChange: (index: Int, sets: Int) -> Unit,
    onRepMinChange: (index: Int, value: Int) -> Unit,
    onRepMaxChange: (index: Int, value: Int) -> Unit,
    onTimeChange: (index: Int, seconds: Int) -> Unit,
) {
    var dragState by remember { mutableStateOf<DragState?>(null) }
    val segmentHeightsPx = remember { mutableStateMapOf<Int, Float>() }
    val gapPx = with(LocalDensity.current) { 16.dp.toPx() }
    val segments = remember(exercises) { buildExerciseSegments(exercises) }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        segments.forEachIndexed { segIdx, segment ->
            val isDragging = dragState?.fromIndex == segIdx
            val ds = dragState
            val offsetYPx = when {
                isDragging -> ds!!.offsetY
                ds != null -> calcDisplacement(
                    index = segIdx,
                    from = ds.fromIndex,
                    target = ds.targetIndex,
                    draggedItemHeightPx = segmentHeightsPx[ds.fromIndex] ?: 0f,
                    gapPx = gapPx,
                )
                else -> 0f
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { segmentHeightsPx[segIdx] = it.height.toFloat() }
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset { IntOffset(0, offsetYPx.roundToInt()) },
            ) {
                when (segment) {
                    is ExerciseSegment.Single -> {
                        val exIdx = segment.index
                        // Show superset link button only when the next segment is also a Single
                        val canLink = segIdx + 1 < segments.size && segments[segIdx + 1] is ExerciseSegment.Single
                        RoutineExerciseItem(
                            exercise = exercises[exIdx],
                            isDragging = isDragging,
                            onDragStart = { dragState = DragState(segIdx, 0f, segIdx) },
                            onDrag = { dy ->
                                val state = dragState ?: return@RoutineExerciseItem
                                val newOffset = state.offsetY + dy
                                val slotHeight = (segmentHeightsPx[state.fromIndex] ?: 0f) + gapPx
                                val delta = if (slotHeight > 0) (newOffset / slotHeight).roundToInt() else 0
                                val newTarget = (state.fromIndex + delta).coerceIn(0, segments.size - 1)
                                dragState = state.copy(offsetY = newOffset, targetIndex = newTarget)
                            },
                            onDragEnd = {
                                val state = dragState
                                dragState = null
                                if (state != null && state.fromIndex != state.targetIndex) {
                                    onMoveSegment(state.fromIndex, state.targetIndex)
                                }
                            },
                            onDragCancel = { dragState = null },
                            onRemove = { onRemove(exIdx) },
                            onSetsChange = { onSetsChange(exIdx, it) },
                            onRepMinChange = { onRepMinChange(exIdx, it) },
                            onRepMaxChange = { onRepMaxChange(exIdx, it) },
                            onTimeChange = { onTimeChange(exIdx, it) },
                            showDragHandle = true,
                            showSupersetButton = canLink,
                            onToggleSuperset = { onToggleSuperset(exIdx) },
                        )
                    }

                    is ExerciseSegment.SupersetPair -> {
                        SupersetPairContainer(
                            exercise1 = exercises[segment.index1],
                            exercise2 = exercises[segment.index2],
                            isDragging = isDragging,
                            onDragStart = { dragState = DragState(segIdx, 0f, segIdx) },
                            onDrag = { dy ->
                                val state = dragState ?: return@SupersetPairContainer
                                val newOffset = state.offsetY + dy
                                val slotHeight = (segmentHeightsPx[state.fromIndex] ?: 0f) + gapPx
                                val delta = if (slotHeight > 0) (newOffset / slotHeight).roundToInt() else 0
                                val newTarget = (state.fromIndex + delta).coerceIn(0, segments.size - 1)
                                dragState = state.copy(offsetY = newOffset, targetIndex = newTarget)
                            },
                            onDragEnd = {
                                val state = dragState
                                dragState = null
                                if (state != null && state.fromIndex != state.targetIndex) {
                                    onMoveSegment(state.fromIndex, state.targetIndex)
                                }
                            },
                            onDragCancel = { dragState = null },
                            onRemove1 = { onRemove(segment.index1) },
                            onRemove2 = { onRemove(segment.index2) },
                            onSetsChange1 = { onSetsChange(segment.index1, it) },
                            onRepMinChange1 = { onRepMinChange(segment.index1, it) },
                            onRepMaxChange1 = { onRepMaxChange(segment.index1, it) },
                            onTimeChange1 = { onTimeChange(segment.index1, it) },
                            onSetsChange2 = { onSetsChange(segment.index2, it) },
                            onRepMinChange2 = { onRepMinChange(segment.index2, it) },
                            onRepMaxChange2 = { onRepMaxChange(segment.index2, it) },
                            onTimeChange2 = { onTimeChange(segment.index2, it) },
                            onUnlink = { onToggleSuperset(segment.index1) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Superset pair container (purple frame wrapping two exercise cards)
// ---------------------------------------------------------------------------

@Composable
private fun SupersetPairContainer(
    exercise1: RoutineExerciseUi,
    exercise2: RoutineExerciseUi,
    isDragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onRemove1: () -> Unit,
    onRemove2: () -> Unit,
    onSetsChange1: (Int) -> Unit,
    onRepMinChange1: (Int) -> Unit,
    onRepMaxChange1: (Int) -> Unit,
    onTimeChange1: (Int) -> Unit,
    onSetsChange2: (Int) -> Unit,
    onRepMinChange2: (Int) -> Unit,
    onRepMaxChange2: (Int) -> Unit,
    onTimeChange2: (Int) -> Unit,
    onUnlink: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(2.dp, primaryColor),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 10.dp else 1.dp,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header: drag handle + "SUPERSET" label + unlink button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Drag to reorder",
                    tint = primaryColor,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onDragStart() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount.y)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragCancel() },
                            )
                        },
                )
                Text(
                    text = "SUPERSET",
                    style = MaterialTheme.typography.labelMedium,
                    color = primaryColor,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onUnlink) {
                    Icon(
                        Icons.Default.LinkOff,
                        contentDescription = "Remove superset",
                        tint = primaryColor,
                    )
                }
            }

            // Exercise 1 (no drag handle, no superset button)
            RoutineExerciseItem(
                exercise = exercise1,
                isDragging = false,
                onDragStart = {},
                onDrag = {},
                onDragEnd = {},
                onDragCancel = {},
                onRemove = onRemove1,
                onSetsChange = onSetsChange1,
                onRepMinChange = onRepMinChange1,
                onRepMaxChange = onRepMaxChange1,
                onTimeChange = onTimeChange1,
                showDragHandle = false,
                showSupersetButton = false,
            )

            HorizontalDivider(color = primaryColor.copy(alpha = 0.4f), thickness = 1.dp)

            // Exercise 2 (no drag handle, no superset button)
            RoutineExerciseItem(
                exercise = exercise2,
                isDragging = false,
                onDragStart = {},
                onDrag = {},
                onDragEnd = {},
                onDragCancel = {},
                onRemove = onRemove2,
                onSetsChange = onSetsChange2,
                onRepMinChange = onRepMinChange2,
                onRepMaxChange = onRepMaxChange2,
                onTimeChange = onTimeChange2,
                showDragHandle = false,
                showSupersetButton = false,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Single exercise card
// ---------------------------------------------------------------------------

@Composable
private fun RoutineExerciseItem(
    exercise: RoutineExerciseUi,
    isDragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onRemove: () -> Unit,
    onSetsChange: (Int) -> Unit,
    onRepMinChange: (Int) -> Unit,
    onRepMaxChange: (Int) -> Unit,
    onTimeChange: (Int) -> Unit,
    showDragHandle: Boolean = true,
    showSupersetButton: Boolean = false,
    onToggleSuperset: () -> Unit = {},
) {
    val borderColor = when (exercise.exerciseType) {
        ExerciseType.FORZA -> ForzaColor
        ExerciseType.STRETCH -> StretchColor
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(2.dp, borderColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 10.dp else 1.dp,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row: drag handle | name | delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showDragHandle) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { onDragStart() },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        onDrag(dragAmount.y)
                                    },
                                    onDragEnd = { onDragEnd() },
                                    onDragCancel = { onDragCancel() },
                                )
                            },
                    )
                }
                Text(
                    text = exercise.exerciseName,
                    style = MaterialTheme.typography.titleLarge,
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

            // Sets row
            NumberRow(
                label = "Sets",
                value = exercise.sets,
                onValueChange = onSetsChange,
            )

            if (exercise.exerciseType == ExerciseType.FORZA) {
                // Rep range: label + centered pickers with large numbers
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Rep range:", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RoundStepButton("-") { onRepMinChange(exercise.repRangeMin - 1) }
                        Text(
                            text = exercise.repRangeMin.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.Center,
                        )
                        RoundStepButton("+") { onRepMinChange(exercise.repRangeMin + 1) }
                        Text(
                            text = "–",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                        RoundStepButton("-") { onRepMaxChange(exercise.repRangeMax - 1) }
                        Text(
                            text = exercise.repRangeMax.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.Center,
                        )
                        RoundStepButton("+") { onRepMaxChange(exercise.repRangeMax + 1) }
                    }
                }
            } else {
                NumberRow(
                    label = "Time per set (sec)",
                    value = exercise.timePerSetSeconds,
                    onValueChange = onTimeChange,
                    step = 5,
                )
            }

            // Superset link button (shown for Single segments that have a next Single)
            if (showSupersetButton) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onToggleSuperset) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "Add to superset",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Superset",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared sub-components
// ---------------------------------------------------------------------------


/** A label + value + round -/+ buttons in a horizontal row. */
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
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        RoundStepButton("-") { onValueChange(value - step) }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.Center,
        )
        RoundStepButton("+") { onValueChange(value + step) }
    }
}

// ---------------------------------------------------------------------------
// Day picker
// ---------------------------------------------------------------------------

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
