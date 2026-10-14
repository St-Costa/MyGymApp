package com.mygymapp.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Testo dei pulsanti azione primari (COMPLETE/SKIP, START/STOP, SEND, ...):
 * leggermente più grande del default dei Button (labelLarge 14sp) e ExtraBold.
 * Sempre maiuscolo e su singola riga con ellissi — i testi maiuscoli sono più
 * larghi, quindi il troncamento garantisce che non sbordino dal Button.
 */
@Composable
fun ActionButtonText(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
        ),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
    )
}

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
                Text(
                    "DELETE",
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "CANCEL",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
        },
    )
}

/**
 * Round −/+ stepper: 48dp touch target (a11y minimum) with the visible circle kept at
 * 32dp — same split as [PickerButton] in `ScrollPickerInput.kt`. The outer Box owns the
 * single tap handler (no ripple, but the shared haptic tick); the inner circle is pure
 * visual, so a tap can never fire twice. Semantics sit on the whole 48dp box.
 */
@Composable
fun RoundStepButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val currentOnClick = rememberUpdatedState(onClick)
    val currentPerformTick = rememberUpdatedState(rememberHapticTick())
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = {
                    currentPerformTick.value()
                    currentOnClick.value()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
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
        // Explicit US locale: the default IT locale would render "+1,2" with a comma.
        "μ = %+.1f".format(java.util.Locale.US, delta),
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
