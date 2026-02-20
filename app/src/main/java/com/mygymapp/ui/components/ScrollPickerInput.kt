package com.mygymapp.ui.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun ScrollPickerInput(
    value: Number,
    onValueChange: (Number) -> Unit,
    label: String,
    previousValue: String = "",
    step: Double = 1.0,
    minValue: Double = 0.0,
    isDecimal: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val sensitivity = 20f // pixels per step

    Column(
        modifier = modifier
            .width(80.dp)
            .pointerInput(step, minValue) {
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulator -= dragAmount // negative because drag down = decrease
                        val steps = (dragAccumulator / sensitivity).toInt()
                        if (steps != 0) {
                            dragAccumulator -= steps * sensitivity
                            val newValue = if (isDecimal) {
                                val current = value.toDouble()
                                (current + steps * step).coerceAtLeast(minValue)
                            } else {
                                val current = value.toInt()
                                (current + (steps * step).roundToInt()).coerceAtLeast(minValue.roundToInt())
                            }
                            if (isDecimal) {
                                onValueChange(newValue as Number)
                            } else {
                                onValueChange((newValue as Number).toInt())
                            }
                        }
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        if (previousValue.isNotBlank()) {
            Text(
                text = previousValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                textAlign = TextAlign.Center,
            )
        }

        val displayValue = if (isDecimal) {
            val d = value.toDouble()
            if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)
        } else {
            value.toInt().toString()
        }

        Text(
            text = displayValue,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )

        Text(
            text = "scroll",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
        )
    }
}
