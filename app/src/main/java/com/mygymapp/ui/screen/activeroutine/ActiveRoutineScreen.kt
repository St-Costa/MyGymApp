package com.mygymapp.ui.screen.activeroutine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mygymapp.ui.theme.JetBrainsMono
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.components.AutoSaveTextField
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.HeartRateBar
import com.mygymapp.ui.components.HrZoneTraceChart
import com.mygymapp.ui.components.LiveEcgCard
import com.mygymapp.ui.components.TonnageLineChart
import com.mygymapp.ui.components.trimpColor
import com.mygymapp.ui.theme.accentColor
import com.mygymapp.ui.theme.GitgraphGreen
import com.mygymapp.ui.theme.GitgraphRed
import com.mygymapp.ui.theme.SkippedColor
import com.mygymapp.ui.util.groupSupersets

// ---------------------------------------------------------------------------
// Exercise grouping (mirrors RoutineEditViewModel's segment model)
// ---------------------------------------------------------------------------

private sealed class ExerciseGroup {
    data class Single(val exercise: ActiveExerciseUi) : ExerciseGroup()
    data class Superset(val exercises: List<ActiveExerciseUi>) : ExerciseGroup()
}

private fun buildExerciseGroups(exercises: List<ActiveExerciseUi>): List<ExerciseGroup> =
    groupSupersets(
        items = exercises,
        isPairedWithNext = { it.supersetWithNext },
        single = { i -> ExerciseGroup.Single(exercises[i]) },
        group = { idxs -> ExerciseGroup.Superset(idxs.map { exercises[it] }) },
    )

// A superset chain never spans a category boundary, so the first exercise's category is the group's.
private fun ExerciseGroup.category(): SessionExerciseCategory = when (this) {
    is ExerciseGroup.Single -> exercise.category
    is ExerciseGroup.Superset -> exercises.first().category
}

