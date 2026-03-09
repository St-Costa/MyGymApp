package com.mygymapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mygymapp.ui.theme.GitgraphEmpty
import com.mygymapp.ui.theme.GitgraphGreen
import com.mygymapp.ui.theme.GitgraphRed
import kotlin.math.abs
import kotlin.math.roundToInt

enum class DayStatus {
    NONE,
    IMPROVED,
    REGRESSED,
}

@Composable
fun GitgraphView(
    days: List<DayStatus>,
    todayIndex: Int,
    tonnageChanges: List<Double?> = emptyList(),      // 28 values, one per square
    lastWeekRoutineNames: List<String?> = emptyList(), // 7 values, last row only
    // Called when user taps a last-row cell that has a session; col = 0..6 (Mon–Sun)
    onLastRowCellClick: ((col: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = 6.dp
    val horizontalPadding = 12.dp
    val shape = RoundedCornerShape(6.dp)
    val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

    BoxWithConstraints(modifier = modifier.padding(horizontal = horizontalPadding)) {
        val cellSize: Dp = (maxWidth - spacing * 6) / 7
        // Starting font size for text inside squares: big enough to need shrinking for short
        // strings like "7%", but converges quickly for longer ones like "100%".
        val squareMaxFontSp = remember(cellSize) { cellSize.value * 0.48f }
        // Starting font size for routine name labels below: one word per line.
        val nameMaxFontSp = remember(cellSize) { cellSize.value * 0.55f }

        Column(
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            // Day-of-week header
            Row(
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    for (col in 0 until 7) {
                        val index = row * 7 + col
                        val status = days.getOrElse(index) { DayStatus.NONE }
                        val color = when (status) {
                            DayStatus.NONE -> GitgraphEmpty
                            DayStatus.IMPROVED -> GitgraphGreen
                            DayStatus.REGRESSED -> GitgraphRed
                        }
                        val isToday = index == todayIndex
                        val change = tonnageChanges.getOrNull(index)
                        val isLastRow = row == 3
                        val hasSession = status != DayStatus.NONE

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(cellSize)
                                .clip(shape)
                                .background(color)
                                .then(
                                    if (isToday) Modifier.border(2.dp, Color.White, shape)
                                    else Modifier
                                )
                                .then(
                                    if (isLastRow && hasSession && onLastRowCellClick != null)
                                        Modifier.clickable { onLastRowCellClick(col) }
                                    else Modifier
                                ),
                        ) {
                            if (change != null) {
                                AutoShrinkText(
                                    text = "${abs(change).roundToInt()}%",
                                    modifier = Modifier.fillMaxWidth(),
                                    maxFontSizeSp = squareMaxFontSp,
                                    minFontSizeSp = 6f,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                )
                            }
                        }
                    }
                }
            }

            // Routine name labels for the current week:
            // each word on its own line, font maximized to fill cell width.
            if (lastWeekRoutineNames.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    for (col in 0 until 7) {
                        val name = lastWeekRoutineNames.getOrNull(col)
                        // Split on spaces so each word is on its own line,
                        // then shrink the font until the longest word fits.
                        val multilineText = name?.trim()?.split(" ")
                            ?.filter { it.isNotEmpty() }
                            ?.joinToString("\n") ?: ""
                        AutoShrinkText(
                            text = multilineText,
                            modifier = Modifier.width(cellSize),
                            maxFontSizeSp = nameMaxFontSp,
                            minFontSizeSp = 5f,
                            color = if (name != null) Color.White.copy(alpha = 0.7f)
                                    else Color.Transparent,
                        )
                    }
                }
            }
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
    color: Color = Color.White,
) {
    var fontSizeSp by remember(text, maxFontSizeSp) { mutableStateOf(maxFontSizeSp) }
    Text(
        text = text,
        modifier = modifier,
        fontSize = fontSizeSp.sp,
        fontWeight = fontWeight,
        maxLines = Int.MAX_VALUE,
        softWrap = false,         // honour explicit \n but never word-wrap
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        color = color,
        onTextLayout = { result ->
            if (result.didOverflowWidth && fontSizeSp > minFontSizeSp) {
                fontSizeSp = (fontSizeSp * 0.85f).coerceAtLeast(minFontSizeSp)
            }
        },
    )
}
