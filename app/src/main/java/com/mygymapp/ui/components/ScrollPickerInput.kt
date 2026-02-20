package com.mygymapp.ui.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    scrollStep: Double = 5.0,
    buttonStep: Double = 1.0,
    minValue: Double = 0.0,
    isDecimal: Boolean = false,
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

    Row(
        modifier = modifier
            .pointerInput(scrollStep, minValue) {
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
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { applyChange(-buttonStep) },
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text("\u2212", style = MaterialTheme.typography.titleMedium)
        }

        Text(
            text = displayValue,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )

        OutlinedButton(
            onClick = { applyChange(buttonStep) },
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}
