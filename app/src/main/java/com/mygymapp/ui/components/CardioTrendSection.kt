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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mygymapp.data.repository.CardioMetric
import com.mygymapp.data.repository.CardioRhythmCounters
import com.mygymapp.data.repository.CardioTrendReport
import com.mygymapp.data.repository.TrendDirection
import com.mygymapp.data.repository.TrendSemaphore

private val SemaphoreGreen = Color(0xFF66BB6A)
private val SemaphoreYellow = Color(0xFFFFCA28)
private val SemaphoreRed = Color(0xFFEF5350)
private val SemaphoreGray = Color(0xFF3A3A3A)

@Composable
fun CardioTrendSection(report: CardioTrendReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "📊 Cardio trend · last 4 weeks",
                style = MaterialTheme.typography.titleMedium,
            )
            if (report.sessionCount > 0) {
                Text(
                    "${report.sessionCount} sessions · avg duration ${report.avgDurationMinutes} min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!report.hasEnoughData) {
                Text(
                    "Not enough data yet. Complete more sessions with Polar connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            // Metrics in 2-column grid
            val pairs = report.metrics.chunked(2)
            for (pair in pairs) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricCell(pair[0], modifier = Modifier.weight(1f))
                    if (pair.size > 1) {
                        MetricCell(pair[1], modifier = Modifier.weight(1f))
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            // Rhythm counters
            RhythmSection(report.rhythm)

            if (report.alerts.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Text(
                    "⚠ Alerts",
                    style = MaterialTheme.typography.titleSmall,
                )
                for (alert in report.alerts) {
                    Text(
                        "• $alert",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCell(metric: CardioMetric, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Label + semaphore dot
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                metric.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            SemaphoreDot(color = semaphoreColor(metric.semaphore))
        }
        // Sparkline
        Sparkline(
            data = metric.series,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            color = semaphoreColor(metric.semaphore),
        )
        // Current value + delta + arrow
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${metric.formatCurrent.format(metric.current)} ${metric.unitLabel}".trim(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(6.dp))
            val arrow = when (metric.direction) {
                TrendDirection.UP -> "↑"
                TrendDirection.DOWN -> "↓"
                TrendDirection.FLAT -> "→"
            }
            Text(
                text = "$arrow ${metric.formatDelta.format(metric.delta)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RhythmSection(counters: CardioRhythmCounters) {
    Text(
        "Ritmo · ultimi 30 giorni",
        style = MaterialTheme.typography.titleSmall,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RhythmRow("AFib episodes", counters.afibEpisodes, counters.afibSemaphore)
        RhythmRow("Pauses (>2s)", counters.pauses, counters.pausesSemaphore)
        RhythmRow("Premature", counters.premature, counters.prematureSemaphore)
        RhythmRow("Uneven", counters.uneven, counters.unevenSemaphore)
    }
}

@Composable
private fun RhythmRow(label: String, value: Int, semaphore: TrendSemaphore) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            "$value",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(8.dp))
        SemaphoreDot(color = semaphoreColor(semaphore))
    }
}

@Composable
private fun SemaphoreDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun semaphoreColor(s: TrendSemaphore): Color = when (s) {
    TrendSemaphore.GREEN -> SemaphoreGreen
    TrendSemaphore.YELLOW -> SemaphoreYellow
    TrendSemaphore.RED -> SemaphoreRed
    TrendSemaphore.GRAY -> SemaphoreGray
}

@Composable
private fun Sparkline(data: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas
        val min = data.min()
        val max = data.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (data.size - 1).toFloat()
        val pad = size.height * 0.1f
        val usableH = size.height - 2 * pad

        val path = Path()
        for (i in data.indices) {
            val x = i * stepX
            val normalized = ((data[i] - min) / range).toFloat()
            val y = pad + (1f - normalized) * usableH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = 2f))
    }
}
