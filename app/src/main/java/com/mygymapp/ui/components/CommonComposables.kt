package com.mygymapp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

@Composable
fun FullscreenLoading(paddingValues: PaddingValues = PaddingValues(0.dp)) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyStateBox(message: String, paddingValues: PaddingValues = PaddingValues(0.dp)) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun DeleteConfirmationDialog(
    itemName: String,
    itemType: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $itemType?") },
        text = { Text("\"$itemName\" will be permanently deleted.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun RoundStepButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.size(32.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * "μ = +1.2" style average-weekly-delta badge shown in a [ChartCard] header, next to the
 * metric title. Shared by [ScaleTrendSection] and [CardioMetricsTrendSection] — same format
 * string/style was previously duplicated 4x across those two files.
 */
@Composable
internal fun WeeklyDeltaLabel(delta: Double) {
    Text(
        "μ = %+.1f".format(delta),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
    )
}

/**
 * All-time PR badge shown above the sets on the strength / superset exercise screens, e.g.
 * `RM` with a small subscript `PR` then `: 92`. [prefix] is the metric ("RM" for estimated
 * 1RM, "T" for tonnage); [value] is the pre-formatted right-hand side.
 *
 * Two of these stack (RM on top of T) inside a `Column` with `2.dp` spacing — pass a
 * `Modifier` with `fillMaxWidth()` so `TextAlign.Center` centers each line. [style] defaults
 * to `titleMedium` (strength screen); the superset card passes `labelMedium`.
 */
@Composable
fun PrBadge(
    prefix: String,
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Text(
        text = buildAnnotatedString {
            append(prefix)
            withStyle(
                SpanStyle(fontSize = 0.7.em, baselineShift = BaselineShift(-0.25f)),
            ) { append("PR") }
            append(": ")
            append(value)
        },
        style = style,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
