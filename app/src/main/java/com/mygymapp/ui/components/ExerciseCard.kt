package com.mygymapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.theme.ForzaColor
import com.mygymapp.ui.theme.StretchColor

@Composable
fun ExerciseCard(
    exercise: Exercise,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = when (exercise.type) {
        ExerciseType.FORZA -> ForzaColor
        ExerciseType.STRETCH -> StretchColor
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(2.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exercise.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (exercise.type == ExerciseType.FORZA) "Strength" else "Stretch",
                style = MaterialTheme.typography.labelLarge,
                color = borderColor,
            )
        }
    }
}
