package com.mygymapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ColorNeutral = Color(0xFF4FC3F7)
private val ColorUp = Color(0xFF66BB6A)    // green
private val ColorDown = Color(0xFFEF5350)  // red
private val ColorZero = Color(0xFF424242)  // dark gray for zero-value points
private val GridColor = Color(0x33FFFFFF)
private val LabelColor = Color(0xFFAAAAAA)
private val ZeroLineColor = Color(0x55FFFFFF)

@Composable
fun TonnageLineChart(
    data: List<Double>,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    if (data.isEmpty()) return

    // Pre-compute per-point colors: zero = dark, up = green, down = red
    val pointColors = data.mapIndexed { i, value ->
        when {
            value == 0.0 -> ColorZero
            i == 0 -> ColorNeutral
            value > data[i - 1] -> ColorUp
            else -> ColorDown  // equal or lower = red
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        val paddingLeft = 48f
        val paddingRight = 12f
        val paddingTop = 12f
        val paddingBottom = 40f

        val chartWidth = size.width - paddingLeft - paddingRight
        val chartHeight = size.height - paddingTop - paddingBottom

        val maxValue = data.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val n = data.size

        // Grid lines (3 horizontal levels: 0, 50%, max)
        listOf(0.0, 0.5, 1.0).forEach { level ->
            val y = paddingTop + chartHeight * (1f - level.toFloat())
            drawLine(
                color = if (level == 0.0) ZeroLineColor else GridColor,
                start = Offset(paddingLeft, y),
                end = Offset(paddingLeft + chartWidth, y),
                strokeWidth = 1f,
            )
            if (level == 0.0 || level == 1.0) {
                val labelValue = (maxValue * level).toInt()
                drawYLabel(labelValue.toString(), y, paddingLeft - 4f, LabelColor)
            }
        }

        // Compute point positions
        val points = data.mapIndexed { i, value ->
            val x = paddingLeft + (i.toFloat() / (n - 1).coerceAtLeast(1)) * chartWidth
            val y = paddingTop + chartHeight * (1f - (value / maxValue).toFloat()).coerceIn(0f, 1f)
            Offset(x, y)
        }

        // Draw connecting segments: each segment takes the color of the destination point
        for (i in 0 until points.size - 1) {
            drawLine(
                color = pointColors[i + 1],
                start = points[i],
                end = points[i + 1],
                strokeWidth = 5f,
            )
        }

        // Draw dots (3x bigger: radius 15f). Zero-value = solid dark dot, others = colored ring
        points.forEachIndexed { i, pt ->
            drawCircle(color = pointColors[i], radius = 15f, center = pt)
            if (data[i] > 0.0) {
                drawCircle(color = Color.Black, radius = 7f, center = pt)
            }
        }

        // X-axis labels: every other week to avoid overlap
        labels.forEachIndexed { i, label ->
            if (i % 2 == 0) {
                val x = paddingLeft + (i.toFloat() / (n - 1).coerceAtLeast(1)) * chartWidth
                drawXLabel(label, x, paddingTop + chartHeight + 28f, LabelColor)
            }
        }
    }
}

private fun DrawScope.drawYLabel(text: String, y: Float, rightX: Float, color: Color) {
    val paint = Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        textSize = 10.sp.toPx()
        this.color = color.toArgb()
        textAlign = android.graphics.Paint.Align.RIGHT
    }
    drawContext.canvas.nativeCanvas.drawText(text, rightX, y + paint.textSize / 3, paint)
}

private fun DrawScope.drawXLabel(text: String, x: Float, y: Float, color: Color) {
    val paint = Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        textSize = 9.sp.toPx()
        this.color = color.toArgb()
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}
