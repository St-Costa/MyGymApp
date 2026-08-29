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
import com.mygymapp.data.sync.BackupDiffEntry
import com.mygymapp.data.sync.BackupError
import com.mygymapp.data.sync.BackupErrorCategory
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

            Text("Box backup sul server (docs/BACKUP.md §3.7)", style = MaterialTheme.typography.titleSmall)
            BackupVerifyBox(running = true)
            // Clean: a range edit (one line -, one line +) plus a brand-new exercise.
            BackupVerifyBox(
                report = BackupVerifyReport(
                    exercises = listOf(
                        BackupDiffEntry("Calf Raise", added = 1, removed = 1),
                        BackupDiffEntry("Incline DB Press", added = 12, removed = 0),
                    ),
                    routines = listOf(BackupDiffEntry("Pull", added = 3, removed = 2)),
                    exercisesLocal = 42, exercisesMatching = 42,
                    routinesLocal = 7, routinesMatching = 7,
                    sessionsLocal = 62, sessionsMatching = 62,
                    elapsedMs = 1_840, bytesUploaded = 1_432,
                ),
            )
            // Nothing to send — everything already aligned.
            BackupVerifyBox(
                report = BackupVerifyReport(
                    exercisesLocal = 42, exercisesMatching = 42,
                    routinesLocal = 7, routinesMatching = 7,
                    sessionsLocal = 62, sessionsMatching = 62,
                    elapsedMs = 640, bytesUploaded = 0,
                ),
            )
            // With problems: an error under Routine + a count-off Sessioni row.
            BackupVerifyBox(
                report = BackupVerifyReport(
                    exercises = listOf(BackupDiffEntry("Squat", added = 1, removed = 1)),
                    exercisesLocal = 42, exercisesMatching = 41,
                    routinesLocal = 7, routinesMatching = 6,
                    sessionsLocal = 62, sessionsMatching = 61,
                    sessionsChanged = listOf("2026-08-28_rt-71284f58_b38ae530"),
                    errors = listOf(
                        BackupError(BackupErrorCategory.ROUTINE, "push-rt-b997ec72.md", "HTTP 422: contentHash mismatch"),
                        BackupError(BackupErrorCategory.ROUTINE, "leg-rt-97a2091f.md", "assente dal manifest dopo il push"),
                    ),
                    elapsedMs = 3_120, bytesUploaded = 2_890_000,
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
