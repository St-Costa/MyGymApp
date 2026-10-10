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
import com.mygymapp.ui.theme.SignalRed
import com.mygymapp.data.model.ScaleWeighIn
import com.mygymapp.data.repository.ScaleTrendReport
import com.mygymapp.data.repository.WeeklyPoint
import com.mygymapp.data.repository.averageWeeklyDelta
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

private val FatColor = SignalRed
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
                    xLabels = dayInitials,
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
            }
        }

        val weeklyMedians = report.weeklyMedianWeights
        if (weeklyMedians.isNotEmpty()) {
            ChartCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Peso - 2 mesi", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    weeklyMedians.averageWeeklyDelta()?.let { delta -> WeeklyDeltaLabel(delta) }
                }
                PointLineChart(
                    values = weeklyMedians.map { it.value },
                    color = WeightColor,
                    labelStyle = PointLabelStyle.TEXT_ABOVE,
                    xLabels = weeklyMedians.map { it.weekLabel },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
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
 *
 * [xLabels] (when present) are drawn inside the canvas itself, centered on
 * the exact same x-slot as their dot — so axis labels can never drift out of
 * alignment with the points the way a separate `Row(SpaceBetween)` does
 * (edge-aligned items + different horizontal padding than the canvas inset).
 */
@Composable
internal fun PointLineChart(
    values: List<Double?>,
    color: Color,
    labelStyle: PointLabelStyle,
    modifier: Modifier = Modifier,
    highlightIndex: Int? = null,
    xLabels: List<String>? = null,
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
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier) {
        // Inset so the first/last point's centered labels (value above, axis
        // label below) aren't clipped by the canvas edge. ~28dp covers half a
        // "102.4" value label at 13sp bold.
        val sideInset = 28.dp.toPx()
        val plotWidth = (size.width - 2 * sideInset).coerceAtLeast(0f)
        val stepX = if (n > 1) plotWidth / (n - 1) else plotWidth / 2
        val labelHeadroom = if (labelStyle == PointLabelStyle.NONE) size.height * 0.12f else size.height * 0.30f
        val bottomPad = if (xLabels != null) 20.dp.toPx() else size.height * 0.12f
        val usableH = (size.height - labelHeadroom - bottomPad).coerceAtLeast(0f)

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
                val prev = prevIndex
                val pv = values[prev] ?: continue
                drawLine(
                    color = color,
                    start = Offset(xFor(prev), yFor(pv)),
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

        xLabels?.forEachIndexed { i, label ->
            if (i !in values.indices) return@forEachIndexed
            drawCenteredText(
                text = label,
                x = xFor(i),
                y = size.height - 4.dp.toPx(),
                color = axisColor,
                textSizeSp = 11f,
                bold = false,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenteredText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    textSizeSp: Float = 13f,
    bold: Boolean = true,
) {
    val paint = Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        textSize = textSizeSp.sp.toPx()
        isFakeBoldText = bold
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

