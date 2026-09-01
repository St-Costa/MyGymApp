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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.mygymapp.ui.components.ScrollPickerInput
import com.mygymapp.ui.theme.CardioColor
import com.mygymapp.ui.theme.ForzaColor
import com.mygymapp.ui.theme.StretchColor
import com.mygymapp.ui.util.MAX_SUPERSET_SIZE
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
                    if (!uiState.isNew && !uiState.isFixedDaily) {
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
                readOnly = uiState.isFixedDaily,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!uiState.isFixedDaily) {
                DayPicker(
                    selectedDay = uiState.day,
                    onDaySelected = viewModel::onDayChange,
                )
            }

            OutlinedTextField(
                value = uiState.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = if (uiState.isFixedDaily) "Daily fixed exercises" else "Exercises",
                style = MaterialTheme.typography.titleLarge,
            )

            if (uiState.isFixedDaily) {
                Text(
                    text = "These exercises are added at the start of every session and are " +
                        "excluded from tonnage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            ExerciseDragDropList(
                exercises = uiState.exercises,
                showWarmupDivider = !uiState.isFixedDaily,
                warmupCount = uiState.warmupCount,
                onMoveLineUp = viewModel::moveWarmupLineUp,
                onMoveLineDown = viewModel::moveWarmupLineDown,
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
    showWarmupDivider: Boolean,
    warmupCount: Int,
    onMoveLineUp: () -> Unit,
    onMoveLineDown: () -> Unit,
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
        // Running count of exercises rendered so far, used to place the warmup line.
        var cumExercises = 0
        segments.forEachIndexed { segIdx, segment ->
            if (showWarmupDivider && cumExercises == warmupCount) {
                WarmupDividerRow(
                    canMoveUp = warmupCount > 0,
                    canMoveDown = warmupCount < exercises.size,
                    onMoveUp = onMoveLineUp,
                    onMoveDown = onMoveLineDown,
                )
            }
            cumExercises += segment.indices().size
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
                        // Show the superset link button when the next segment can still absorb
                        // this one without the chain exceeding MAX_SUPERSET_SIZE, and never
                        // across the warmup line (would straddle warmup/normal).
                        // Cardio exercises can never be linked into a superset — they're a
                        // time-based block (see CardioExerciseScreen), not a set-based one, and
                        // SupersetViewModel/Screen only know how to interleave FORZA/STRETCH sets.
                        val nextSegment = segments.getOrNull(segIdx + 1)
                        val nextIndices = nextSegment?.indices() ?: emptyList()
                        val nextIsCardioFree = nextIndices.all {
                            exercises[it].exerciseType != ExerciseType.CARDIO
                        }
                        val canLink = nextSegment != null &&
                            1 + nextIndices.size <= MAX_SUPERSET_SIZE &&
                            exIdx + 1 != warmupCount &&
                            exercises[exIdx].exerciseType != ExerciseType.CARDIO &&
                            nextIsCardioFree
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

                    is ExerciseSegment.Superset -> {
                        val idxs = segment.indices
                        // Whether the chain can absorb the exercise right after it: the next
                        // segment must be a Single, the chain must still be under the cap, and
                        // the join must not straddle the warmup line or pull in a CARDIO block.
                        val afterSegment = segments.getOrNull(segIdx + 1)
                        val canExtend = afterSegment is ExerciseSegment.Single &&
                            idxs.size < MAX_SUPERSET_SIZE &&
                            idxs.last() + 1 != warmupCount &&
                            exercises[afterSegment.index].exerciseType != ExerciseType.CARDIO
                        SupersetContainer(
                            exercises = idxs.map { exercises[it] },
                            isDragging = isDragging,
                            canExtend = canExtend,
                            // Extending the chain = set supersetWithNext on its last member,
                            // linking it forward to the next Single.
                            onExtend = { onToggleSuperset(idxs.last()) },
                            onDragStart = { dragState = DragState(segIdx, 0f, segIdx) },
                            onDrag = { dy ->
                                val state = dragState ?: return@SupersetContainer
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
                            onRemoveAt = { local -> onRemove(idxs[local]) },
                            onSetsChangeAt = { local, v -> onSetsChange(idxs[local], v) },
                            onRepMinChangeAt = { local, v -> onRepMinChange(idxs[local], v) },
                            onRepMaxChangeAt = { local, v -> onRepMaxChange(idxs[local], v) },
                            onTimeChangeAt = { local, v -> onTimeChange(idxs[local], v) },
                            // Unlinking the whole chain: clear the flag on every member except
                            // the last, so it collapses back to standalone singles.
                            onUnlink = { idxs.dropLast(1).forEach { onToggleSuperset(it) } },
                        )
                    }
                }
            }
        }
        // Line sitting below every exercise (all exercises are warmup).
        if (showWarmupDivider && cumExercises == warmupCount) {
            WarmupDividerRow(
                canMoveUp = warmupCount > 0,
                canMoveDown = false,
                onMoveUp = onMoveLineUp,
                onMoveDown = onMoveLineDown,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Warmup divider line (positional; exercises above it are warmup)
// ---------------------------------------------------------------------------

@Composable
private fun WarmupDividerRow(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            HorizontalDivider(color = color, thickness = 2.dp)
            Text(
                text = "↑ Warmup · Workout ↓",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Move line up",
                tint = if (canMoveUp) color else color.copy(alpha = 0.3f),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Move line down",
                tint = if (canMoveDown) color else color.copy(alpha = 0.3f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Superset container (purple frame wrapping 2–3 exercise cards)
// ---------------------------------------------------------------------------

@Composable
private fun SupersetContainer(
    exercises: List<RoutineExerciseUi>,
    isDragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onRemoveAt: (local: Int) -> Unit,
    onSetsChangeAt: (local: Int, value: Int) -> Unit,
    onRepMinChangeAt: (local: Int, value: Int) -> Unit,
    onRepMaxChangeAt: (local: Int, value: Int) -> Unit,
    onTimeChangeAt: (local: Int, value: Int) -> Unit,
    onUnlink: () -> Unit,
    canExtend: Boolean = false,
    onExtend: () -> Unit = {},
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
                    text = if (exercises.size >= 3) "SUPERSET ×${exercises.size}" else "SUPERSET",
                    style = MaterialTheme.typography.labelMedium,
                    color = primaryColor,
                    modifier = Modifier.weight(1f),
                )
                if (canExtend) {
                    IconButton(onClick = onExtend) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = "Add next exercise to superset",
                            tint = primaryColor,
                        )
                    }
                }
                IconButton(onClick = onUnlink) {
                    Icon(
                        Icons.Default.LinkOff,
                        contentDescription = "Remove superset",
                        tint = primaryColor,
                    )
                }
            }

            // Members (no drag handle, no superset button), separated by dividers.
            exercises.forEachIndexed { local, ex ->
                if (local > 0) {
                    HorizontalDivider(color = primaryColor.copy(alpha = 0.4f), thickness = 1.dp)
                }
                RoutineExerciseItem(
                    exercise = ex,
                    isDragging = false,
                    onDragStart = {},
                    onDrag = {},
                    onDragEnd = {},
                    onDragCancel = {},
                    onRemove = { onRemoveAt(local) },
                    onSetsChange = { onSetsChangeAt(local, it) },
                    onRepMinChange = { onRepMinChangeAt(local, it) },
                    onRepMaxChange = { onRepMaxChangeAt(local, it) },
                    onTimeChange = { onTimeChangeAt(local, it) },
                    showDragHandle = false,
                    showSupersetButton = false,
                )
            }
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
        ExerciseType.CARDIO -> CardioColor
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
            // Header row: drag handle | name | superset link | delete
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showSupersetButton) {
                        IconButton(onClick = onToggleSuperset) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = "Add to superset",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // Sets/rep-range are meaningless for CARDIO — no pre-configured set count, blocks
            // are appended live from "Inizia cardio"/"Termina cardio" in CardioExerciseScreen
            // (ActiveRoutineViewModel builds an empty set list for CARDIO regardless of
            // exercise.sets here). It reuses timePerSetSeconds instead, but as a single total
            // countdown duration for the whole block rather than "per set" — CardioExerciseScreen
            // counts down from this value once "Inizia cardio" is pressed, continuing into
            // overtime rather than auto-stopping if the user doesn't tap "Termina cardio" first.
            if (exercise.exerciseType == ExerciseType.CARDIO) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Durata cardio (min):", style = MaterialTheme.typography.bodyMedium)
                    // Long-press either button to jump by 10 min at once — same widget/behavior
                    // as the weight picker on strength sets (SupersetScreen).
                    ScrollPickerInput(
                        value = exercise.timePerSetSeconds / 60,
                        onValueChange = { onTimeChange(it.toInt() * 60) },
                        buttonStep = 1.0,
                        minValue = 0.0,
                        longPressRepeatStep = 10.0,
                        enableScroll = false,
                    )
                }
            } else {
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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.width(8.dp))
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
