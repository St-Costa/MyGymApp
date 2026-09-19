package com.mygymapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mygymapp.data.model.MAX_TEMPO_SECONDS
import com.mygymapp.data.model.Tempo
import com.mygymapp.data.model.TempoDuration
import com.mygymapp.data.model.isSkipped
import com.mygymapp.data.model.toToken
import com.mygymapp.ui.theme.JetBrainsMono

/**
 * Big triangular stepper (up-pointing triangle above, down-pointing below each phase box),
 * as wide as the square side. No wrap anywhere: the down-triangle disables itself on X
 * (below X is unreachable) and the up-triangle on 5.
 */
@Composable
private fun TempoTriangle(
    up: Boolean,
    contentDescription: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(TempoBoxSide)
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClickLabel = contentDescription, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                if (up) {
                    moveTo(0f, h)
                    lineTo(w / 2f, 0f)
                    lineTo(w, h)
                } else {
                    moveTo(0f, 0f)
                    lineTo(w / 2f, h)
                    lineTo(w, 0f)
                }
                close()
            }
            drawPath(path, color = color)
        }
    }
}

/**
 * 4-phase Poliquin tempo stepper (FORZA only): square boxes attached 3dp apart (same
 * [TempoSquareShell] as the execution animation) with a triangle above (increment) and
 * below (decrement) each one. Fixed scale X-0-1-2-3-4-5, no wrap. All-zero = disabled
 * (nothing shown during the workout).
 */
@Composable
fun TempoEditor(
    tempo: Tempo,
    onStep: (phaseIndex: Int, delta: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Poliquin times",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        ) {
            for (i in 0..3) {
                val duration = tempo.phaseAt(i)
                val skipped = duration.isSkipped()
                val isExplosive = duration is TempoDuration.Explosive
                val isMax = duration == TempoDuration.Seconds(MAX_TEMPO_SECONDS)
                val contentAlpha = if (skipped) 0.35f else 1f
                val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                Column(
                    modifier = Modifier.width(TempoBoxSide),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    TempoTriangle(
                        up = true,
                        contentDescription = "Increase ${TempoPhaseLabels[i]}",
                        color = if (isMax) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                        } else {
                            contentColor
                        },
                        enabled = !isMax,
                        onClick = { onStep(i, +1) },
                    )
                    TempoSquareShell {
                        TempoSquareTopSlot {
                            Text(
                                text = duration.toToken(),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = JetBrainsMono,
                                textAlign = TextAlign.Center,
                                color = contentColor,
                            )
                        }
                        TempoSquareBottomText(
                            text = TempoPhaseLabels[i],
                            alpha = if (skipped) 0.35f else 0.7f,
                        )
                    }
                    TempoTriangle(
                        up = false,
                        contentDescription = "Decrease ${TempoPhaseLabels[i]}",
                        color = if (isExplosive) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                        } else {
                            contentColor
                        },
                        // Below X is unreachable: the down-triangle disables itself on X.
                        enabled = !isExplosive,
                        onClick = { onStep(i, -1) },
                    )
                }
            }
        }
    }
}
