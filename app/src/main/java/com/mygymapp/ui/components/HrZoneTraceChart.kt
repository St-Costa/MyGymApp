package com.mygymapp.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.polar.ConnectionState
import com.mygymapp.data.polar.HrZone
import com.mygymapp.data.polar.HrZoneCalculator
import com.mygymapp.data.polar.HrZoneMinutes
import com.mygymapp.data.polar.PolarManager

/**
 * Live %HRR trace over a colour-banded zone background.
 *
 * The vertical axis is %HRR, drawn **proportionally** — each Z1..Z5 band is as tall as its
 * real Karvonen span (see [HrZoneCalculator.ZONE_BOUNDARY_FRACTIONS]), so the dot's height
 * always agrees with the "Z3 · 74%" chip in [HeartRateBar]. Each zone name (Z1..Z5) sits at
 * a fixed x, vertically centred in its own band, with a rounded badge to its right showing
 * the running M:SS spent in that zone this session (from [PolarManager.hrZoneMinutes]); a
 * zone not yet visited shows its name only, no badge. The band the live HR currently
 * falls in is drawn brighter than the others to call it out; the white trace is stroked
 * last so it stays visible over the highlighted band. The horizontal axis is time: oldest
 * sample at the left edge, "now" pinned at the right edge, where the current-value dot sits
 * tinted with its zone's colour.
 *
 * History comes from [PolarManager.hrZoneTracePercents] (a ~90s rolling buffer owned by the
 * manager, not by this composable) so the trace survives navigation between the routine and
 * cardio screens rather than restarting empty.
 *
 * Hides itself when the strap is disconnected, same as [HeartRateBar].
 */
@Composable
fun HrZoneTraceChart(
    modifier: Modifier = Modifier,
    viewModel: HeartRateBarViewModel = hiltViewModel(),
) {
    val polarManager = viewModel.polarManager
    val connectionState by polarManager.connectionState.collectAsState()
    val trace by polarManager.hrZoneTracePercents.collectAsState()
    val currentZone by polarManager.currentHrZone.collectAsState()
    val boundaries by polarManager.hrZoneBoundaries.collectAsState()
    val zoneMinutes by polarManager.hrZoneMinutes.collectAsState()

    if (connectionState != ConnectionState.CONNECTED) return
    if (trace.isEmpty()) return

    HrZoneTraceChartContent(
        trace = trace,
        currentZone = currentZone,
        boundaries = boundaries,
        zoneMinutes = zoneMinutes,
        modifier = modifier,
    )
}

/**
 * The pure drawing half of [HrZoneTraceChart] — no ViewModel, no connection gate. Split out
 * so [com.mygymapp.ui.screen.sessionprogress.SessionSummaryPreviewScreen] can render it with
 * hand-made sample data (a trace parked mid-Z3, some zones visited and some not) to eyeball
 * the active-band highlight and the M:SS badges without a live Polar session.
 */
