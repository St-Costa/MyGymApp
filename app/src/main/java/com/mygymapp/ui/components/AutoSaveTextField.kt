package com.mygymapp.ui.components

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

@Composable
fun AutoSaveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minLines: Int = 3,
    debounceMs: Long = 500L,
) {
    var localValue by remember(value) { mutableStateOf(value) }
    var pendingSave by remember { mutableStateOf<String?>(null) }
    val currentOnSave by rememberUpdatedState(onSave)

    LaunchedEffect(pendingSave) {
        val toSave = pendingSave ?: return@LaunchedEffect
        delay(debounceMs)
        currentOnSave(toSave)
        pendingSave = null
    }

    // Flush any pending save when the composable leaves the composition
    // (e.g. user navigates back before the debounce delay elapses).
    DisposableEffect(Unit) {
        onDispose {
            pendingSave?.let(currentOnSave)
        }
    }

    OutlinedTextField(
        value = localValue,
        onValueChange = { newValue ->
            localValue = newValue
            onValueChange(newValue)
            pendingSave = newValue
        },
        label = { Text(label) },
        minLines = minLines,
        modifier = modifier,
    )
}
