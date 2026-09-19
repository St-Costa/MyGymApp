package com.mygymapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mygymapp.data.model.Tempo
import com.mygymapp.data.model.animationDurationMs
import com.mygymapp.data.model.isSkipped
import com.mygymapp.data.model.toToken
import com.mygymapp.ui.theme.JetBrainsMono

/** [TempoOutlinedGlyph] index: 0 = negativa, 1 = stretch, 2 = concentrica, 3 = peak. */

/**
 * Infinite Poliquin tempo loop: the 4 phase boxes fill bottom-to-top in sequence, each
 * over its real duration (X = 0.35s fast fill, see TempoDuration.animationDurationMs).
 * Zero phases render faded and are skipped entirely. Glyphs (custom canvas icon + token)
 * are constant near-white with a black outline ring — readable on both the empty dark
 * square and the violet fill, with no color flip mid-fill.
 *
 * Timing is driven by the vsync frame clock ([withFrameNanos]), not by an animation
 * spec — so a rep phase always lasts its real seconds even if the system's animator
 * duration scale is reduced or off (which would make tween-based timing run fast).
 *
 * The squares are the shared [TempoSquareShell] (same size/border/fonts as the editor).
 */
@Composable
fun TempoAnimation(
    tempo: Tempo,
    modifier: Modifier = Modifier,
) {
    // No remember() before this point: activePhases() is 4 items, and an early return
    // before any hook keeps the hook order stable across recompositions.
    if (tempo.isDisabled) return
    val active = tempo.activePhases()
    if (active.isEmpty()) return

    var currentPhaseIdx by remember(tempo) { mutableIntStateOf(active.first().first) }
    var fillFraction by remember(tempo) { mutableFloatStateOf(0f) }

    LaunchedEffect(tempo) {
        while (true) {
            for ((idx, duration) in active) {
                currentPhaseIdx = idx
                fillFraction = 0f
                val phaseMs = duration.animationDurationMs().coerceAtLeast(1)
                val startNanos = withFrameNanos { it }
                while (true) {
                    val elapsedMs = (withFrameNanos { it } - startNanos) / 1_000_000L
                    if (elapsedMs >= phaseMs) break
                    fillFraction = elapsedMs.toFloat() / phaseMs.toFloat()
                }
                fillFraction = 1f
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Tempo ${tempo.toFileString()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        ) {
            for (i in 0..3) {
                val duration = tempo.phaseAt(i)
                val skipped = duration.isSkipped()
                val isFilling = i == currentPhaseIdx && !skipped
                val fill = if (isFilling) fillFraction else 0f
                // Constant high-visibility glyph: canvas-drawn near-white with a black
                // outline ring (no Icon composable, no color flip) — same on the empty
                // dark square and the violet fill.
                TempoSquareShell(fillFraction = fill) {
                    // Same 32dp top slot as the editor number, outlined duration token
                    // below like the editor label.
                    TempoSquareTopSlot {
                        TempoPhaseGlyph(
                            phaseIndex = i,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    TempoOutlinedToken(
                        text = duration.toToken(),
                        foreground = Color(0xFFF5F5F5),
                        outlineColor = Color.Black,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = JetBrainsMono,
                        alpha = if (skipped) 0.35f else 1f,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 0.5.dp)
                            .height(20.dp),
                    )
                }
            }
        }
    }
}
