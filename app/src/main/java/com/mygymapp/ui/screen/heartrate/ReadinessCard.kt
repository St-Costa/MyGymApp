package com.mygymapp.ui.screen.heartrate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mygymapp.data.polar.BatteryLifeState
import com.mygymapp.data.polar.Readiness
import com.mygymapp.data.polar.ReadinessResult

/** Groups the digits of a non-negative number with a "." thousands separator: 12641 -> "12.641". */
private fun groupThousands(n: Long): String {
    val s = n.toString()
    val sb = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append('.')
        sb.append(c)
    }
    return sb.toString()
}

/**
 * The HRV-readiness card. Shared between [HeartRateScreen]'s connected view and the
 * end-of-routine debug preview so both render identically.
 */
@Composable
fun ReadinessCard(
    readiness: ReadinessResult,
    vo2max: Double?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (readiness.readiness == Readiness.MEASURING) {
                Text(
                    "HRV Readiness",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Lie still... ${readiness.secondsRemaining}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { 1f - readiness.secondsRemaining / 60f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            } else {
                val readinessColor = when (readiness.readiness) {
                    Readiness.DELOAD_RECOMMENDED -> Color(0xFFEF5350)
                    Readiness.LIGHT_DAY -> Color(0xFFFFCA28)
                    Readiness.NORMAL -> Color(0xFF66BB6A)
                    Readiness.GOOD -> Color(0xFF4CAF50)
                    Readiness.PEAK -> Color(0xFF2196F3)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val readinessLabel = when (readiness.readiness) {
                    Readiness.DELOAD_RECOMMENDED -> "DELOAD"
                    Readiness.LIGHT_DAY -> "LIGHT DAY"
                    Readiness.NORMAL -> "NORMAL"
                    Readiness.GOOD -> "GOOD"
                    Readiness.PEAK -> "PEAK"
                    Readiness.NO_BASELINE -> "BASELINE ${if (readiness.lnRmssd > 0) "(collecting)" else ""}"
                    else -> ""
                }
                Text(
                    readinessLabel,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = readinessColor,
                )
                if (readiness.recommendation.isNotBlank()) {
                    // Only the actionable half interests us ("Proceed with planned workout"),
                    // not the "HRV within normal range." lead-in — the recommendation strings
                    // are always "<state sentence>. <action sentence>". Trailing "." dropped.
                    val action = readiness.recommendation
                        .substringAfterLast(". ", readiness.recommendation)
                        .trim()
                        .trimEnd('.')
                    Text(
                        action,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // Mean HR across the 60s measurement window (μ, LaTeX-style italic
                    // serif). The resting-HR figure isn't shown here — only the average.
                    if (readiness.bpmTrace.isNotEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color.Red,
                                )
                                Text(
                                    "μ",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                            }
                            Text(
                                "%.0f".format(readiness.bpmTrace.average()),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (vo2max != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Air,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFF4FC3F7),
                            )
                            Text(
                                buildAnnotatedString {
                                    append("%.1f VO".format(vo2max))
                                    withStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 10.sp)) {
                                        append("2")
                                    }
                                    append("m")
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    // Yesterday's complete calendar-day step total (see PolarManager.
                    // finishReadinessMeasurement()). Absent entirely until the async Health
                    // Connect read lands, and stays absent if it can't answer.
                    if (readiness.stepsPreviousDay != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.AutoMirrored.Filled.DirectionsWalk,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                groupThousands(readiness.stepsPreviousDay),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                // How much HR actually moved during the 60s "lie still" window — a quick
                // visual sanity check alongside the readiness verdict. Absent when this
                // result came from reusing today's earlier measurement (bpmTrace isn't
                // persisted, only held in memory for the live measurement).
                if (readiness.bpmTrace.size >= 2) {
                    ReadinessBpmSparkline(
                        bpmTrace = readiness.bpmTrace,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Minimal line chart of the BPM samples collected during the 60s readiness measurement —
 * just enough to see how much (or little) HR actually moved while lying still. Y-axis is
 * auto-scaled to the trace's own min/max (with a small floor so a dead-flat trace doesn't
 * divide by zero). The range is printed above the chart, horizontally centred, as
 * "[57, 70],  13Δ BPM".
 */
@Composable
private fun ReadinessBpmSparkline(
    bpmTrace: List<Int>,
    modifier: Modifier = Modifier,
) {
    val minBpm = bpmTrace.min()
    val maxBpm = bpmTrace.max()
    val range = (maxBpm - minBpm).coerceAtLeast(1)
    val lineColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "[$minBpm, $maxBpm],  ${maxBpm - minBpm}Δ BPM",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(top = 4.dp),
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val stepX = w / (bpmTrace.size - 1).toFloat()
            fun yOf(bpm: Int): Float = h - ((bpm - minBpm).toFloat() / range) * h
            val path = Path().apply {
                moveTo(0f, yOf(bpmTrace[0]))
                for (i in 1 until bpmTrace.size) {
                    lineTo(i * stepX, yOf(bpmTrace[i]))
                }
            }
            drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
            val lastX = (bpmTrace.size - 1) * stepX
            drawCircle(color = lineColor, radius = 3.dp.toPx(), center = Offset(lastX, yOf(bpmTrace.last())))
        }
    }
}

/**
 * Polar strap connection facts, boxed: device id (left) · battery % with icon (centre) ·
 * active hours / estimated cell lifespan (right). Shared between [HeartRateScreen] and the
 * end-of-routine debug preview. Any "Polar <model> " marketing prefix is stripped off the
 * id — only the identifying digits are shown.
 */
@Composable
fun PolarDeviceBox(
    deviceId: String?,
    batteryLevel: Int?,
    batteryLow: Boolean,
    batteryLife: BatteryLifeState?,
    modifier: Modifier = Modifier,
) {
    val shortId = deviceId
        ?.replace(Regex("^Polar\\s+\\S+\\s+"), "")
        ?.removePrefix("Polar ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "—"
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shortId,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (batteryLevel != null) {
                    val batteryColor = if (batteryLow) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (batteryLow) Icons.Default.BatteryAlert else Icons.Default.BatteryFull,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = batteryColor,
                        )
                        Text(
                            " $batteryLevel%",
                            style = MaterialTheme.typography.titleMedium,
                            color = batteryColor,
                        )
                    }
                }
            }

            // Active hours accumulated on the current CR2025 vs the mean measured lifespan
            // of a cell in this setup. Falls back to just active hours until at least one
            // cell has been swapped out and there's an average to show.
            val lifeText = batteryLife?.let { life ->
                val avg = life.avgLifeHours
                if (avg != null) {
                    "${"%.0f".format(life.activeHours)}/${"%.0f".format(avg)} h"
                } else {
                    "${"%.0f".format(life.activeHours)} h"
                }
            } ?: ""
            Text(
                lifeText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
