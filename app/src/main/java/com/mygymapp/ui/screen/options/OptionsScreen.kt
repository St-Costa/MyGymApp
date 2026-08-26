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
import androidx.compose.material3.Switch
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
    viewModel: OptionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Opzioni") },
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
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ProfileSection(
                profile = uiState.profile,
                onGenderChange = viewModel::updateGender,
                onHeightChange = viewModel::updateHeight,
                onBirthYearChange = viewModel::updateBirthYear,
            )
            ServerSettingsSection(
                uiState = uiState,
                onServerUrlChange = viewModel::setSyncServerUrl,
                onBearerTokenChange = viewModel::setSyncBearerToken,
                onEnabledChange = viewModel::setSyncEnabled,
                onTestConnection = viewModel::testConnection,
                onResyncAll = viewModel::resyncAll,
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
                onStepCheckClick = viewModel::checkStepCounterDebug,
                onSendDebugEcg = viewModel::sendDebugEcg,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        modifier = Modifier.width(120.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Height (cm)", style = MaterialTheme.typography.bodySmall)
                    ScrollPickerInput(
                        value = profile.heightCm,
                        onValueChange = { onHeightChange(it.toInt()) },
                        buttonStep = 1.0,
                        isModified = true,
                        modifier = Modifier.width(120.dp),
                    )
                }
            }

            Text(
                if (profile.birthYear != null) {
                    "HRmax: ${profile.hrMax} BPM (Tanaka formula)"
                } else {
                    "Imposta l'anno di nascita per calcoli accurati (HRmax stimato: ${profile.hrMax} BPM)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Just the server connection settings: URL, token, sync on/off, "test connection". Every
 * other sync-related control (debug ECG, pending queue, resync, diagnostics) lives in
 * grouped with the other debug tools. Also shows the pending-sync queue and a manual "send
 * now" action — but only while sync is OFF, since with it on the periodic workers already
 * drain the queue on their own (every few hours) and the button would be redundant. */
@Composable
private fun ServerSettingsSection(
    uiState: OptionsUiState,
    onServerUrlChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onResyncAll: () -> Unit,
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
            Text("Server", style = MaterialTheme.typography.titleMedium)
            Text(
                "Invia i dati al tuo server self-hosted via Tailscale.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = uiState.syncServerUrl,
                onValueChange = onServerUrlChange,
                label = { Text("URL server (Tailscale)") },
                placeholder = { Text("https://tuo-host.tailnet.ts.net") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.syncBearerToken,
                onValueChange = onBearerTokenChange,
                label = { Text("Token condiviso (bearer)") },
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
                Text("Sincronizzazione attiva", style = MaterialTheme.typography.bodyLarge)
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
                        Text("Verifica connessione")
                    }
                }
                when (uiState.syncConnectionTestResult) {
                    true -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Raggiungibile",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    false -> Icon(
                        Icons.Default.Error,
                        contentDescription = "Non raggiungibile",
                        tint = MaterialTheme.colorScheme.error,
                    )
                    null -> {}
                }
            }

            if (!uiState.syncEnabled) {
                PendingItemsList(uiState)

                Button(
                    onClick = onResyncAll,
                    enabled = !uiState.syncIsResyncing && configured,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.syncIsResyncing) {
                        Text("Invio… ${(uiState.syncResyncProgress * 100).toInt()}%")
                    } else {
                        Text("Invia dati in coda")
                    }
                }
                if (uiState.syncIsResyncing) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { uiState.syncResyncProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Pending-sync counts as one bullet per record type, instead of a single summed number. */
@Composable
private fun PendingItemsList(uiState: OptionsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("In coda:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(
            "Sessioni" to uiState.syncSessionsPending,
            "Pesate" to uiState.syncScalePending,
            "ECG" to uiState.syncEcgPending,
        ).forEach { (label, count) ->
            Text(
                "• $label: $count",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (uiState.syncLastSuccessAt != null) {
            Text(
                "Ultimo invio: ${uiState.syncLastSuccessAt.take(16).replace('T', ' ')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * All debug tools in one card: bilancia BLE, contapassi (Health Connect), ECG debug send.
 * Each is its own short explanation + button, separated by a divider.
 */
@Composable
private fun DebugSection(
    uiState: OptionsUiState,
    onScaleDebugClick: () -> Unit,
    onStepCheckClick: () -> Unit,
    onSendDebugEcg: () -> Unit,
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

            // Bilancia
            Text(
                "Verifica la connessione BLE alla bilancia.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onScaleDebugClick, modifier = Modifier.fillMaxWidth()) {
                Text("Scale BLE Debug")
            }

            androidx.compose.material3.HorizontalDivider()

            // Contapassi — confirms Health Connect + its steps read permission actually work,
            // without waiting for the next morning's readiness test to find out — this is what
            // caught Phase 41's TYPE_STEP_COUNTER approach not working on real hardware, see
            // CHANGELOG Phase 42. "Ultime 24h" is loose phrasing for "since the last saved
            // checkpoint" — see OptionsViewModel.checkStepCounterDebug.
            Text(
                "Verifica sensore e permesso leggendo i passi delle ultime 24h.",
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
                    Text("Controlla passi ultime 24h")
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
                "Registra ~10s di ECG dal Polar connesso e la invia al server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onSendDebugEcg,
                enabled = configured && !uiState.ecgDebugRecording,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.ecgDebugRecording) {
                    Text("Registrazione ECG… ${uiState.ecgDebugSecondsLeft}s")
                } else {
                    Text("Debug ECG: registra e invia")
                }
            }
            if (uiState.ecgDebugResult != null) {
                Text(
                    uiState.ecgDebugResult,
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
            Text("Settimana powerlifting", style = MaterialTheme.typography.titleMedium)
            Text(
                "Seleziona settimana e intervallo per l'avviso periodico in sessione.",
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
                    Text("Disattiva avviso powerlifting")
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
                Icon(Icons.Default.ChevronLeft, contentDescription = "Mese precedente")
            }
            val monthName = displayedMonth.month
                .getDisplayName(TextStyle.FULL, Locale.getDefault())
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
                Icon(Icons.Default.ChevronRight, contentDescription = "Mese successivo")
            }
        }

        // Weekday headers (Mon..Sun)
        Row(modifier = Modifier.fillMaxWidth()) {
            val labels = listOf("L", "M", "M", "G", "V", "S", "D")
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
        Text("Ogni", style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onSetInterval(intervalWeeks - 1) },
                enabled = intervalWeeks > 1,
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Diminuisci")
            }
            Text(
                "$intervalWeeks",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = { onSetInterval(intervalWeeks + 1) }) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Aumenta")
            }
        }
        Text("settimane", style = MaterialTheme.typography.bodyLarge)
    }
}
