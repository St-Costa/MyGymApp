package com.mygymapp.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mygymapp.data.polar.PolarLinkStatus
import com.mygymapp.ui.theme.SignalAmber
import com.mygymapp.ui.theme.SignalGreen

/** Ambre used for both the "connecting" spinner and the "no signal" warning. */
private val Amber = SignalAmber
private val Green = SignalGreen

/**
 * Single source of truth for how a [PolarLinkStatus] looks, so every screen that shows the
 * strap's state (heart-rate pairing, Polar BLE debug, in-session cardio) renders it the
 * same way:
 * - CONNECTED  → Bluetooth icon, green
 * - NO_SIGNAL  → Warning ⚠️, amber (BLE up but strap silent — taken off / powered off)
 * - CONNECTING → Sync arrows, amber (first handshake or Android retrying a drop)
 * - STANDBY → Bluetooth icon, muted grey-blue (link up, streams paused to save battery)
 * - DISCONNECTED → LinkOff, muted grey
 */
@Composable
fun PolarLinkStatusIcon(
    status: PolarLinkStatus,
    modifier: Modifier = Modifier,
) {
    when (status) {
        PolarLinkStatus.CONNECTED -> Icon(
            Icons.AutoMirrored.Filled.BluetoothSearching,
            contentDescription = "Connected",
            tint = Green,
            modifier = modifier,
        )
        PolarLinkStatus.NO_SIGNAL -> Icon(
            Icons.Default.Warning,
            contentDescription = "No signal from the Polar",
            tint = Amber,
            modifier = modifier,
        )
        PolarLinkStatus.CONNECTING -> Icon(
            Icons.Default.Sync,
            contentDescription = "Connecting",
            tint = Amber,
            modifier = modifier,
        )
        PolarLinkStatus.STANDBY -> Icon(
            Icons.AutoMirrored.Filled.BluetoothSearching,
            contentDescription = "On standby to save battery",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        PolarLinkStatus.DISCONNECTED -> Icon(
            Icons.Default.LinkOff,
            contentDescription = "Not connected",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/** Short English label matching [PolarLinkStatusIcon], for places that show text too. */
fun polarLinkStatusLabel(status: PolarLinkStatus): String = when (status) {
    PolarLinkStatus.CONNECTED -> "Connected"
    PolarLinkStatus.NO_SIGNAL -> "No signal"
    PolarLinkStatus.CONNECTING -> "Connecting…"
    PolarLinkStatus.STANDBY -> "Standby"
    PolarLinkStatus.DISCONNECTED -> "Disconnected"
}
