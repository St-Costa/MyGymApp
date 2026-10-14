package com.mygymapp.ui.screen.options

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import com.mygymapp.BuildConfig
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import com.mygymapp.ui.components.ScrollPickerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(
    onBack: () -> Unit,
    onNavigateToScaleDebug: () -> Unit = {},
    onNavigateToPolarDebug: () -> Unit = {},
    onNavigateToSummaryPreview: () -> Unit = {},
    viewModel: OptionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Options") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ProfileSection(
                profile = uiState.profile,
                onGenderChange = viewModel::updateGender,
                onHeightChange = viewModel::updateHeight,
                onBirthYearChange = viewModel::updateBirthYear,
            )
            SoundSection(
                volume = uiState.beepVolume,
                onVolumeChange = viewModel::onBeepVolumeChange,
                onVolumeChangeFinished = viewModel::previewBeepVolume,
            )
            ServerSettingsSection(
                uiState = uiState,
                onServerUrlChange = viewModel::setSyncServerUrl,
                onBearerTokenChange = viewModel::setSyncBearerToken,
                onEnabledChange = viewModel::setSyncEnabled,
                onTestConnection = viewModel::testConnection,
                onVerifyBackup = viewModel::verifyBackupRoundTrip,
                onResyncAll = viewModel::resyncAll,
                onRestoreFromServer = viewModel::restoreFromServer,
            )
            PowerliftingSection(
                anchorMonday = uiState.anchorMonday,
                intervalWeeks = uiState.intervalWeeks,
                onSelectWeek = viewModel::selectWeek,
                onSetInterval = viewModel::setInterval,
                onClear = viewModel::clearSchedule,
            )
            DebugSection(
                uiState = uiState,
                onScaleDebugClick = onNavigateToScaleDebug,
                onPolarDebugClick = onNavigateToPolarDebug,
                onStepCheckClick = viewModel::checkStepCounterDebug,
                onSendDebugEcg = viewModel::sendDebugEcg,
                onRunPolarSelfTest = viewModel::runPolarSelfTest,
                onSummaryPreviewClick = onNavigateToSummaryPreview,
            )
        }
    }
}

/** Gender, birth year, height — feeds every age/gender-dependent formula (Keytel calories,
 * Tanaka HRmax, TRIMP, VO2max, BIA body-fat %). Moved here (first card) from the Heart &
 * Scale screen since it's a one-off setting, not something tweaked during a live BLE
 * session. Birth year is the sole source of age for every formula (replaces a plain "Age"
 * field so it stays correct as years pass); null until the user fills it in, falling back
 * to a fixed default age until then. */
