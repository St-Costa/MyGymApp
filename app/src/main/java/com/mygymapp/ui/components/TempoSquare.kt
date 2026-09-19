package com.mygymapp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.nativeCanvas

/** Side of the square tempo boxes, shared by the editor and the loop animation. */
internal val TempoBoxSide = 52.dp

internal val TempoPhaseLabels = listOf("Neg", "Stretch", "Contr", "Peak")

/**
 * The square itself, identical in the editor and the execution animation: 52dp bordered
 * box, 6dp corners like the gitgraph squares. The animation additionally passes a
 * bottom-to-top [fillFraction] (0f = no fill, editor case).
 */
@Composable
internal fun TempoSquareShell(
    fillFraction: Float = 0f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .width(TempoBoxSide)
            .height(TempoBoxSide)
            .clip(RoundedCornerShape(6.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp),
            ),
    ) {
        if (fillFraction > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fillFraction)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        content()
    }
}

/**
 * Upper slot of the square, identical in the editor (duration number) and the execution
 * animation (phase icon): fixed 28dp zone with 2dp top padding, content centered — so the
 * gap to the bottom text is the same in both.
 */
@Composable
internal fun BoxScope.TempoSquareTopSlot(
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(32.dp)
            .padding(top = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Phase glyph built from scratch as a vector: white-filled shape with its own black
 * outline (two nested paths — outer black silhouette, inner white face). No stroke
 * tricks: the rim is real geometry, so it can't swallow the fill.
 *
 * [phaseIndex]: 0 = negativa (down arrow), 1 = stretch (pause bars), 2 = concentrica
 * (up arrow), 3 = peak (pause bars). All paths are defined in a 24x24 box, like
 * Material icons.
 */
@Composable
internal fun TempoPhaseGlyph(
    phaseIndex: Int,
    modifier: Modifier = Modifier,
) {
    // Approved version: identical black (with native 1f stroke) + white paths.
    val vector = remember(phaseIndex) {
        ImageVector.Builder(
            name = "TempoPhase$phaseIndex",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            val white = SolidColor(Color(0xFFF5F5F5))
            val black = SolidColor(Color.Black)
            when (phaseIndex) {
                0 -> {
                    path(fill = black, stroke = black, strokeLineWidth = 1f) {
                        moveTo(20f, 12f)
                        lineToRelative(-1.41f, -1.41f)
                        lineTo(13.11f, 16.17f)
                        verticalLineTo(4f)
                        horizontalLineToRelative(-2.23f)
                        verticalLineToRelative(12.17f)
                        lineToRelative(-5.58f, -5.59f)
                        lineTo(4f, 12f)
                        lineToRelative(8f, 8f)
                        lineToRelative(8f, -8f)
                        close()
                    }
                    path(fill = white) {
                        moveTo(20f, 12f)
                        lineToRelative(-1.41f, -1.41f)
                        lineTo(13.11f, 16.17f)
                        verticalLineTo(4f)
                        horizontalLineToRelative(-2.23f)
                        verticalLineToRelative(12.17f)
                        lineToRelative(-5.58f, -5.59f)
                        lineTo(4f, 12f)
                        lineToRelative(8f, 8f)
                        lineToRelative(8f, -8f)
                        close()
                    }
                }
                2 -> {
                    path(fill = black, stroke = black, strokeLineWidth = 1f) {
                        moveTo(4f, 12f)
                        lineToRelative(1.41f, 1.41f)
                        lineTo(10.89f, 7.83f)
                        verticalLineTo(20f)
                        horizontalLineToRelative(2.23f)
                        verticalLineToRelative(-12.17f)
                        lineToRelative(5.58f, 5.59f)
                        lineTo(20f, 12f)
                        lineToRelative(-8f, -8f)
                        lineToRelative(-8f, 8f)
                        close()
                    }
                    path(fill = white) {
                        moveTo(4f, 12f)
                        lineToRelative(1.41f, 1.41f)
                        lineTo(10.89f, 7.83f)
                        verticalLineTo(20f)
                        horizontalLineToRelative(2.23f)
                        verticalLineToRelative(-12.17f)
                        lineToRelative(5.58f, 5.59f)
                        lineTo(20f, 12f)
                        lineToRelative(-8f, -8f)
                        lineToRelative(-8f, 8f)
                        close()
                    }
                }
                else -> {
                    path(fill = black, stroke = black, strokeLineWidth = 1f) {
                        // Barre dal tuo SVG (rect 200x120): x 5.6-10 / 14-19, y 5.5-18.5.
                        moveTo(5.6f, 18.5f)
                        lineTo(10f, 18.5f)
                        lineTo(10f, 5.5f)
                        lineTo(5.6f, 5.5f)
                        close()
                        moveTo(14f, 5.5f)
                        lineTo(19f, 5.5f)
                        lineTo(19f, 18.5f)
                        lineTo(14f, 18.5f)
                        close()
                    }
                    path(fill = white) {
                        moveTo(5.6f, 18.5f)
                        lineTo(10f, 18.5f)
                        lineTo(10f, 5.5f)
                        lineTo(5.6f, 5.5f)
                        close()
                        moveTo(14f, 5.5f)
                        lineTo(19f, 5.5f)
                        lineTo(19f, 18.5f)
                        lineTo(14f, 18.5f)
                        close()
                    }
                }
            }
        }.build()
    }
    Image(
        imageVector = vector,
        contentDescription = TempoPhaseLabels.getOrElse(phaseIndex) { "phase" },
        modifier = modifier,
    )
}
/**
 * Single outlined duration token: the digit painted once with a stroked copy underneath —
 * the same look as the 8-offset ring, but a single native-canvas draw, so recomposing
 * every animation frame doesn't lag.
 */
@Composable
internal fun TempoOutlinedToken(
    text: String,
    foreground: androidx.compose.ui.graphics.Color,
    outlineColor: androidx.compose.ui.graphics.Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily?,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // rememberTextMeasurer/drawText(canvas TextMeasurer) need Compose 1.6+ text APIs —
    // this project is on ui-text 1.7.7 but the drawscope overload isn't resolving, so use
    // the native canvas + Paint path already proven in TonnageLineChart/HrZoneTraceChart.
    // MONOSPACE placeholder — the animation token is a single digit, and the fill pass
    // below keeps the JetBrainsMono look close enough at 16sp; loading the real font
    // family into a native Paint would need ResourcesCompat per-frame work.
    val typeface = android.graphics.Typeface.MONOSPACE
    val textPx = with(density) { fontSize.toPx() }
    val strokePx = with(density) { 1.dp.toPx() } * 2
    val paint = remember(textPx, strokePx, foreground, outlineColor, alpha) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = typeface
            this.textSize = textPx
        }
        p to Triple(foreground, outlineColor, alpha)
    }.let { (p, colors) -> p to colors }
    androidx.compose.foundation.Canvas(
        modifier = modifier.graphicsLayer(alpha = 1f),
    ) {
        val (fg, outline, a) = paint.second
        val p = paint.first
        fun argb(c: androidx.compose.ui.graphics.Color, alphaMul: Float): Int =
            android.graphics.Color.argb(
                (c.alpha * alphaMul * 255).toInt().coerceIn(0, 255),
                (c.red * 255).toInt(),
                (c.green * 255).toInt(),
                (c.blue * 255).toInt(),
            )
        val cx = size.width / 2f
        val cy = size.height / 2f - (p.descent() + p.ascent()) / 2f
        // Stroke pass (outline ring).
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = strokePx
        p.color = argb(outline, a)
        drawContext.canvas.nativeCanvas.drawText(text, cx, cy, p)
        // Fill pass.
        p.style = android.graphics.Paint.Style.FILL
        p.color = argb(fg, a)
        drawContext.canvas.nativeCanvas.drawText(text, cx, cy, p)
    }
}

/**
 * Small text pinned to the bottom edge with trimmed leading — same treatment as the
 * routine name in the gitgraph squares (0.5dp from the edge). Plain single-Text path
 * (used by the static editor); the animation uses [TempoOutlinedToken] instead.
 */
@Composable
internal fun BoxScope.TempoSquareBottomText(
    text: String,
    alpha: Float,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 11.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    fontFamily: FontFamily? = null,
) {
    Text(
        text = text,
        fontSize = fontSize,
        fontFamily = fontFamily,
        textAlign = TextAlign.Center,
        lineHeight = fontSize,
        style = TextStyle(
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Bottom,
                trim = LineHeightStyle.Trim.Both,
            ),
        ),
        color = color.copy(alpha = alpha),
        fontWeight = fontWeight,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = 0.5.dp),
    )
}
