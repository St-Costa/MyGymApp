package com.mygymapp.ui.screen.sessionprogress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.polar.DisconnectStats
import com.mygymapp.data.polar.DropCause
import com.mygymapp.ui.components.FullscreenLoading
import com.mygymapp.ui.components.TonnageLineChart
import com.mygymapp.ui.components.trimpColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionProgressScreen(
    justCompleted: Boolean = false,
    onBack: () -> Unit,
    onDone: () -> Unit = onBack,
    onNavigateHome: () -> Unit,
    viewModel: SessionProgressViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reached right after finishing a workout: force the summary to be seen before Home,
    // instead of letting it get buried under the exercise list like before.
    if (justCompleted) {
        androidx.activity.compose.BackHandler { onDone() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (justCompleted) "Sessione completata" else uiState.routineName) },
                navigationIcon = {
                    if (!justCompleted) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateHome) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            FullscreenLoading()
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (justCompleted) {
                Text(
                    text = uiState.routineName,
                    style = MaterialTheme.typography.titleLarge,
                )
                SyncStatusBox(
                    uiState.syncStatus,
                    bytesSent = uiState.syncBytesSent,
                    durationMs = uiState.syncDurationMs,
                    serverStatus = uiState.syncServerStatus,
                )
                if (uiState.backupVerifyRunning ||
                    uiState.backupVerifyError != null ||
                    uiState.backupVerifyReport != null
                ) {
                    com.mygymapp.ui.components.BackupVerifyBox(
                        running = uiState.backupVerifyRunning,
                        error = uiState.backupVerifyError,
                        report = uiState.backupVerifyReport,
                    )
                }
                if (uiState.polarDrops.hadDrops) {
                    PolarConnectionBox(uiState.polarDrops)
                }
            }
            // Calories + TRIMP summary (if recorded)
            if (uiState.sessionCalories > 0 || uiState.sessionTrimp > 0 || uiState.sessionSteps != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${uiState.sessionCalories.toInt()}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color(0xFFFF9800),
                            )
                            Text("kcal", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${uiState.sessionTrimp.toInt()}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = trimpColor(uiState.sessionTrimp),
                            )
                            Text("TRIMP", style = MaterialTheme.typography.bodySmall)
                        }
                        if (uiState.vo2max > 0) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "%.1f".format(uiState.vo2max),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                Text("VO2max", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        // Display-only — read fresh from Health Connect for this session's
                        // own time window, never saved onto the session or synced (the
                        // figure that IS synced is a whole-day average, a different number
                        // entirely — see SessionProgressUiState.sessionSteps).
                        if (uiState.sessionSteps != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${uiState.sessionSteps}",
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                Text("passi", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // Total tonnage chart — no filter/selector, just the trend across recent sessions
            if (uiState.sessionTonnage.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Tonnellaggio totale: ${"%.1f".format(uiState.sessionTonnage.lastOrNull() ?: 0.0)} kg",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TonnageLineChart(
                            data = uiState.sessionTonnage,
                            labels = uiState.sessionLabels,
                            modifier = Modifier.fillMaxWidth(),
                            secondaryData = if (uiState.sessionBestE1RM.size == uiState.sessionTonnage.size) {
                                uiState.sessionBestE1RM
                            } else null,
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Nessun dato disponibile", style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (justCompleted) {
                androidx.compose.material3.Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Fatto")
                }
            }
        }
    }
}

/**
 * Reassurance card confirming the just-finished session made it to the server — or flagging
 * that it didn't, so a failed upload doesn't go unnoticed until "Options" is opened days
 * later. Always shown, even when sync is off — otherwise its absence could be mistaken for a
 * bug rather than for the deliberate off state. Given real vertical presence (icon + title +
 * subtitle) so it reads as a status panel, not a thin strip.
 */
