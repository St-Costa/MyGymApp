package com.mygymapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mygymapp.data.model.ScaleWeighIn
import com.mygymapp.data.repository.ScaleTrendReport
import com.mygymapp.data.repository.WeeklyPoint
import com.mygymapp.data.repository.averageWeeklyDelta
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

private val FatColor = Color(0xFFEF5350)
private val WeightColor = Color(0xFF42A5F5)
private val BmiColor = Color(0xFFAB47BC)

private val dayInitials = listOf("L", "M", "M", "G", "V", "S", "D")

@Composable
fun ScaleTrendSection(report: ScaleTrendReport) {
    if (!report.hasData) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Text(
                "Nessuna pesata registrata ancora.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val currentWeekSlots = report.currentWeekSlots
        if (currentWeekSlots.any { it != null }) {
            ChartCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Peso settimanale", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        report.currentWeekMedianBmi?.let { bmi ->
                            Text(
                                "BMI %.1f".format(bmi),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = BmiColor,
                            )
                        }
                        report.currentWeekMedianFatPercent?.let { fat ->
                            Text(
                                "BF %.1f%%".format(fat),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = FatColor,
                            )
                        }
                    }
                }
                PointLineChart(
                    values = currentWeekSlots.map { it?.weightKg },
                    color = WeightColor,
                    labelStyle = PointLabelStyle.TEXT_ABOVE,
                    highlightIndex = report.currentWeekMedianSlotIndex,
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (label in dayInitials) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        val weeklyMedians = report.weeklyMedianWeights
        if (weeklyMedians.isNotEmpty()) {
            ChartCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Peso - 2 mesi", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    weeklyMedians.averageWeeklyDelta()?.let { delta ->
                        Text(
                            "μ = %+.1f".format(delta),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
                PointLineChart(
                    values = weeklyMedians.map { it.value },
                    color = WeightColor,
                    labelStyle = PointLabelStyle.TEXT_ABOVE,
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (point in weeklyMedians) {
                        Text(point.weekLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

    }
}


@Composable
internal fun ChartCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

internal enum class PointLabelStyle { NONE, TEXT_ABOVE, BADGE }

/**
 * Draws a dot for every non-null value and connects consecutive non-null
 * points with a segment; gaps (nulls) are skipped, never interpolated or
 * extended. Values are plotted against a fixed x-slot per list index, so
 * gaps stay in their correct position (e.g. an unweighed Tuesday leaves an
 * empty slot between Monday's and Wednesday's dots).
 */
@Composable
internal fun PointLineChart(
    values: List<Double?>,
    color: Color,
    labelStyle: PointLabelStyle,
    modifier: Modifier = Modifier,
    highlightIndex: Int? = null,
) {
    val present = values.filterNotNull()
    if (present.isEmpty()) return

    val min = present.min()
    val max = present.max()
    // Single point: no real range, so it sits dead-center. Multiple points:
    // scale tightly to the data's own min/max, leaving just enough headroom
    // for the label/badge drawn above each dot.
    val hasRange = present.size > 1 && max > min
    val n = values.size

    Canvas(modifier = modifier) {
        // Horizontal inset so the first/last point's label isn't clipped by the canvas edge.
        val sideInset = 20f
        val plotWidth = size.width - 2 * sideInset
        val stepX = if (n > 1) plotWidth / (n - 1) else plotWidth / 2
        val labelHeadroom = if (labelStyle == PointLabelStyle.NONE) size.height * 0.12f else size.height * 0.32f
        val bottomPad = size.height * 0.12f
        val usableH = size.height - labelHeadroom - bottomPad

        fun yFor(v: Double): Float {
            if (!hasRange) return labelHeadroom + usableH / 2f
            val normalized = ((v - min) / (max - min)).toFloat()
            return labelHeadroom + (1f - normalized) * usableH
        }
        fun xFor(i: Int): Float = if (n > 1) sideInset + i * stepX else size.width / 2

        var prevIndex: Int? = null
        for (i in values.indices) {
            val v = values[i] ?: continue
            if (prevIndex != null) {
                val pv = values[prevIndex]!!
                drawLine(
                    color = color,
                    start = Offset(xFor(prevIndex), yFor(pv)),
                    end = Offset(xFor(i), yFor(v)),
                    strokeWidth = 4f,
                )
            }
            prevIndex = i
        }

        for (i in values.indices) {
            val v = values[i] ?: continue
            val x = xFor(i)
            val y = yFor(v)
            drawCircle(color = color, radius = 8f, center = Offset(x, y))

            val effectiveStyle = if (highlightIndex != null) {
                if (i == highlightIndex) PointLabelStyle.BADGE else PointLabelStyle.TEXT_ABOVE
            } else {
                labelStyle
            }
            when (effectiveStyle) {
                PointLabelStyle.TEXT_ABOVE -> drawCenteredText(
                    text = "%.1f".format(v),
                    x = x,
                    y = y - 20f,
                    color = color,
                    textSizeSp = 13f,
                )
                PointLabelStyle.BADGE -> drawBadge(
                    text = "%.1f".format(v),
                    x = x,
                    y = y - 26f,
                    color = color,
                )
                PointLabelStyle.NONE -> {}
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenteredText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    textSizeSp: Float = 13f,
) {
    val paint = Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        textSize = textSizeSp.sp.toPx()
        isFakeBoldText = true
        this.color = color.toArgb()
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBadge(
    text: String,
    x: Float,
    y: Float,
    color: Color,
) {
    val paint = Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        textSize = 13.sp.toPx()
        isFakeBoldText = true
        this.color = Color.White.toArgb()
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val textWidth = paint.measureText(text)
    val paddingH = 10f
    val paddingV = 8f
    val rect = androidx.compose.ui.geometry.Rect(
        left = x - textWidth / 2 - paddingH,
        top = y - paint.textSize - paddingV,
        right = x + textWidth / 2 + paddingH,
        bottom = y + paddingV,
    )
    drawRoundRect(
        color = color,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
    )
    drawContext.canvas.nativeCanvas.drawText(text, x, y - paddingV / 2, paint)
}

