package com.mygymapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.polar.PolarLinkStatus
import com.mygymapp.ui.theme.GitgraphGreen
import com.mygymapp.ui.theme.SignalAmber
import com.mygymapp.ui.theme.SignalGreen
import com.mygymapp.ui.theme.SignalRed

private val SemaphoreGreen = SignalGreen
private val SemaphoreYellow = SignalAmber
private val SemaphoreRed = SignalRed

/** Beats needed before the regular-% semaphore gets a verdict color (Sprint 5). */
private const val MIN_BEATS_FOR_REGULAR_PCT = 10

@Composable
fun LiveEcgCard(
    modifier: Modifier = Modifier,
    viewModel: HeartRateBarViewModel = hiltViewModel(),
) {
    val polarManager = viewModel.polarManager
    val linkStatus by polarManager.linkStatus.collectAsState()
    val waveform by polarManager.ecgWaveform.collectAsState()
    val snapshot by polarManager.liveEcgSnapshot.collectAsState()
    // HRR intentionally not shown live — it's more meaningful aggregated
    // post-session and in the 4-week trend card.
    // Irregularities (premature/pauses/uneven) and cardiac drift are still computed and
    // saved (LiveEcgAnalyzer / PolarManager.liveCardiacDrift) — just not surfaced here
    // per user request. Only beats + regular% stay on this card.

    // Hide on NO_SIGNAL too (link up but strap silent) so we don't show a frozen trace.
    if (linkStatus != PolarLinkStatus.CONNECTED) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Waveform
            EcgWaveformView(
                samples = waveform,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0A0A0A)),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            // Beats + Regular %
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Beats: ${snapshot.beats}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // No verdict before the statistics mean anything: "—" with no beats,
                    // value with a neutral dot below MIN_BEATS (a single uneven beat out
                    // of 3 would otherwise paint an unjustified red).
                    val statsReady = snapshot.beats >= MIN_BEATS_FOR_REGULAR_PCT
                    Text(
                        text = if (snapshot.beats == 0) {
                            "Regular: —"
                        } else {
                            "Regular: %.1f%%".format(snapshot.regularPct)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    SemaphoreDot(
                        color = if (statsReady) {
                            regularColor(snapshot.regularPct)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SemaphoreDot(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun regularColor(pct: Double): Color = when {
    pct >= 98.0 -> SemaphoreGreen
    pct >= 95.0 -> SemaphoreYellow
    else -> SemaphoreRed
}

@Composable
private fun EcgWaveformView(
    samples: IntArray,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (samples.isEmpty()) return@Canvas
        val width = size.width
        val height = size.height
        val midY = height / 2f

        // Auto-scale: find max absolute value in the window
        var maxAbs = 1
        for (v in samples) {
            val a = if (v < 0) -v else v
            if (a > maxAbs) maxAbs = a
        }
        val verticalPad = height * 0.1f
        val usableHalf = (height / 2f) - verticalPad
        val scale = usableHalf / maxAbs.toFloat()

        val path = Path()
        val step = width / (samples.size - 1).coerceAtLeast(1).toFloat()
        for (i in samples.indices) {
            val x = i * step
            val y = midY - samples[i] * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = GitgraphGreen,
            style = Stroke(width = 2f),
        )
    }
}
