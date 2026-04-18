package com.mygymapp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.polar.ConnectionState
import com.mygymapp.data.polar.PolarManager
import com.mygymapp.data.polar.RecoveryState

private val RedLight = Color(0xFFEF5350)
private val YellowLight = Color(0xFFFFCA28)
private val GreenLight = Color(0xFF66BB6A)
private val LightOff = Color(0xFF3A3A3A)

/**
 * Shows current HR + recovery semaphore. Hides itself when Polar is not connected.
 * Call [PolarManager.onSetCompleted] to trigger recovery tracking.
 */
@Composable
fun HeartRateBar(
    modifier: Modifier = Modifier,
    viewModel: HeartRateBarViewModel = hiltViewModel(),
) {
    val polarManager = viewModel.polarManager
    val connectionState by polarManager.connectionState.collectAsState()
    val heartRate by polarManager.heartRate.collectAsState()
    val recoveryState by polarManager.recoveryState.collectAsState()

    if (connectionState != ConnectionState.CONNECTED) return

    val hr = heartRate ?: return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Heart icon
        Icon(
            Icons.Default.Favorite,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = RedLight,
        )
        Spacer(modifier = Modifier.width(8.dp))

        // BPM
        Text(
            text = "$hr",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "BPM",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline(),
        )

        Spacer(modifier = Modifier.weight(1f))

        // Semaphore
        Semaphore(recoveryState = recoveryState)
    }
}

@Composable
private fun Semaphore(recoveryState: RecoveryState) {
    val redColor by animateColorAsState(
        targetValue = if (recoveryState == RecoveryState.RECOVERING) RedLight else LightOff,
        animationSpec = tween(300),
        label = "red",
    )
    val yellowColor by animateColorAsState(
        targetValue = if (recoveryState == RecoveryState.ALMOST_READY) YellowLight else LightOff,
        animationSpec = tween(300),
        label = "yellow",
    )
    val greenColor by animateColorAsState(
        targetValue = if (recoveryState == RecoveryState.READY) GreenLight else LightOff,
        animationSpec = tween(300),
        label = "green",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SemaphoreLight(color = redColor)
        SemaphoreLight(color = yellowColor)
        SemaphoreLight(color = greenColor)
    }
}

@Composable
private fun SemaphoreLight(color: Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color),
    )
}
