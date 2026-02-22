package com.mygymapp.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * +/− button with optional long-press repeat.
 *
 * Timing logic lives at PointerInputScope level (outside awaitPointerEventScope) so
 * kotlinx.coroutines.withTimeoutOrNull can be called freely — AwaitPointerEventScope is
 * annotated @RestrictsSuspension and only allows its own member suspend functions.
 *
 * Behavior:
 *   - Tap (release < 1s) → onClick
 *   - Hold ≥ 1s → onLongPressRepeat fires, then again every 1s until released
 *
 * rememberUpdatedState keeps lambdas fresh across recompositions without restarting
 * the pointerInput block (keyed on Unit).
 */
@Composable
private fun PickerButton(
    label: String,
    onClick: () -> Unit,
    onLongPressRepeat: (() -> Unit)? = null,
) {
    val currentOnClick = rememberUpdatedState(onClick)
    val currentOnLongPressRepeat = rememberUpdatedState(onLongPressRepeat)

    Box(
        modifier = Modifier
            .size(32.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clip(CircleShape)
            .pointerInput(Unit) {
                // PointerInputScope level: @RestrictsSuspension does NOT apply here,
                // so kotlinx.coroutines.withTimeoutOrNull is callable.
                while (true) {
                    awaitPointerEventScope {
                        awaitFirstDown(requireUnconsumed = false)
                    }

                    val longPressAction = currentOnLongPressRepeat.value
                    if (longPressAction != null) {
                        // null = timed out (held ≥ 1s), non-null = released early (tap)
                        val releasedEarly = withTimeoutOrNull(1000L) {
                            awaitPointerEventScope { waitForUpOrCancellation() }
                        } != null

                        if (releasedEarly) {
                            currentOnClick.value()
                        } else {
                            // Long press: fire immediately, then every 500ms while held
                            while (true) {
                                currentOnLongPressRepeat.value?.invoke()
                                val released = withTimeoutOrNull(500L) {
                                    awaitPointerEventScope { waitForUpOrCancellation() }
                                } != null
                                if (released) break
                            }
                        }
                    } else {
                        awaitPointerEventScope { waitForUpOrCancellation() }
                        currentOnClick.value()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ScrollPickerInput(
    value: Number,
    onValueChange: (Number) -> Unit,
    scrollStep: Double = 5.0,
    buttonStep: Double = 1.0,
    minValue: Double = 0.0,
    isDecimal: Boolean = false,
    isModified: Boolean = true,
    enableScroll: Boolean = false,
    longPressRepeatStep: Double = 0.0,
    modifier: Modifier = Modifier,
) {
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val sensitivity = 20f

    val displayValue = if (isDecimal) {
        val d = value.toDouble()
        if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)
    } else {
        value.toInt().toString()
    }

    fun applyChange(delta: Double) {
        val newValue = if (isDecimal) {
            (value.toDouble() + delta).coerceAtLeast(minValue)
        } else {
            (value.toInt() + delta.roundToInt()).coerceAtLeast(minValue.roundToInt())
        }
        if (isDecimal) onValueChange(newValue as Number) else onValueChange((newValue as Number).toInt())
    }

    val scrollModifier = if (enableScroll) {
        Modifier.pointerInput(scrollStep, minValue) {
            detectVerticalDragGestures(
                onDragStart = { dragAccumulator = 0f },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    dragAccumulator -= dragAmount
                    val steps = (dragAccumulator / sensitivity).toInt()
                    if (steps != 0) {
                        dragAccumulator -= steps * sensitivity
                        applyChange(steps * scrollStep)
                    }
                },
            )
        }
    } else Modifier

    Row(
        modifier = modifier.then(scrollModifier),
        horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerButton(
            label = "\u2212",
            onClick = { applyChange(-buttonStep) },
            onLongPressRepeat = if (longPressRepeatStep > 0) {
                { applyChange(-longPressRepeatStep) }
            } else null,
        )

        Text(
            text = displayValue,
            style = MaterialTheme.typography.headlineSmall,
            color = if (isModified) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp),
        )

        PickerButton(
            label = "+",
            onClick = { applyChange(buttonStep) },
            onLongPressRepeat = if (longPressRepeatStep > 0) {
                { applyChange(longPressRepeatStep) }
            } else null,
        )
    }
}