@Composable
internal fun SyncStatusBox(
    status: SessionSyncStatus,
    bytesSent: Long = 0,
    durationMs: Long = 0,
    serverStatus: String = "",
) {
    data class Spec(val icon: androidx.compose.ui.graphics.vector.ImageVector?, val title: String, val subtitle: String, val color: Color)
    val spec = when (status) {
        SessionSyncStatus.CHECKING -> Spec(
            // Static two-arrows-in-a-circle glyph, not the animated spinner (null icon) —
            // conveys "sync check" without motion.
            Icons.Default.Sync,
            "Verifica connessione al server…",
            "Sto controllando che il salvataggio remoto sia raggiungibile.",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SessionSyncStatus.SENT -> Spec(
            Icons.Default.CheckCircle,
            "Sessione ricevuta dal server",
            // The ledger only flips to SENT on a confirmed 2xx (docs/SYNC.md §1.3 step 4),
            // and the server echoes what it did — so this really is a delivery receipt.
            when (serverStatus) {
                "duplicate" -> "Il server aveva già questa versione: nessuna riscrittura."
                else -> "Il server ha confermato la ricezione e l'ha salvata."
            },
            Color(0xFF4CAF50),
        )
        SessionSyncStatus.FAILED -> Spec(
            Icons.Default.Error,
            "Invio al server fallito",
            "Verrà ritentato automaticamente in background.",
            MaterialTheme.colorScheme.error,
        )
        SessionSyncStatus.PENDING -> Spec(
            null,
            "Invio al server in corso…",
            "Attendi qualche secondo.",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SessionSyncStatus.SYNC_OFF -> Spec(
            Icons.Default.CloudOff,
            "Sync col server disattivata",
            "La sessione resta salvata solo su questo telefono.",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (spec.icon != null) {
                Icon(spec.icon, contentDescription = null, tint = spec.color, modifier = Modifier.size(32.dp))
            } else {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(spec.title, style = MaterialTheme.typography.titleMedium, color = spec.color)
                Text(
                    spec.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status == SessionSyncStatus.SENT && bytesSent > 0) {
                    Text(
                        "${formatTransferSize(bytesSent)} in ${formatTransferDuration(durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "820 B" / "4.2 KB" / "1.8 MB" — binary units, one decimal above the KB threshold. */
private fun formatTransferSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** "0.4 s" / "12 s" — sub-10s keeps one decimal, above that whole seconds. */
private fun formatTransferDuration(ms: Long): String = when {
    ms < 10_000 -> "%.1f s".format(ms / 1000.0)
    else -> "${ms / 1000} s"
}

/**
 * Shown only when the Polar strap dropped mid-session (involuntarily). One row per drop
 * with its technical detail — time into the session, RSSI (strap's last-known signal, "n/d"
 * when Android gave nothing), the HR-sample gap right before the drop, and the cause guessed
 * from that gap. Header line says whether every drop auto-recovered (it almost always does —
 * a reconnect resumes HR/ECG for the same session, workout data is unaffected). Full raw
 * detail is also in the app log (`Disconnected: … rssi= hrGap=`).
 */
@Composable
internal fun PolarConnectionBox(stats: DisconnectStats) {
    val n = stats.count
    val header = if (n == 1) "Fascia Polar — 1 disconnessione" else "Fascia Polar — $n disconnessioni"
    val recovery = if (stats.everyDropAutoRecovered) {
        "Riconnessa da sola ogni volta: i dati della sessione sono completi."
    } else {
        "L'ultima disconnessione non si è ripristinata prima della fine della sessione."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(32.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(header, style = MaterialTheme.typography.titleMedium, color = Color(0xFFFF9800))
                    Text(
                        recovery,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            stats.drops.forEachIndexed { i, drop ->
                DropRow(index = i + 1, drop = drop)
            }
            Text(
                "RSSI = potenza del segnale della fascia (più vicino a 0 = più forte). " +
                    "Il tempo senza battiti prima del calo indica la causa probabile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DropRow(index: Int, drop: DisconnectStats.SessionDrop) {
    val rssi = if (drop.rssi != 0) "${drop.rssi} dBm" else "n/d"
    val gap = if (drop.hrGapSec > 0) "${drop.hrGapSec}s senza battiti prima del calo"
              else "battito ricevuto fino all'istante del calo"
    val cause = when (drop.cause) {
        DropCause.RANGE_OR_FADE -> "distanza o telefono coperto dal corpo"
        DropCause.INTERFERENCE -> "interferenza radio o contatto elettrodo intermittente"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "$index)  a ${formatMmSs(drop.atElapsedSec)} dall'inizio  ·  RSSI $rssi",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "$gap  →  $cause",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun formatMmSs(totalSec: Long): String = "%d:%02d".format(totalSec / 60, totalSec % 60)
