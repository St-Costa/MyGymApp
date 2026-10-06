package com.mygymapp.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Descrizione collassata quando vuota: resta visibile come riga singola
 * (solo label attenuata, senza freccia — l'attenuazione segnala che è vuota)
 * così occupa poco spazio ma si può aggiungere con un tap. Il tap espande
 * il campo e gli dà subito il focus (tastiera aperta), senza bisogno di un
 * secondo tap per iniziare a scrivere.
 *
 * Quando il valore non è vuoto il campo è sempre mostrato per intero.
 */
@Composable
fun CollapsibleAutoSaveDescription(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: (String) -> Unit,
    label: String = "Description",
    modifier: Modifier = Modifier,
    minLines: Int = 2,
    debounceMs: Long = 500L,
) {
    var expanded by rememberSaveable { mutableStateOf(value.isNotBlank()) }
    // Il valore arriva in async dai ViewModel (stato iniziale "" poi notes reale):
    // se poi risulta non-vuoto, mostra sempre il campo esteso.
    LaunchedEffect(value.isNotBlank()) {
        if (value.isNotBlank()) expanded = true
    }

    if (!expanded) {
        CollapsedDescriptionRow(
            label = label,
            onExpand = { expanded = true },
            modifier = modifier,
        )
    } else {
        var localValue by remember(value) { mutableStateOf(value) }
        var pendingSave by remember { mutableStateOf<String?>(null) }
        val currentOnSave by rememberUpdatedState(onSave)
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current

        LaunchedEffect(pendingSave) {
            val toSave = pendingSave ?: return@LaunchedEffect
            delay(debounceMs)
            currentOnSave(toSave)
            pendingSave = null
        }

        DisposableEffect(Unit) {
            onDispose {
                pendingSave?.let(currentOnSave)
            }
        }

        // Arrivando dall'espansione (tap sulla riga collassata) il focus parte
        // da qui: tastiera subito pronta, un solo tap per iniziare a scrivere.
        LaunchedEffect(Unit) {
            if (value.isBlank()) {
                focusRequester.requestFocus()
                keyboard?.show()
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
            modifier = modifier.focusRequester(focusRequester),
            trailingIcon = {
                // Solo da vuota ha senso ricollassare (ripensamento): con testo
                // l'icona sparisce per non rubare spazio al contenuto.
                if (localValue.isBlank()) {
                    IconButton(onClick = { expanded = false }) {
                        Icon(
                            Icons.Filled.ExpandLess,
                            contentDescription = "Riduci descrizione",
                        )
                    }
                }
            },
        )
    }
}

/**
 * Variante senza autosave per l'editor esercizi (salva su back): stesso
 * comportamento collassa-se-vuota + tap-per-espandere-con-focus.
 */
@Composable
fun CollapsibleNotesField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Notes / Description",
    modifier: Modifier = Modifier,
    minLines: Int = 3,
) {
    var expanded by rememberSaveable { mutableStateOf(value.isNotBlank()) }
    LaunchedEffect(value.isNotBlank()) {
        if (value.isNotBlank()) expanded = true
    }

    if (!expanded) {
        CollapsedDescriptionRow(
            label = label,
            onExpand = { expanded = true },
            modifier = modifier,
        )
    } else {
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current

        LaunchedEffect(Unit) {
            if (value.isBlank()) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
        }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            minLines = minLines,
            modifier = modifier.focusRequester(focusRequester),
            trailingIcon = {
                if (value.isBlank()) {
                    IconButton(onClick = { expanded = false }) {
                        Icon(
                            Icons.Filled.ExpandLess,
                            contentDescription = "Riduci descrizione",
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun CollapsedDescriptionRow(
    label: String,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        onClick = onExpand,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
