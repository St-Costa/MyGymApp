package com.mygymapp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BodyPartAutocomplete(
    value: String,
    onValueChange: (String) -> Unit,
    existingBodyparts: List<String>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val filtered = if (value.isBlank()) {
        existingBodyparts
    } else {
        existingBodyparts.filter { it.contains(value, ignoreCase = true) }
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text("Body Part") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(visible = expanded && filtered.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.small,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp),
                ) {
                    itemsIndexed(filtered) { index, bodypart ->
                        Text(
                            text = bodypart,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onValueChange(bodypart)
                                    expanded = false
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                        if (index != filtered.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                        }
                    }
                }
            }
        }
    }
}