@Composable
private fun ProfileSection(
    profile: com.mygymapp.data.polar.UserProfile,
    onGenderChange: (Boolean) -> Unit,
    onHeightChange: (Int) -> Unit,
    onBirthYearChange: (Int?) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Profile", style = MaterialTheme.typography.titleMedium)

            // Gender chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                FilterChip(
                    selected = profile.isMale,
                    onClick = { onGenderChange(true) },
                    label = { Text("Male") },
                )
                FilterChip(
                    selected = !profile.isMale,
                    onClick = { onGenderChange(false) },
                    label = { Text("Female") },
                )
            }

            val currentYear = java.time.Year.now().value
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Birth year", style = MaterialTheme.typography.bodySmall)
                    ScrollPickerInput(
                        value = profile.birthYear ?: (currentYear - 30),
                        onValueChange = { onBirthYearChange(it.toInt().takeIf { y -> y in 1900..currentYear }) },
                        buttonStep = 1.0,
                        isModified = profile.birthYear != null,
                        // No fixed width: the content is 48+56+48 = 152dp, so width(120.dp)
                        // overflowed 16dp per side. Wrap sizing stays exact by construction.
                        fieldName = "birth year",
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Height (cm)", style = MaterialTheme.typography.bodySmall)
                    ScrollPickerInput(
                        value = profile.heightCm,
                        onValueChange = { onHeightChange(it.toInt()) },
                        buttonStep = 1.0,
                        isModified = true,
                        // No fixed width here either (see birth year above).
                        fieldName = "height",
                    )
                }
            }

            Text(
                if (profile.birthYear != null) {
                    "HRmax: ${profile.hrMax} BPM (Fox/Tanaka/Gulati average)"
                } else {
                    "Set your birth year for accurate results (estimated HRmax: ${profile.hrMax} BPM)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Readiness-beep volume (0–100). Dragging persists immediately; releasing plays one
 * beep at the just-set volume so the user hears exactly what they picked.
 */
@Composable
private fun SoundSection(
    volume: Int,
    onVolumeChange: (Int) -> Unit,
    onVolumeChangeFinished: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Beep volume", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { onVolumeChange(it.toInt()) },
                    onValueChangeFinished = onVolumeChangeFinished,
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$volume",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                "Readiness beep loudness. Releasing the slider plays a preview.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Just the server connection settings: URL, token, sync on/off, "test connection". Every
 * other sync-related control (debug ECG, pending queue, resync, diagnostics) lives grouped
 * with the other debug tools. Also shows the pending-sync queue and a manual "send
 * now" action — but only while sync is OFF, since with it on the periodic workers already
 * drain the queue on their own (every few hours) and the button would be redundant. */
@Composable
private fun ServerSettingsSection(
    uiState: OptionsUiState,
    onServerUrlChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onVerifyBackup: () -> Unit,
    onResyncAll: () -> Unit,
    onRestoreFromServer: () -> Unit,
) {
    val configured = uiState.syncServerUrl.isNotBlank() && uiState.syncBearerToken.isNotBlank()
    var showRestoreConfirm by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Server", style = MaterialTheme.typography.titleMedium)
            Text(
                "Send your data to your self-hosted server via Tailscale.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = uiState.syncServerUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Server URL (Tailscale)") },
                placeholder = { Text("https://your-host.tailnet.ts.net") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.syncBearerToken,
                onValueChange = onBearerTokenChange,
                label = { Text("Shared token (bearer)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Sync enabled", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = uiState.syncEnabled, onCheckedChange = onEnabledChange, enabled = configured)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = uiState.syncServerUrl.isNotBlank() && !uiState.syncIsTestingConnection,
                ) {
                    if (uiState.syncIsTestingConnection) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            "CHECK",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                        )
                    }
                }
                when (uiState.syncConnectionTestResult) {
                    true -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Reachable",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    false -> Icon(
                        Icons.Default.Error,
                        contentDescription = "Unreachable",
                        tint = MaterialTheme.colorScheme.error,
                    )
                    null -> {}
                }
            }

            // Full-store backup round-trip: keep this with the server connection controls.
            Text(
                "Push exercises/routines not yet on the server, then re-download and verify they are identical.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onVerifyBackup,
                enabled = configured && !uiState.backupVerifyRunning,
                modifier = Modifier.fillMaxWidth(0.85f).align(Alignment.CenterHorizontally),
            ) {
                if (uiState.backupVerifyRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    com.mygymapp.ui.components.ActionButtonText("VERIFY BACKUP")
                }
            }
            if (uiState.backupVerifyRunning || uiState.backupVerifyError != null || uiState.backupVerifyReport != null) {
                com.mygymapp.ui.components.BackupVerifyBox(
                    running = uiState.backupVerifyRunning,
                    progressText = uiState.backupVerifyProgress,
                    error = uiState.backupVerifyError,
                    report = uiState.backupVerifyReport,
                )
            }

            // Sync status is shown in both toggle states. With sync OFF nothing drains on
            // its own, so the queue can only grow — the summed count plus a manual "send
            // now". With sync ON the workers drain it in the background, but there's a
            // window (e.g. sync was just turned on after a session finished) where you
            // want to *confirm* everything landed: show a green "all synced" line, or the
            // per-type breakdown while anything is still pending.
            if (uiState.syncEnabled) {
                if (uiState.syncPendingCount == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Column {
                            Text(
                                "All synced",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (uiState.syncLastSuccessAt != null) {
                                Text(
                                    "Last send: ${uiState.syncLastSuccessAt.take(16).replace('T', ' ')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    PendingItemsList(uiState)
                }
            } else {
                PendingItemsList(uiState)
            }

            // "Invia": a full backfill re-enqueue. Always available (not just
            // while sync is OFF) — it's the escape hatch when you're not sure the
            // background workers delivered everything.
            Button(
                onClick = onResyncAll,
                enabled = !uiState.syncIsResyncing && configured,
                modifier = Modifier.fillMaxWidth(0.85f).align(Alignment.CenterHorizontally),
            ) {
                if (uiState.syncIsResyncing) {
                    com.mygymapp.ui.components.ActionButtonText("SENDING… ${(uiState.syncResyncProgress * 100).toInt()}%")
                } else {
                    com.mygymapp.ui.components.ActionButtonText("SEND")
                }
            }
            if (uiState.syncIsResyncing) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { uiState.syncResyncProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Full-store restore (docs/BACKUP.md §3.6): pull-only, never deletes local
            // files. Confirmed because it can overwrite local edits that haven't synced.
            // Shown regardless of the toggle — it's the post-wipe recovery action, and a
            // fresh install may well have sync still ON from a restored config.
            if (configured) {
                OutlinedButton(
                    onClick = { showRestoreConfirm = true },
                    enabled = !uiState.syncIsRestoring,
                    modifier = Modifier.fillMaxWidth(0.85f).align(Alignment.CenterHorizontally),
                ) {
                    com.mygymapp.ui.components.ActionButtonText(if (uiState.syncIsRestoring) "RESTORING…" else "RESTORE")
                }
                uiState.syncRestoreResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showRestoreConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restore from server") },
            text = {
                Text(
                    "Download every missing or different file from the server (routines, " +
                        "exercises, sessions, weigh-ins…). Deletes nothing locally.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRestoreConfirm = false
                    onRestoreFromServer()
                }) {
                    Text(
                        "RESTORE",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRestoreConfirm = false }) {
                    Text(
                        "CANCEL",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            },
        )
    }
}

/** Pending-sync counts as one bullet per record type, instead of a single summed number. */
@Composable
private fun PendingItemsList(uiState: OptionsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Queued:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(
            "Sessions" to uiState.syncSessionsPending,
            "Weigh-ins" to uiState.syncScalePending,
            "ECG" to uiState.syncEcgPending,
            "Routines/exercises" to uiState.syncRepoPending,
        ).forEach { (label, count) ->
            Text(
                "• $label: $count",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (uiState.syncLastSuccessAt != null) {
            Text(
                "Last send: ${uiState.syncLastSuccessAt.take(16).replace('T', ' ')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * All debug tools in one card: bilancia BLE, Polar BLE, contapassi (Health Connect), ECG
 * debug send, backup round-trip check. Each is its own short explanation + button, separated
 * by a divider.
 */
@Composable
private fun DebugSection(
    uiState: OptionsUiState,
    onScaleDebugClick: () -> Unit,
    onPolarDebugClick: () -> Unit,
    onStepCheckClick: () -> Unit,
    onSendDebugEcg: () -> Unit,
    onRunPolarSelfTest: () -> Unit,
    onSummaryPreviewClick: () -> Unit,
) {
    val configured = uiState.syncServerUrl.isNotBlank() && uiState.syncBearerToken.isNotBlank()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Debug", style = MaterialTheme.typography.titleMedium)

            // Installed build, for support/diagnostics (which code produced this log/backup).
            Text(
                "App version ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            androidx.compose.material3.HorizontalDivider()

            // Bilancia
            Text(
                "Check the BLE connection to the scale.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onScaleDebugClick, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "SCALE BLE",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }

            androidx.compose.material3.HorizontalDivider()

            // Polar — same as the bilancia button above: scan for the strap and connect.
            // The first successful connect re-saves the device ID so future scans auto-connect
            // to it again — needed after a reinstall/data-wipe clears that pref.
            Text(
                "Find your Polar and connect it. The first connection saves it for future auto-connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onPolarDebugClick, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "POLAR BLE",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }

            androidx.compose.material3.HorizontalDivider()

            // Anteprima dei due riquadri di stato mostrati a fine routine (invio al server +
            // disconnessioni Polar), con dati finti — per controllarne l'aspetto senza dover
            // fare una sessione reale con la fascia che si scollega.
            Text(
                "Show the end-of-routine boxes (server + Polar disconnects) with sample data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onSummaryPreviewClick, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "SUMMARY PREVIEW",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }

            androidx.compose.material3.HorizontalDivider()

            // Contapassi — confirms Health Connect + its steps read permission actually work,
            // without waiting for the next morning's readiness test to find out — this is what
            // caught Phase 41's TYPE_STEP_COUNTER approach not working on real hardware, see
            // CHANGELOG Phase 42. "Ultime 24h" is loose phrasing for "since the last saved
            // checkpoint" — see OptionsViewModel.checkStepCounterDebug.
            Text(
                "Check the sensor and permission by reading the last 24h of steps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onStepCheckClick,
                enabled = !uiState.stepDebugRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.stepDebugRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        "24H STEPS",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            }
            if (uiState.stepDebugResult != null) {
                Text(
                    uiState.stepDebugResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            androidx.compose.material3.HorizontalDivider()

            // ECG — records ~10s of raw ECG from the currently connected Polar device and
            // immediately queues+sends it via the real workout-session pipeline, to exercise
            // POST /v1/ecg end to end without needing a full session (docs/SYNC.md "Fourth
            // record type: raw ECG").
            Text(
                "Record ~10s of ECG from the connected Polar and send it to the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onSendDebugEcg,
                enabled = configured && !uiState.ecgDebugRecording,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.ecgDebugRecording) {
                    Text(
                        "ECG… ${uiState.ecgDebugSecondsLeft}S",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                } else {
                    Text(
                        "DEBUG ECG",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            }
            if (uiState.ecgDebugResult != null) {
                Text(
                    uiState.ecgDebugResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            androidx.compose.material3.HorizontalDivider()

            // Test Polar — verifica la pipeline senza allenarsi: se la fascia
            // non è connessa la cerca da solo (30s), poi campiona 60s di HR live ed
            // esegue i check delle formule su dati sintetici (drift, Keytel, TRIMP,
            // regole di picco). Read-only: non crea sessioni, non scrive history, non
            // accoda sync — la lista delle sessioni resta intatta.
            Text(
                "Everything from here: find the Polar, sample 60s of live HR + formula checks, without dirtying sessions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRunPolarSelfTest,
                enabled = !uiState.polarSelfTestRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.polarSelfTestRunning) {
                    if (uiState.polarSelfTestSearching) {
                        Text(
                            "SEARCHING… ${uiState.polarSelfTestSecondsLeft}S",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                        )
                    } else {
                        Text(
                            "TESTING… ${uiState.polarSelfTestSecondsLeft}S",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                        )
                    }
                } else {
                    Text(
                        "POLAR SELF-TEST",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            }
            if (uiState.polarSelfTestResult != null) {
                Text(
                    uiState.polarSelfTestResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        }
    }
}

@Composable
private fun PowerliftingSection(
    anchorMonday: LocalDate?,
    intervalWeeks: Int,
    onSelectWeek: (LocalDate) -> Unit,
    onSetInterval: (Int) -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Powerlifting week", style = MaterialTheme.typography.titleMedium)
            Text(
                "Select week and interval for the periodic in-session notice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            WeekCalendar(
                anchorMonday = anchorMonday,
                onSelectWeek = onSelectWeek,
            )

            IntervalSelector(intervalWeeks = intervalWeeks, onSetInterval = onSetInterval)

            if (anchorMonday != null) {
                OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "DISABLE NOTICE",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekCalendar(
    anchorMonday: LocalDate?,
    onSelectWeek: (LocalDate) -> Unit,
) {
    val today = remember { LocalDate.now() }
    var displayedMonth by remember { mutableStateOf(YearMonth.from(today)) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Month header with navigation arrows
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = { displayedMonth = displayedMonth.minusMonths(1) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
            }
            val monthName = displayedMonth.month
                .getDisplayName(TextStyle.FULL, java.util.Locale.US)
                .replaceFirstChar { it.uppercase() }
            Text(
                "$monthName ${displayedMonth.year}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            IconButton(
                onClick = { displayedMonth = displayedMonth.plusMonths(1) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
            }
        }

        // Weekday headers (Mon..Sun)
        Row(modifier = Modifier.fillMaxWidth()) {
            val labels = listOf("M", "T", "W", "T", "F", "S", "S")
            labels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Day grid, drawn one week (row) at a time so the whole week can be highlighted.
        val firstOfMonth = displayedMonth.atDay(1)
        // Monday of the week containing the 1st.
        val gridStart = firstOfMonth.minusDays((firstOfMonth.dayOfWeek.value - 1).toLong())
        val weekRows = 6
        for (week in 0 until weekRows) {
            val rowMonday = gridStart.plusWeeks(week.toLong())
            // Stop drawing once the row is entirely past the displayed month.
            if (YearMonth.from(rowMonday).isAfter(displayedMonth) &&
                YearMonth.from(rowMonday.plusDays(6)).isAfter(displayedMonth)
            ) break

            val isSelectedWeek = anchorMonday != null && rowMonday == anchorMonday
            val rowBg = if (isSelectedWeek) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(rowBg)
                    .clickable { onSelectWeek(rowMonday) },
            ) {
                for (dow in 0 until 7) {
                    val date = rowMonday.plusDays(dow.toLong())
                    val inMonth = YearMonth.from(date) == displayedMonth
                    val isToday = date == today
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.5f),
                        contentAlignment = Alignment.Center,
                    ) {
                        val todayRing = if (isToday && !isSelectedWeek) {
                            Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                        } else Modifier
                        Box(modifier = todayRing, contentAlignment = Alignment.Center) {
                            Text(
                                "${date.dayOfMonth}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isSelectedWeek -> MaterialTheme.colorScheme.onPrimary
                                    inMonth -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntervalSelector(intervalWeeks: Int, onSetInterval: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Every", style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onSetInterval(intervalWeeks - 1) },
                enabled = intervalWeeks > 1,
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Decrease")
            }
            Text(
                "$intervalWeeks",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = { onSetInterval(intervalWeeks + 1) }) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Increase")
            }
        }
        Text("weeks", style = MaterialTheme.typography.bodyLarge)
    }
}
