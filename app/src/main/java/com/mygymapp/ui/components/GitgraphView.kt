package com.mygymapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mygymapp.ui.theme.GitgraphEmpty
import com.mygymapp.ui.theme.JetBrainsMono
import com.mygymapp.ui.theme.Primary
import com.mygymapp.ui.theme.GitgraphGreen
import com.mygymapp.ui.theme.GitgraphRed
import kotlin.math.abs
import kotlin.math.roundToInt

/** Stable id for the baseline-profile / macrobenchmark journey to wait on once the home's
 *  gitgraph has actually composed its rows (see docs/CHANGELOG.md § Baseline Profile).
 *  Surfaced to UiAutomator as a resource-id via `testTagsAsResourceId` set on the NavHost. */
const val GITGRAPH_TEST_TAG = "gitgraph"

enum class DayStatus {
    NONE,
    IMPROVED,
    REGRESSED,
}

// One day-of-week cell in the "today's schedule" row: the routine(s) assigned to that day
// (Routine.day), plus which one a tap should open — the first one not yet completed today,
// so completing one and tapping again moves on to the next (see MainViewModel).
//
// If that day of the current week has ALREADY been trained, the session* fields carry its
// outcome (same shape as a history square) so the cell renders like a history cell instead
// of a static "to do" cell — this is what makes yesterday's workout appear on the 5th row.
// The today cell is the exception: it's driven by GitgraphView's dedicated today* params,
// so MainViewModel leaves its session* fields empty (sessionStatus == NONE).
data class ScheduleCell(
    val routineNames: List<String> = emptyList(),
    val openRoutineId: String? = null,
    val sessionStatus: DayStatus = DayStatus.NONE,
    val sessionTonnageChange: Double? = null,
    val sessionCardioMinutes: Int? = null,
    val sessionRoutineName: String? = null,
    val sessionId: String? = null,
    val sessionDate: String? = null,
)