@Composable
fun HrZoneTraceChartContent(
    trace: List<Int>,
    currentZone: HrZone?,
    boundaries: List<Int>,
    zoneMinutes: HrZoneMinutes,
    modifier: Modifier = Modifier,
) {
    if (trace.isEmpty()) return

    // Axis range: 0%..105% HRR. The dot is clamped to this ceiling rather than left free to
    // climb, so an all-out effort pins visibly at the top border instead of implying the
    // chart has more headroom above Z5 than it's showing.
    val axisMax = 105f

    // BELOW_Z1 is "still resting/warming up" — not interesting to look at, so it gets half
    // the screen-space its %HRR span would normally earn. Z1..Z5 keep their real
    // proportions, just packed into the remaining space below the same axisMax ceiling.
    val fractions = HrZoneCalculator.ZONE_BOUNDARY_FRACTIONS
    val belowZ1Top = (fractions[0] * 100).toFloat() // real %HRR where Z1 begins
    val belowZ1ScreenFrac = 0.5f // BELOW_Z1 band takes this fraction of its normal height

    val density = LocalDensity.current
    val labelPaint = remember(density) {
        Paint().apply {
            color = Color.White.copy(alpha = 0.85f).toArgb()
            textSize = with(density) { 10.sp.toPx() }
            isAntiAlias = true
        }
    }
    // Per-zone time-in-zone readout, sitting one line above the boundary label.
    val timerPaint = remember(density) {
        Paint().apply {
            color = Color.White.copy(alpha = 0.9f).toArgb()
            textSize = with(density) { 9.sp.toPx() }
            isAntiAlias = true
        }
    }
    // Rounded backing for the time-in-zone text.
    val badgePaint = remember {
        Paint().apply {
            color = Color.Black.copy(alpha = 0.45f).toArgb()
            isAntiAlias = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Callers supply the height: the cardio screen via .weight(1f), the debug
            // preview via .height(...). No trailing .fillMaxSize() here — inside a scrolling
            // column that has no bounded height it would collapse the chart to nothing.
            .then(modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B1B1B))
            .padding(vertical = 2.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // %HRR -> "virtual" %HRR, compressing [0, belowZ1Top] to half its span and
            // sliding Z1..Z5's real span down to fill the space that freed up. The virtual
            // axis still runs 0..axisMax, so yOf() below stays a single linear map.
            val compressedBelowZ1Span = belowZ1Top * belowZ1ScreenFrac
            val virtualAxisMax = axisMax - belowZ1Top + compressedBelowZ1Span
            fun toVirtualPercent(percent: Float): Float = if (percent <= belowZ1Top) {
                (percent / belowZ1Top.coerceAtLeast(0.01f)) * compressedBelowZ1Span
            } else {
                compressedBelowZ1Span + (percent - belowZ1Top)
            }

            // virtual %HRR -> y, inverted (higher % = higher on screen)
            fun yOf(percent: Float): Float {
                val v = toVirtualPercent(percent.coerceIn(0f, axisMax))
                return h - (v / virtualAxisMax) * h
            }

            // --- Zone bands (background) ---
            // Band N spans [lower bound, next lower bound), the topmost running to axisMax.
            val bands = buildList {
                // Below Z1: 0% up to the Z1 threshold
                add(HrZone.BELOW_Z1 to (0f to (fractions[0] * 100).toFloat()))
                add(HrZone.Z1 to ((fractions[0] * 100).toFloat() to (fractions[1] * 100).toFloat()))
                add(HrZone.Z2 to ((fractions[1] * 100).toFloat() to (fractions[2] * 100).toFloat()))
                add(HrZone.Z3 to ((fractions[2] * 100).toFloat() to (fractions[3] * 100).toFloat()))
                add(HrZone.Z4 to ((fractions[3] * 100).toFloat() to (fractions[4] * 100).toFloat()))
                add(HrZone.Z5 to ((fractions[4] * 100).toFloat() to axisMax))
            }
            bands.forEach { (zone, range) ->
                val (lo, hi) = range
                val top = yOf(hi)
                val bottom = yOf(lo)
                val isActive = zone == currentZone
                // Active band: brighter fill + a thin colour edge so it reads as "you are
                // here" at a glance. The white trace is drawn afterwards, so bumping the
                // fill alpha here never buries it.
                drawRect(
                    color = hrZoneColor(zone).copy(alpha = if (isActive) 0.55f else 0.30f),
                    topLeft = Offset(0f, top),
                    size = Size(w, (bottom - top).coerceAtLeast(0f)),
                )
                if (isActive) {
                    drawRect(
                        color = hrZoneColor(zone).copy(alpha = 0.9f),
                        topLeft = Offset(0f, top),
                        size = Size(w, (bottom - top).coerceAtLeast(0f)),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }

            // --- Trace ---
            // Right-anchored: the newest sample lands exactly on the right edge, so a
            // partially-filled buffer grows leftward from "now" instead of stretching.
            val totalPoints = PolarManager.HR_ZONE_TRACE_MAX_POINTS
            val stepX = w / (totalPoints - 1).toFloat()
            val firstIndex = totalPoints - trace.size
            fun xOf(i: Int): Float = (firstIndex + i) * stepX

            if (trace.size >= 2) {
                val path = Path().apply {
                    moveTo(xOf(0), yOf(trace[0].toFloat()))
                    for (i in 1 until trace.size) {
                        lineTo(xOf(i), yOf(trace[i].toFloat()))
                    }
                }
                // Dark halo first, then the solid white line on top: keeps the trace
                // readable even where it crosses the brightened active band.
                drawPath(
                    path = path,
                    color = Color.Black.copy(alpha = 0.35f),
                    style = Stroke(width = 4.dp.toPx()),
                )
                drawPath(
                    path = path,
                    color = Color.White,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            // --- Boundary lines ---
            // boundaries[0..4] = cutoffs between BELOW_Z1/Z1, Z1/Z2, Z2/Z3, Z3/Z4, Z4/Z5
            boundaries.forEachIndexed { i, _ ->
                val y = yOf((fractions[i] * 100).toFloat())
                drawLine(
                    color = Color.White.copy(alpha = 0.18f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // --- Zone labels: the name (Z1..Z5) at a fixed x, vertically centred in its own
            // band, with the running time-in-zone (M:SS) in a rounded badge to its right.
            // A zone not yet visited shows its name only, no badge. ---
            val zoneNames = listOf("Z1", "Z2", "Z3", "Z4", "Z5")
            val zoneEnums = listOf(HrZone.Z1, HrZone.Z2, HrZone.Z3, HrZone.Z4, HrZone.Z5)
            val labelX = 4.dp.toPx()
            val nameW = zoneNames.maxOf { labelPaint.measureText(it) }
            val badgePadH = 4.dp.toPx()
            val badgePadV = 2.dp.toPx()
            val badgeRadius = 4.dp.toPx()
            val badgeGap = 4.dp.toPx()
            val textHalfHeight = -(labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
            if (boundaries.size >= 5) {
                zoneNames.indices.forEach { i ->
                    // Band i runs from its own lower boundary up to the next one (Z5 -> axisMax).
                    val bandLoPct = (fractions[i] * 100).toFloat()
                    val bandHiPct = if (i + 1 < fractions.size) (fractions[i + 1] * 100).toFloat() else axisMax
                    val bandMidY = (yOf(bandLoPct) + yOf(bandHiPct)) / 2f
                    val baseline = bandMidY + textHalfHeight
                    drawContext.canvas.nativeCanvas.drawText(zoneNames[i], labelX, baseline, labelPaint)

                    val minutesInZone = zoneMinutes.forZone(zoneEnums[i])
                    if (minutesInZone * 60.0 >= 1.0) {
                        val timeText = formatZoneTime(minutesInZone)
                        val textW = timerPaint.measureText(timeText)
                        val textX = labelX + nameW + badgeGap + badgePadH
                        drawContext.canvas.nativeCanvas.drawRoundRect(
                            textX - badgePadH,
                            baseline + timerPaint.fontMetrics.ascent - badgePadV,
                            textX + textW + badgePadH,
                            baseline + timerPaint.fontMetrics.descent + badgePadV,
                            badgeRadius,
                            badgeRadius,
                            badgePaint,
                        )
                        drawContext.canvas.nativeCanvas.drawText(timeText, textX, baseline, timerPaint)
                    }
                }
            }

            // --- Current-value dot, pinned at the right edge ---
            val lastPercent = trace.last().toFloat()
            val dotX = xOf(trace.size - 1)
            val dotY = yOf(lastPercent)
            val dotColor = currentZone?.let { hrZoneColor(it) } ?: Color.White
            // White backing ring keeps the dot legible against its own band colour
            drawCircle(color = Color.White, radius = 4.dp.toPx(), center = Offset(dotX, dotY))
            drawCircle(color = dotColor, radius = 2.75.dp.toPx(), center = Offset(dotX, dotY))
        }
    }
}

/** Minutes accumulated in one Z1..Z5 band. BELOW_Z1 has no on-chart label, so it's excluded. */
private fun HrZoneMinutes.forZone(zone: HrZone): Double = when (zone) {
    HrZone.BELOW_Z1 -> belowZone1
    HrZone.Z1 -> zone1
    HrZone.Z2 -> zone2
    HrZone.Z3 -> zone3
    HrZone.Z4 -> zone4
    HrZone.Z5 -> zone5
}

/** `M:SS` (no leading zero on minutes), rolling over to `H:MM:SS` past the hour. */
private fun formatZoneTime(minutes: Double): String {
    val totalSeconds = (minutes * 60.0).toInt().coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
