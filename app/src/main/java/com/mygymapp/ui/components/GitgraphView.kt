package com.mygymapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mygymapp.ui.theme.GitgraphEmpty
import com.mygymapp.ui.theme.GitgraphGreen
import com.mygymapp.ui.theme.GitgraphRed

enum class DayStatus {
    NONE,
    IMPROVED,
    REGRESSED,
}

@Composable
fun GitgraphView(
    days: List<DayStatus>,
    todayIndex: Int,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 4 rows of 7
        for (row in 0 until 4) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (col in 0 until 7) {
                    val index = row * 7 + col
                    val status = days.getOrElse(index) { DayStatus.NONE }
                    val color = when (status) {
                        DayStatus.NONE -> GitgraphEmpty
                        DayStatus.IMPROVED -> GitgraphGreen
                        DayStatus.REGRESSED -> GitgraphRed
                    }
                    val isToday = index == todayIndex

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(shape)
                            .background(color)
                            .then(
                                if (isToday) Modifier.border(2.dp, Color.White, shape)
                                else Modifier
                            ),
                    )
                }
            }
        }
    }
}
