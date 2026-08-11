package com.mygymapp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.mygymapp.data.polar.HrZone
import com.mygymapp.data.polar.HrZoneMinutes
import com.mygymapp.data.polar.PolarManager
import com.mygymapp.data.polar.RecoveryState

fun trimpColor(trimp: Double): Color = when {
    trimp < 50 -> Color(0xFF66BB6A)   // green
    trimp < 100 -> Color(0xFFFFCA28)  // yellow
    trimp < 200 -> Color(0xFFFF9800)  // orange
    else -> Color(0xFFEF5350)         // red
}

private val RedLight = Color(0xFFEF5350)
private val YellowLight = Color(0xFFFFCA28)
private val GreenLight = Color(0xFF66BB6A)
private val LightOff = Color(0xFF3A3A3A)

// Same palette as the sync server's dashboard "Time in HR zone" chart, so the live
// in-session widget and the post-sync retrospective view read consistently.
fun hrZoneColor(zone: HrZone): Color = when (zone) {
    HrZone.BELOW_Z1 -> Color(0xFF4A5063)
    HrZone.Z1 -> Color(0xFF5B9DFF)
    HrZone.Z2 -> Color(0xFF3ECF7E)
    HrZone.Z3 -> Color(0xFFE0B23E)
    HrZone.Z4 -> Color(0xFFFF9F6B)
    HrZone.Z5 -> Color(0xFFE05A5A)
}

fun hrZoneLabel(zone: HrZone): String = when (zone) {
    HrZone.BELOW_Z1 -> "<Z1"
    HrZone.Z1 -> "Z1"
    HrZone.Z2 -> "Z2"
    HrZone.Z3 -> "Z3"
    HrZone.Z4 -> "Z4"
    HrZone.Z5 -> "Z5"
}

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
    val calories by polarManager.sessionCalories.collectAsState()
    val trimp by polarManager.sessionTrimp.collectAsState()
    val currentZone by polarManager.currentHrZone.collectAsState()
    val currentZonePercent by polarManager.currentHrZonePercent.collectAsState()
    val zoneMinutes by polarManager.hrZoneMinutes.collectAsState()

    if (connectionState != ConnectionState.CONNECTED) return

    val hr = heartRate ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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

            Spacer(modifier = Modifier.width(12.dp))

            // Calories
            Text(
                text = "${calories.toInt()}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF9800),
            )
            Text(
                text = "kcal",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.width(10.dp))

            // TRIMP
            Text(
                text = "${trimp.toInt()}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = trimpColor(trimp),
            )
            Text(
                text = "T",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Zone chip
            currentZone?.let { zone ->
                ZoneChip(zone = zone, percent = currentZonePercent)
                Spacer(modifier = Modifier.width(10.dp))
            }

            // Semaphore
            Semaphore(recoveryState = recoveryState)
        }

        // Time-in-zone bar: only once there's something to show
        if (zoneMinutes.total > 0.0) {
            Spacer(modifier = Modifier.height(8.dp))
            TimeInZoneBar(zoneMinutes)
        }
    }
}

/** Compact always-visible zone badge, e.g. "Z3 · 74%". */
@Composable
private fun ZoneChip(zone: HrZone, percent: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(hrZoneColor(zone).copy(alpha = 0.22f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(hrZoneColor(zone)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${hrZoneLabel(zone)} · $percent%",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Thin stacked bar of per-zone minutes accumulated so far this session — the live,
 * in-progress-session counterpart to the dashboard's "Time in HR zone" chart.
 */
@Composable
private fun TimeInZoneBar(minutes: HrZoneMinutes) {
    val total = minutes.total.coerceAtLeast(0.01)
    val segments = listOf(
        HrZone.BELOW_Z1 to minutes.belowZone1,
        HrZone.Z1 to minutes.zone1,
        HrZone.Z2 to minutes.zone2,
        HrZone.Z3 to minutes.zone3,
        HrZone.Z4 to minutes.zone4,
        HrZone.Z5 to minutes.zone5,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        segments.forEach { (zone, value) ->
            if (value > 0.0) {
                val fraction by animateFloatAsState(
                    targetValue = (value / total).toFloat(),
                    animationSpec = tween(300),
                    label = "zoneFraction",
                )
                Box(
                    modifier = Modifier
                        .weight(fraction.coerceAtLeast(0.001f))
                        .fillMaxWidth()
                        .background(hrZoneColor(zone)),
                )
            }
        }
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
