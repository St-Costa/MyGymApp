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
import com.mygymapp.data.polar.ConnectionState

private val SemaphoreGreen = Color(0xFF66BB6A)
private val SemaphoreYellow = Color(0xFFFFCA28)
private val SemaphoreRed = Color(0xFFEF5350)

@Composable
fun LiveEcgCard(
    modifier: Modifier = Modifier,
    viewModel: HeartRateBarViewModel = hiltViewModel(),
) {
    val polarManager = viewModel.polarManager
    val connectionState by polarManager.connectionState.collectAsState()
    val waveform by polarManager.ecgWaveform.collectAsState()
    val snapshot by polarManager.liveEcgSnapshot.collectAsState()
    // HRR intentionally not shown live — it's more meaningful aggregated
    // post-session and in the 4-week trend card.
    // Irregularities (premature/pauses/uneven) and cardiac drift are still computed and
    // saved (LiveEcgAnalyzer / PolarManager.liveCardiacDrift) — just not surfaced here
    // per user request. Only beats + regular% stay on this card.

    if (connectionState != ConnectionState.CONNECTED) return

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
                    Text(
                        text = "Regular: %.1f%%".format(snapshot.regularPct),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    SemaphoreDot(color = regularColor(snapshot.regularPct))
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
            color = Color(0xFF4CAF50),
            style = Stroke(width = 2f),
        )
    }
}