@Composable
fun GitgraphView(
    // 4 history rows (28 squares) covering the 4 weeks BEFORE the current one.
    days: List<DayStatus>,
    tonnageChanges: List<Double?> = emptyList(), // 28 values, one per square
    // Fallback shown when tonnageChanges is null for a square (all-cardio/warmup routine, or
    // no prior session to compare against): total cardio minutes for that day, e.g. "54m".
    cardioMinutes: List<Int?> = emptyList(),     // 28 values, one per square
    routineNames: List<String?> = emptyList(),   // 28 values, one per square
    // Session id/date for each of the 28 squares — a square with one is tappable.
    sessionIds: List<String?> = emptyList(),
    sessionDates: List<String?> = emptyList(),
    // 4 values, one per week-row: true = powerlifting week (brand-purple border)
    powerliftingWeeks: List<Boolean> = emptyList(),
    // Called when the user taps any of the 28 history squares that has a session.
    onCellClick: ((sessionId: String, date: String) -> Unit)? = null,
    // Schedule row (current week): 7 cells, Monday..Sunday, each the routine(s) assigned that
    // day. The cell matching todayDowIndex is overridden to render like a history cell instead
    // (see below) whenever today already has a session, so it reflects real progress rather
    // than staying a static "to do" cell once you've actually done it.
    scheduleCells: List<ScheduleCell> = emptyList(),
    todayDowIndex: Int = -1, // 0=Monday..6=Sunday; -1 = don't highlight any cell
    // Today's own session outcome, same shape as a history square — null status means today
    // has no session yet, so the cell renders as a schedule cell instead.
    todayStatus: DayStatus = DayStatus.NONE,
    todayTonnageChange: Double? = null,
    todayCardioMinutes: Int? = null,
    todayRoutineName: String? = null,
    todaySessionId: String? = null,
    todaySessionDate: String? = null,
    // Called when the user taps a schedule-row cell that has a routine to open; col = 0..6
    onScheduleCellClick: ((routineId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = 6.dp
    val horizontalPadding = 0.dp
    val shape = RoundedCornerShape(6.dp)
    val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

    BoxWithConstraints(modifier = modifier.testTag(GITGRAPH_TEST_TAG).padding(horizontal = horizontalPadding)) {
        // Every row below (header, history rows, schedule row) additionally pads itself
        // horizontally by 3.dp per side to keep rounded cell corners clear of the powerlifting-
        // week border — that 6.dp must come out of the same maxWidth cellSize is derived from,
        // or the 7th column (Sunday) silently gets clipped narrower than the other 6.
        val rowHorizontalInset = 6.dp
        val cellSize: Dp = (maxWidth - rowHorizontalInset - spacing * 6) / 7
        // Starting font size for text inside squares: big enough to need shrinking for short
        // strings like "7%", but converges quickly for longer ones like "100%".
        val squareMaxFontSp = remember(cellSize) { cellSize.value * 0.48f * 0.9f * 0.9f }
        // Schedule row (5th row) routine-name font: +10% over the 4 history rows above.
        val scheduleNameMaxFontSp = remember(cellSize) { cellSize.value * 0.24f * 1.1f }
        // Starting font size for the routine name, below the square.
        val nameMaxFontSp = remember(cellSize) { cellSize.value * 0.24f }

        Column(
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            // Day-of-week header
            Row(
                modifier = Modifier.padding(horizontal = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                dayLabels.forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.width(cellSize),
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
            }

            // 4 rows of 7 squares
            for (row in 0 until 4) {
                val isPowerliftingWeek = powerliftingWeeks.getOrElse(row) { false }
                val rowModifier = if (isPowerliftingWeek) {
                    // Border (2.dp) + padding (1.dp) = 3.dp per side, same horizontal inset as
                    // the non-powerlifting case below — cellSize is shared across all rows, so
                    // every row must consume the same horizontal space or the last column
                    // (Sunday) gets clipped narrower than the rest.
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(2.dp, Primary, RoundedCornerShape(8.dp))
                        .padding(1.dp)
                } else {
                    Modifier.padding(horizontal = 3.dp, vertical = 3.dp)
                }
                Row(
                    modifier = rowModifier,
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    for (col in 0 until 7) {
                        val index = row * 7 + col
                        val sessionId = sessionIds.getOrNull(index)
                        val sessionDate = sessionDates.getOrNull(index)
                        HistoryCell(
                            cellSize = cellSize,
                            shape = shape,
                            squareMaxFontSp = squareMaxFontSp,
                            nameMaxFontSp = nameMaxFontSp,
                            status = days.getOrElse(index) { DayStatus.NONE },
                            isToday = false,
                            change = tonnageChanges.getOrNull(index),
                            minutes = cardioMinutes.getOrNull(index),
                            routineName = routineNames.getOrNull(index),
                            onClick = if (sessionId != null && sessionDate != null && onCellClick != null) {
                                { onCellClick(sessionId, sessionDate) }
                            } else null,
                        )
                    }
                }
            }

            // Today's-schedule row, set apart from the 4 history rows above: tapping any cell
            // opens that day's routine (registered against today, regardless of which day's
            // cell was tapped — ActiveRoutineViewModel always saves with today's date). The
            // cell for today itself instead mirrors the current week's history cell once a
            // session exists for today, so it shows real progress (%, color, tap-to-view)
            // rather than staying frozen as a static "to do" cell.
            if (scheduleCells.isNotEmpty()) {
                Spacer(modifier = Modifier.height(spacing * 3))
                Row(
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    for (col in 0 until 7) {
                        val isToday = col == todayDowIndex
                        val todayHasSession = isToday && todayStatus != DayStatus.NONE
                        val cell = scheduleCells.getOrNull(col) ?: ScheduleCell()
                        // A past day of this week that's already been trained: render its
                        // session as a history cell, same as "today" does once done.
                        val pastDayHasSession = !isToday && cell.sessionStatus != DayStatus.NONE

                        if (todayHasSession) {
                            HistoryCell(
                                cellSize = cellSize,
                                shape = shape,
                                squareMaxFontSp = squareMaxFontSp,
                                nameMaxFontSp = scheduleNameMaxFontSp,
                                status = todayStatus,
                                isToday = true,
                                change = todayTonnageChange,
                                minutes = todayCardioMinutes,
                                routineName = todayRoutineName,
                                onClick = if (todaySessionId != null && todaySessionDate != null && onCellClick != null) {
                                    { onCellClick(todaySessionId, todaySessionDate) }
                                } else null,
                            )
                        } else if (pastDayHasSession) {
                            HistoryCell(
                                cellSize = cellSize,
                                shape = shape,
                                squareMaxFontSp = squareMaxFontSp,
                                nameMaxFontSp = scheduleNameMaxFontSp,
                                status = cell.sessionStatus,
                                isToday = false,
                                change = cell.sessionTonnageChange,
                                minutes = cell.sessionCardioMinutes,
                                routineName = cell.sessionRoutineName,
                                onClick = if (cell.sessionId != null && cell.sessionDate != null && onCellClick != null) {
                                    { onCellClick(cell.sessionId, cell.sessionDate) }
                                } else null,
                            )
                        } else {
                            val canOpen = cell.openRoutineId != null && onScheduleCellClick != null
                            ScheduleCellView(
                                cellSize = cellSize,
                                shape = shape,
                                nameMaxFontSp = scheduleNameMaxFontSp,
                                cell = cell,
                                isToday = isToday,
                                onClick = if (canOpen) ({ onScheduleCellClick!!(cell.openRoutineId!!) }) else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One square in the 4 history rows (or the "today" cell once it has a session): color from
 * [status], %/cardio-minutes centered inside, routine name on its own line below the square. */
@Composable
private fun HistoryCell(
    cellSize: Dp,
    shape: RoundedCornerShape,
    squareMaxFontSp: Float,
    nameMaxFontSp: Float,
    status: DayStatus,
    isToday: Boolean,
    change: Double?,
    minutes: Int?,
    routineName: String?,
    onClick: (() -> Unit)?,
) {
    val color = when (status) {
        DayStatus.NONE -> GitgraphEmpty
        DayStatus.IMPROVED -> GitgraphGreen
        DayStatus.REGRESSED -> GitgraphRed
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(cellSize)
            .clip(shape)
            .background(color)
            .then(if (isToday) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    ) {
        // % stays centered in the square; the routine name — what lets an out-of-schedule
        // day (routine done on the "wrong" day) still be identified at a glance — is pinned
        // to the bottom edge instead, so it never pushes the % off-center. When there's no
        // tonnage % (all-cardio/warmup routine, or no prior session to compare against), fall
        // back to total cardio minutes.
        if (change != null) {
            AutoShrinkText(
                text = "${abs(change).roundToInt()}%",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cellSize * 0.75f)
                    .offset(y = -cellSize * 0.05f),
                maxFontSizeSp = squareMaxFontSp,
                minFontSizeSp = 6f,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMono,
                color = Color.Black,
            )
        } else if (minutes != null) {
            AutoShrinkText(
                text = "${minutes}m",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cellSize * 0.75f)
                    .offset(y = -cellSize * 0.05f),
                maxFontSizeSp = squareMaxFontSp,
                minFontSizeSp = 6f,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMono,
                color = Color.Black,
            )
        }
        if (routineName != null) {
            AutoShrinkText(
                text = routineName,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 0.5.dp),
                maxFontSizeSp = nameMaxFontSp,
                minFontSizeSp = 5f,
                color = Color.Black.copy(alpha = 0.7f),
                tightBottom = true,
            )
        }
    }
}

/** One square in the schedule row: neutral color, the day's assigned routine name(s) stacked
 * below the square (not inside — there's no % to show, it hasn't happened yet). */
@Composable
private fun ScheduleCellView(
    cellSize: Dp,
    shape: RoundedCornerShape,
    nameMaxFontSp: Float,
    cell: ScheduleCell,
    isToday: Boolean,
    onClick: (() -> Unit)?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(cellSize),
    ) {
        Box(
            modifier = Modifier
                .size(cellSize)
                .clip(shape)
                .background(GitgraphEmpty)
                .then(if (isToday) Modifier.border(2.dp, Color.White, shape) else Modifier)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        )
        cell.routineNames.forEach { name ->
            AutoShrinkText(
                text = name,
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                maxFontSizeSp = nameMaxFontSp,
                minFontSizeSp = 5f,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Text that shrinks from [maxFontSizeSp] until it fits the available width, down to [minFontSizeSp].
 * Starts large so each call naturally maximizes the font for its content and container.
 */
@Composable
private fun AutoShrinkText(
    text: String,
    modifier: Modifier = Modifier,
    maxFontSizeSp: Float = 14f,
    minFontSizeSp: Float = 6f,
    fontWeight: FontWeight = FontWeight.Normal,
    fontFamily: FontFamily? = null,
    color: Color = Color.White,
    // When true, collapses the font's built-in leading so the glyphs themselves sit flush
    // against the bottom of the layout box instead of floating a few px above it (Text
    // normally reserves space for descenders/leading above and below the visible glyphs).
    tightBottom: Boolean = false,
) {
    var fontSizeSp by remember(text, maxFontSizeSp) { mutableStateOf(maxFontSizeSp) }
    Text(
        text = text,
        modifier = modifier,
        fontSize = fontSizeSp.sp,
        fontFamily = fontFamily,
        lineHeight = if (tightBottom) fontSizeSp.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        style = if (tightBottom) {
            androidx.compose.ui.text.TextStyle(
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Bottom,
                    trim = LineHeightStyle.Trim.Both,
                ),
            )
        } else androidx.compose.ui.text.TextStyle.Default,
        fontWeight = fontWeight,
        maxLines = Int.MAX_VALUE,
        softWrap = false,         // honour explicit \n but never word-wrap
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        color = color,
        onTextLayout = { result ->
            if ((result.didOverflowWidth || result.didOverflowHeight) && fontSizeSp > minFontSizeSp) {
                fontSizeSp = (fontSizeSp * 0.85f).coerceAtLeast(minFontSizeSp)
            }
        },
    )
}
