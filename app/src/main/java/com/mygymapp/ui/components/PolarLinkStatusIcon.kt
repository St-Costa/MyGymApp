package com.mygymapp.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mygymapp.data.polar.PolarLinkStatus

/** Ambre used for both the "connecting" spinner and the "no signal" warning. */
private val Amber = Color(0xFFFFCA28)
private val Green = Color(0xFF66BB6A)

/**
 * Single source of truth for how a [PolarLinkStatus] looks, so every screen that shows the
 * strap's state (heart-rate pairing, Polar BLE debug, in-session cardio) renders it the
 * same way:
 * - CONNECTED  → Bluetooth icon, green
 * - NO_SIGNAL  → Warning ⚠️, amber (BLE up but strap silent — taken off / powered off)
 * - CONNECTING → Sync arrows, amber (first handshake or Android retrying a drop)
 * - DISCONNECTED → LinkOff, muted grey
 */
@Composable
fun PolarLinkStatusIcon(
    status: PolarLinkStatus,
    modifier: Modifier = Modifier,
) {
    when (status) {
        PolarLinkStatus.CONNECTED -> Icon(
            Icons.Default.BluetoothSearching,
            contentDescription = "Connesso",
            tint = Green,
            modifier = modifier,
        )
        PolarLinkStatus.NO_SIGNAL -> Icon(
            Icons.Default.Warning,
            contentDescription = "Nessun segnale dal Polar",
            tint = Amber,
            modifier = modifier,
        )
        PolarLinkStatus.CONNECTING -> Icon(
            Icons.Default.Sync,
            contentDescription = "Connessione in corso",
            tint = Amber,
            modifier = modifier,
        )
        PolarLinkStatus.DISCONNECTED -> Icon(
            Icons.Default.LinkOff,
            contentDescription = "Non connesso",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/** Short Italian label matching [PolarLinkStatusIcon], for places that show text too. */
fun polarLinkStatusLabel(status: PolarLinkStatus): String = when (status) {
    PolarLinkStatus.CONNECTED -> "Connesso"
    PolarLinkStatus.NO_SIGNAL -> "Nessun segnale"
    PolarLinkStatus.CONNECTING -> "Connessione…"
    PolarLinkStatus.DISCONNECTED -> "Disconnesso"
}