@Composable
private fun SessionSectionHeader(
    category: SessionExerciseCategory,
    modifier: Modifier = Modifier,
) {
    // Excluded sections (warmup/daily) are muted; the counted workout section is highlighted.
    val label: String
    val color: androidx.compose.ui.graphics.Color
    when (category) {
        SessionExerciseCategory.WARMUP -> {
            label = "WARMUP"; color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        }
        SessionExerciseCategory.DAILY -> {
            label = "DAILY"; color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        }
        SessionExerciseCategory.NORMAL -> {
            label = "WORKOUT"; color = MaterialTheme.colorScheme.primary
        }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(color = color.copy(alpha = 0.4f), modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveRoutineScreen(
    onNavigateToExercise: (sessionId: String, exerciseId: String, type: ExerciseType) -> Unit,
    onNavigateToSuperset: (sessionId: String, exerciseIds: List<String>) -> Unit,
    onBack: () -> Unit,
    onSessionRegistered: (sessionId: String, date: String) -> Unit,
    viewModel: ActiveRoutineViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.sessionRegistered) {
        if (uiState.sessionRegistered) {
            onSessionRegistered(uiState.registeredSessionId, uiState.registeredSessionDate)
        }
    }

    // Disabled while the mandatory RPE prompt is up — back must not be a way to bypass it.
    BackHandler(enabled = !uiState.showRpePrompt) {
        viewModel.abandonSession()
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.routineName.ifBlank { "Workout" }) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.abandonSession()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            FullscreenLoading(padding)
        } else {
            val groups = remember(uiState.exercises) { buildExerciseGroups(uiState.exercises) }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Notes
                item(key = "notes") {
                    AutoSaveTextField(
                        value = uiState.notes,
                        onValueChange = {},
                        onSave = { viewModel.updateNotes(it) },
                        label = "Notes",
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }

                // Exercise groups (singles and supersets), with section headers when the
                // session mixes warmup / daily / workout exercises.
                val hasSections = groups.any { it.category() != SessionExerciseCategory.NORMAL }
                itemsIndexed(
                    items = groups,
                    key = { _, group ->
                        when (group) {
                            is ExerciseGroup.Single -> group.exercise.exerciseId
                            is ExerciseGroup.Superset ->
                                "ss_" + group.exercises.joinToString("_") { it.exerciseId }
                        }
                    },
                ) { index, group ->
                    val showHeader = hasSections &&
                        (index == 0 || groups[index - 1].category() != group.category())
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showHeader) {
                            SessionSectionHeader(
                                category = group.category(),
                                modifier = if (index == 0) Modifier else Modifier.padding(top = 8.dp),
                            )
                        }
                        when (group) {
                            is ExerciseGroup.Single -> {
                                ExerciseRow(
                                    exercise = group.exercise,
                                    onClick = {
                                        if (!group.exercise.completed) {
                                            onNavigateToExercise(
                                                uiState.sessionId,
                                                group.exercise.exerciseId,
                                                group.exercise.type,
                                            )
                                        }
                                    },
                                )
                            }
                            is ExerciseGroup.Superset -> {
                                val allCompleted = group.exercises.all { it.completed }
                                SupersetGroupRow(
                                    exercises = group.exercises,
                                    onClick = {
                                        if (!allCompleted) {
                                            onNavigateToSuperset(
                                                uiState.sessionId,
                                                group.exercises.map { it.exerciseId },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // Heart rate bar (live BPM + TRIMP)
                item(key = "hr_bar") {
                    HeartRateBar()
                }

                // Live %HRR trace over the coloured zone bands
                item(key = "hr_zone_trace") {
                    HrZoneTraceChart()
                }

                // Live ECG card (between HR bar and register button, per user spec)
                item(key = "live_ecg") {
                    LiveEcgCard()
                }

                // Register button — shown before progress chart
                item(key = "register_button") {
                    Button(
                        onClick = { viewModel.requestRegisterRoutine() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 8.dp),
                    ) {
                        Text("Registra routine")
                    }
                }

                // Progress section (shown when all completed)
                if (uiState.allCompleted) {
                    item(key = "progress") {
                        ProgressSection(
                            totalTonnage = uiState.totalTonnage,
                            previousTonnage = uiState.previousTonnage,
                            sessionCalories = uiState.sessionCalories,
                            sessionTrimp = uiState.sessionTrimp,
                            vo2max = uiState.vo2max,
                            sessionTonnage = uiState.sessionTonnage,
                            sessionBestE1RM = uiState.sessionBestE1RM,
                            sessionTonnageByBodypart = uiState.sessionTonnageByBodypart,
                            sessionLabels = uiState.sessionLabels,
                            allSessionCalories = uiState.allSessionCalories,
                            allSessionTrimp = uiState.allSessionTrimp,
                            allSessionVo2max = uiState.allSessionVo2max,
                            allSessionLabels = uiState.allSessionLabels,
                            selectedFilter = uiState.selectedChartFilter,
                            isLoadingChart = uiState.isLoadingChart,
                            onFilterSelected = { viewModel.selectChartFilter(it) },
                        )
                    }
                }
            }
        }
    }

    // Only after the session has finished loading, so the popup appears on top of
    // the loaded session and isn't flashed-then-closed while loading completes.
    if (!uiState.isLoading && uiState.isPowerliftingWeek && !uiState.powerliftingDismissed) {
        PowerliftingWeekOverlay(onDismiss = { viewModel.dismissPowerliftingOverlay() })
    }

    if (uiState.showRpePrompt) {
        SessionRpeDialog(
            onSubmit = { rpe -> viewModel.submitSessionRpe(rpe) },
        )
    }

    } // Box
}

/**
 * Mandatory session-RPE prompt (Foster method, 0-9 "how hard was this session"), shown
 * right after "Registra routine" is tapped. Not dismissible/skippable — a rating is
 * required before the session can be registered. See docs/SYNC.md — internal-load signal
 * that complements tonnage server-side.
 */
@Composable
private fun SessionRpeDialog(
    onSubmit: (Int) -> Boolean,
) {
    var selected by remember { mutableStateOf<Int?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = {}, // mandatory — no dismiss via outside tap or back press
        title = { Text("Quanto è stata dura questa sessione?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Da 0 (nessuno sforzo) a 9 (massimale)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                // Two rows of 5 so each chip stays tappable at normal phone widths.
                for (row in 0..1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (col in 0..4) {
                            val value = row * 5 + col
                            FilterChip(
                                selected = selected == value,
                                onClick = { selected = value },
                                label = { Text(value.toString()) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = selected != null,
                onClick = { selected?.let { onSubmit(it) } },
            ) { Text("Conferma") }
        },
    )
}

@Composable
private fun PowerliftingWeekOverlay(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(
                    "SETTIMANA POWERLIFTING",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Button(
                    onClick = onDismiss,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("Ho capito")
                }
            }
        }
    }
}

// Cardio exercises are timed, not set-based — show the configured block duration instead
// of a set count.
private fun ActiveExerciseUi.setsOrDurationLabel(): String =
    if (type == ExerciseType.CARDIO) {
        "%d:%02d".format(timePerSetSeconds / 60, timePerSetSeconds % 60)
    } else {
        "$setCount sets"
    }

@Composable
private fun ExerciseRow(
    exercise: ActiveExerciseUi,
    onClick: () -> Unit,
) {
    val baseBorderColor = if (exercise.completedEmpty) SkippedColor else exercise.type.accentColor()
    // Completed exercises get a less opaque border instead of a strikethrough — dimming the
    // border (not the text) is what reads as "done".
    val borderColor = if (exercise.completed) baseBorderColor.copy(alpha = 0.4f) else baseBorderColor

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left side: name only — dimmed when completed.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (exercise.completed) 0.4f else 1f),
            ) {
                Text(
                    text = exercise.exerciseName,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (exercise.substitutedForName != null) {
                    Text(
                        text = "Sostituito: ${exercise.substitutedForName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Right side: sets/duration when not completed; once completed, the badge
            // (skipped X > tonnage% / stretch seconds > "primo dato") takes that spot instead.
            ExerciseTrailingBadge(exercise)
        }
    }
}

/**
 * The right-hand status shown for one exercise in the active list. Priority:
 * skipped X  >  tonnage/1RM change (strength)  >  seconds performed (stretch)  >  "primo dato"
 * (strength, first time ever)  >  plain set count / block duration (not yet completed).
 * Extracted so [ExerciseRow] and [SupersetExerciseEntry] can't drift.
 */
@Composable
private fun ExerciseTrailingBadge(exercise: ActiveExerciseUi) {
    if (exercise.completedEmpty) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Saltato",
            tint = SkippedColor,
        )
    } else if (exercise.completed && exercise.tonnageChangePct != null) {
        TonnageAndRmChange(
            tonnageChangePct = exercise.tonnageChangePct,
            rmChangePct = exercise.rmChangePct,
            style = MaterialTheme.typography.bodySmall,
        )
    } else if (exercise.completed && exercise.stretchTotalSeconds != null) {
        Text(
            text = formatStretchDuration(exercise.stretchTotalSeconds),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = JetBrainsMono,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    } else if (exercise.completed && exercise.isFirstTimeTonnage) {
        Text(
            text = "primo dato",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    } else {
        Text(
            text = exercise.setsOrDurationLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

/** "45s" under a minute, "3:00" / "3:30" at or above one. */
private fun formatStretchDuration(totalSeconds: Int): String =
    if (totalSeconds < 60) "${totalSeconds}s"
    else "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)

@Composable
private fun SupersetGroupRow(
    exercises: List<ActiveExerciseUi>,
    onClick: () -> Unit,
) {
    val allCompleted = exercises.all { it.completed }
    // A superset chain can mix exercise types (FORZA + STRETCH, …), so the border is a
    // gradient from the first member's accent color (top) to the last member's (bottom).
    // When they're the same type it just reads as a solid color, exactly like ExerciseRow.
    fun accentFor(ex: ActiveExerciseUi) =
        if (ex.completedEmpty) SkippedColor else ex.type.accentColor()
    // Less opaque border once every exercise in the chain is done — mirrors ExerciseRow.
    val alpha = if (allCompleted) 0.4f else 1f
    val colors = exercises.map { accentFor(it).copy(alpha = alpha) }
    val borderBrush = Brush.verticalGradient(colors)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, borderBrush),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            exercises.forEachIndexed { i, ex ->
                if (i > 0) {
                    HorizontalDivider(
                        color = Color.Transparent,
                        thickness = 1.dp,
                        modifier = Modifier.background(
                            Brush.horizontalGradient(
                                listOf(
                                    colors[i - 1].copy(alpha = 0.3f),
                                    colors[i].copy(alpha = 0.3f),
                                ),
                            ),
                        ),
                    )
                }
                SupersetExerciseEntry(exercise = ex)
            }
        }
    }
}

@Composable
private fun SupersetExerciseEntry(exercise: ActiveExerciseUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left side: name only — dimmed when completed (mirrors ExerciseRow).
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (exercise.completed) 0.4f else 1f),
        ) {
            Text(
                text = exercise.exerciseName,
                style = MaterialTheme.typography.titleMedium,
            )
            if (exercise.substitutedForName != null) {
                Text(
                    text = "Sostituito: ${exercise.substitutedForName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // Right side: sets/duration when not completed; once completed, the badge
        // (skipped X > tonnage% / stretch seconds > "primo dato") takes that spot instead.
        ExerciseTrailingBadge(exercise)
    }
}

/** "+14%T +3%RM" — tonnage and 1RM change, each rounded to whole percent, each colored by its own sign. */
@Composable
private fun TonnageAndRmChange(
    tonnageChangePct: Double,
    rmChangePct: Double?,
    style: androidx.compose.ui.text.TextStyle,
) {
    // Stacked, no +/- sign — color alone carries the direction of the change. Each row is split
    // into a fixed-width number column (right-aligned) and a fixed-width label column
    // (left-aligned), both in JetBrainsMono, so "T"/"RM" line up regardless of digit count
    // (e.g. "11% T" / " 2% RM" — the "T" and "RM" start at the same x).
    val numberWidth = 26.dp
    val labelWidth = 18.dp
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%.0f%% ".format(kotlin.math.abs(tonnageChangePct)),
                style = style,
                fontFamily = JetBrainsMono,
                textAlign = TextAlign.End,
                color = if (tonnageChangePct > 0) GitgraphGreen else GitgraphRed,
                modifier = Modifier.width(numberWidth),
            )
            Text(
                text = "T",
                style = style,
                fontFamily = JetBrainsMono,
                textAlign = TextAlign.Start,
                color = if (tonnageChangePct > 0) GitgraphGreen else GitgraphRed,
                modifier = Modifier.width(labelWidth),
            )
        }
        if (rmChangePct != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "%.0f%% ".format(kotlin.math.abs(rmChangePct)),
                    style = style,
                    fontFamily = JetBrainsMono,
                    textAlign = TextAlign.End,
                    color = if (rmChangePct > 0) GitgraphGreen else GitgraphRed,
                    modifier = Modifier.width(numberWidth),
                )
                Text(
                    text = "RM",
                    style = style,
                    fontFamily = JetBrainsMono,
                    textAlign = TextAlign.Start,
                    color = if (rmChangePct > 0) GitgraphGreen else GitgraphRed,
                    modifier = Modifier.width(labelWidth),
                )
            }
        }
    }
}

@Composable
private fun ProgressSection(
    totalTonnage: Double,
    previousTonnage: Double?,
    sessionCalories: Double,
    sessionTrimp: Double,
    vo2max: Double,
    sessionTonnage: List<Double>,
    sessionBestE1RM: List<Double>,
    sessionTonnageByBodypart: Map<String, List<Double>>,
    sessionLabels: List<String>,
    allSessionCalories: List<Double>,
    allSessionTrimp: List<Double>,
    allSessionVo2max: List<Double>,
    allSessionLabels: List<String>,
    selectedFilter: String,
    isLoadingChart: Boolean,
    onFilterSelected: (String) -> Unit,
) {
    // Calories + TRIMP + VO2max summary
    if (sessionCalories > 0 || sessionTrimp > 0) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${sessionCalories.toInt()}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = androidx.compose.ui.graphics.Color(0xFFFF9800),
                    )
                    Text("kcal", style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${sessionTrimp.toInt()}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = trimpColor(sessionTrimp),
                    )
                    Text("TRIMP", style = MaterialTheme.typography.bodySmall)
                }
                if (vo2max > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "%.1f".format(vo2max),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text("VO2max", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isLoadingChart) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (sessionTonnage.isNotEmpty()) {
                val crossRoutineFilters = listOf("kcal", "TRIMP", "VO2max")
                // Filter chips: routine-specific tonnage + cross-routine metrics
                val filters = listOf("Totale") + sessionTonnageByBodypart.keys.toList() + crossRoutineFilters
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters) { filter ->
                        FilterChip(
                            selected = filter == selectedFilter,
                            onClick = { onFilterSelected(filter) },
                            label = { Text(filter) },
                        )
                    }
                }

                val isCrossRoutine = selectedFilter in crossRoutineFilters
                val chartData: List<Double>
                val chartLabels: List<String>
                val chartTitle: String

                when (selectedFilter) {
                    "kcal" -> {
                        chartData = allSessionCalories
                        chartLabels = allSessionLabels
                        val current = chartData.lastOrNull() ?: 0.0
                        chartTitle = "kcal: ${current.toInt()} (all routines)"
                    }
                    "TRIMP" -> {
                        chartData = allSessionTrimp
                        chartLabels = allSessionLabels
                        val current = chartData.lastOrNull() ?: 0.0
                        chartTitle = "TRIMP: ${current.toInt()} (all routines)"
                    }
                    "VO2max" -> {
                        chartData = allSessionVo2max.filter { it > 0 }
                        chartLabels = allSessionLabels.zip(allSessionVo2max)
                            .filter { it.second > 0 }.map { it.first }
                        val current = chartData.lastOrNull() ?: 0.0
                        chartTitle = "VO2max: %.1f (all routines)".format(current)
                    }
                    "Totale" -> {
                        chartData = sessionTonnage
                        chartLabels = sessionLabels
                        val current = chartData.lastOrNull() ?: 0.0
                        chartTitle = "Totale: %.1f kg".format(current)
                    }
                    else -> {
                        chartData = sessionTonnageByBodypart[selectedFilter] ?: sessionTonnage
                        chartLabels = sessionLabels
                        val current = chartData.lastOrNull() ?: 0.0
                        chartTitle = "$selectedFilter: %.1f kg".format(current)
                    }
                }

                Text(
                    text = chartTitle,
                    style = MaterialTheme.typography.titleMedium,
                )

                if (chartData.isNotEmpty()) {
                    TonnageLineChart(
                        data = chartData,
                        labels = chartLabels,
                        modifier = Modifier.fillMaxWidth(),
                        // 1RM only lines up point-for-point with tonnage on the "Totale" view —
                        // per-bodypart/cross-routine views don't have a matching e1RM series.
                        secondaryData = if (selectedFilter == "Totale" && sessionBestE1RM.size == chartData.size) {
                            sessionBestE1RM
                        } else null,
                    )
                }
            }
        }
    }
}
