package com.mygymapp.ui.screen.sessionprogress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mygymapp.data.polar.DisconnectStats

/**
 * Debug-only screen (reachable from Options → Debug) that renders the two end-of-routine
 * status panels — server-sync box and Polar-disconnection box — with hand-made sample data,
 * so their look can be checked without a real flaky Polar session. Not part of any real
 * user flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSummaryPreviewScreen(onBack: () -> Unit) {
    val sampleDrops = DisconnectStats(
        everyDropAutoRecovered = true,
        drops = listOf(
            DisconnectStats.SessionDrop(atElapsedSec = 41 * 60 + 59, rssi = -68, hrGapSec = 0),
            DisconnectStats.SessionDrop(atElapsedSec = 42 * 60 + 39, rssi = 0, hrGapSec = 1),
            DisconnectStats.SessionDrop(atElapsedSec = 43 * 60 + 10, rssi = -74, hrGapSec = 6),
            DisconnectStats.SessionDrop(atElapsedSec = 43 * 60 + 34, rssi = -71, hrGapSec = 2),
            DisconnectStats.SessionDrop(atElapsedSec = 54 * 60 + 32, rssi = -80, hrGapSec = 9),
        ),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anteprima riepilogo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Box invio al server", style = MaterialTheme.typography.titleSmall)
            SyncStatusBox(SessionSyncStatus.SENT)
            SyncStatusBox(SessionSyncStatus.PENDING)
            SyncStatusBox(SessionSyncStatus.FAILED)
            SyncStatusBox(SessionSyncStatus.SYNC_OFF)

            Text("Box disconnessioni Polar", style = MaterialTheme.typography.titleSmall)
            PolarConnectionBox(sampleDrops)
            PolarConnectionBox(
                DisconnectStats(
                    everyDropAutoRecovered = false,
                    drops = listOf(
                        DisconnectStats.SessionDrop(atElapsedSec = 12 * 60 + 3, rssi = -83, hrGapSec = 11),
                    ),
                ),
            )
        }
    }
}
