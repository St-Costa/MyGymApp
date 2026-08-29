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
import com.mygymapp.data.polar.BatteryLifeState
import com.mygymapp.data.polar.DisconnectStats
import com.mygymapp.data.polar.Readiness
import com.mygymapp.data.polar.ReadinessResult
import com.mygymapp.data.sync.BackupVerifyReport
import com.mygymapp.ui.components.BackupVerifyBox
import com.mygymapp.ui.screen.heartrate.PolarDeviceBox
import com.mygymapp.ui.screen.heartrate.ReadinessCard
import java.time.LocalDate

/**
 * Debug-only screen (reachable from Options → Debug) that renders the end-of-routine
 * status panels — server-sync box, readiness card, Polar-connection box, and
 * Polar-disconnection box — with hand-made sample data, so their look can be checked
 * without a real flaky Polar session. Not part of any real user flow.
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
            SyncStatusBox(SessionSyncStatus.CHECKING)
            SyncStatusBox(
                SessionSyncStatus.SENT,
                bytesSent = 4_312,
                durationMs = 1_240,
                serverStatus = "duplicate",
            )
            SyncStatusBox(SessionSyncStatus.PENDING)
            SyncStatusBox(SessionSyncStatus.FAILED)
            SyncStatusBox(SessionSyncStatus.SYNC_OFF)

            Text("Box backup schede/esercizi (docs/BACKUP.md §3.7)", style = MaterialTheme.typography.titleSmall)
            BackupVerifyBox(running = true)
            BackupVerifyBox(
                report = BackupVerifyReport(
                    exercisesPushed = listOf("incline-db-press-ex-0f1e2d3c.md", "calf-raise-ex-7ca58254.md"),
                    routinesPushed = listOf("pull-rt-71284f58.md"),
                    exercisesUnchanged = 40,
                    routinesUnchanged = 6,
                    verifiedIdentical = 49,
                    serverSummary = "3 file accettati (stored). Manifest: 49 file schede/routine sul server, 49 riletti identici.",
                ),
            )
            BackupVerifyBox(
                report = BackupVerifyReport(
                    exercisesPushed = emptyList(),
                    routinesPushed = emptyList(),
                    exercisesUnchanged = 42,
                    routinesUnchanged = 7,
                    verifiedIdentical = 49,
                    serverSummary = "0 file accettati (stored). Manifest: 49 file schede/routine sul server, 49 riletti identici.",
                ),
            )
            BackupVerifyBox(
                report = BackupVerifyReport(
                    exercisesPushed = listOf("squat-ex-11112222.md"),
                    routinesPushed = emptyList(),
                    exercisesUnchanged = 41,
                    routinesUnchanged = 7,
                    hashMismatch = listOf("push-rt-b997ec72.md"),
                    missingAfter = listOf("leg-rt-97a2091f.md"),
                    verifiedIdentical = 46,
                    serverSummary = "1 file accettati (stored). Manifest: 48 file schede/routine sul server, 46 riletti identici, 1 con hash diverso, 1 ancora mancanti.",
                ),
            )
            BackupVerifyBox(error = "Manifest non recuperato: HTTP 404: not found")

            Text("Scheda readiness", style = MaterialTheme.typography.titleSmall)
            ReadinessCard(
                readiness = ReadinessResult(
                    readiness = Readiness.NORMAL,
                    lnRmssd = 4.1,
                    restingHr = 54,
                    secondsRemaining = 0,
                    recommendation = "HRV within normal range. Proceed with planned workout.",
                    stepsPreviousDay = 12_641,
                    bpmTrace = listOf(62, 61, 63, 60, 59, 58, 60, 62, 64, 61, 59, 57, 58, 60, 61, 63, 62, 60, 59, 58, 57, 59, 61, 62, 70, 66, 63, 61, 60, 58),
                ),
                vo2max = 48.2,
            )

            Text("Box connessione Polar", style = MaterialTheme.typography.titleSmall)
            PolarDeviceBox(
                deviceId = "Polar H10 B79A5D2A",
                batteryLevel = 88,
                batteryLow = false,
                batteryLife = BatteryLifeState(
                    installedAtDate = LocalDate.now().minusDays(37),
                    installedAtLevel = 100,
                    activeSeconds = 62L * 3600,
                    lastLevel = 88,
                    lastReadingEpochSec = 0,
                    pastLifeSeconds = listOf(410L * 3600, 380L * 3600),
                ),
            )

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
