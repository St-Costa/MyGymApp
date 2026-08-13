package com.mygymapp.ui.screen.heartrate

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.polar.ConnectionState
import com.mygymapp.data.polar.Readiness
import com.mygymapp.data.scale.ScaleConnectionState
import com.mygymapp.ui.components.CardioMetricsTrendSection
import com.mygymapp.ui.components.ScaleTrendSection
import com.mygymapp.ui.components.ScrollPickerInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartRateScreen(
    onBack: () -> Unit,
    viewModel: HeartRateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasBlePermissions(): Boolean = blePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothEnabled(): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java)
        return manager?.adapter?.isEnabled == true
    }

    // Location must be on for BLE scans on Android <= 11; from Android 12+ the
    // neverForLocation BLUETOOTH_SCAN flag (already set in the manifest) removes
    // this requirement.
    fun isLocationRequiredAndDisabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val manager = context.getSystemService(LocationManager::class.java)
        return manager?.let { !it.isProviderEnabled(LocationManager.GPS_PROVIDER) && !it.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } ?: false
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.startScan()
        }
    }

    val scalePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.startScaleScan()
        }
    }

    // Health Connect's steps permission is no longer requested from here — every runtime
    // permission the app needs (BLE, Health Connect steps) is now requested once, centrally,
    // from MainScreen right when the app opens (see MainScreen's PermissionRequests), so a
    // permission revoked or never granted gets re-prompted from the Home screen the user
    // always passes through, not only if/when they happen to open this specific screen.

    fun startScanWithPermissionCheck() {
        if (hasBlePermissions()) {
            viewModel.startScan()
        } else {
            permissionLauncher.launch(blePermissions())
        }
    }

    fun startScaleScanWithPermissionCheck() {
        if (hasBlePermissions()) {
            viewModel.startScaleScan()
        } else {
            scalePermissionLauncher.launch(blePermissions())
        }
    }

    // Both devices auto-scan on ON_RESUME, not just on first composition —
    // a plain LaunchedEffect(Unit) only fires once and never retries after
    // e.g. the user backgrounds the app to toggle Bluetooth in a system
    // dialog (like the OEM "available devices" popup Android/One UI shows
    // right after Bluetooth is turned on) and returns without navigating
    // away from this screen, which never recreates the composable.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val latestUiState = androidx.compose.runtime.rememberUpdatedState(uiState)
    DisposableEffect(lifecycleOwner) {
        var isFirstResume = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // Skip the very first ON_RESUME: it fires immediately on initial
                // composition, when the scale/Polar scan hasn't had a chance to
                // run yet, and force-restarting here would tear down a
                // just-established connection every time the screen opens.
                if (isFirstResume) {
                    isFirstResume = false
                    return@LifecycleEventObserver
                }
                // Force-restart both scans on later resumes only: a previous
                // scan may be stuck in SCANNING (e.g. interrupted mid-flight by
                // a system dialog backgrounding the app), and startScan()
                // no-ops if it isn't strictly DISCONNECTED.
                val state = latestUiState.value
                if (state.scaleConnectionState != ScaleConnectionState.CONNECTED) {
                    viewModel.stopScaleScan()
                    startScaleScanWithPermissionCheck()
                }
                if (state.connectionState != ConnectionState.CONNECTED) {
                    viewModel.stopScan()
                    startScanWithPermissionCheck()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // First-open auto-scan (separate from the resume observer above).
    LaunchedEffect(Unit) {
        if (uiState.scaleConnectionState == ScaleConnectionState.DISCONNECTED) {
            startScaleScanWithPermissionCheck()
        }
        if (uiState.connectionState == ConnectionState.DISCONNECTED) {
            startScanWithPermissionCheck()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("❤️⚖️") },
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
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.BluetoothSearching,
                contentDescription = null,
                modifier = Modifier.size(62.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (!isBluetoothEnabled()) {
                RadioWarningBox("Attiva il Bluetooth per collegare i dispositivi")
            } else if (isLocationRequiredAndDisabled()) {
                RadioWarningBox("Attiva la posizione per cercare dispositivi Bluetooth")
            } else {
                DeviceStatusHeader(
                    heartRateConnectionState = uiState.connectionState,
                    scaleConnectionState = uiState.scaleConnectionState,
                    scaleReading = uiState.scaleReading,
                )
            }

            if (uiState.connectionState == ConnectionState.CONNECTED) {
                ConnectedContent(
                    uiState = uiState,
                    onDisconnect = { viewModel.disconnect() },
                )
            }

            // Scale trend graphs (weight, BMI, fat/lean %)
            HorizontalDivider(modifier = Modifier.padding(vertical = 32.dp))
            ScaleTrendSection(report = uiState.scaleTrend)

            // Cardio metrics trend (Resting HR, HRR60s, VO2max)
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            CardioMetricsTrendSection(report = uiState.cardioMetricsTrend)

            // Profile section (always visible)
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            ProfileSection(
                profile = uiState.profile,
                onAgeChange = { viewModel.updateAge(it) },
                onGenderChange = { viewModel.updateGender(it) },
                onHeightChange = { viewModel.updateHeight(it) },
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RadioWarningBox(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCA28).copy(alpha = 0.15f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFCA28))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DeviceStatusHeader(
    heartRateConnectionState: ConnectionState,
    scaleConnectionState: ScaleConnectionState,
    scaleReading: com.mygymapp.data.scale.ScaleReading?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("❤️", fontSize = 36.sp)
            Spacer(modifier = Modifier.height(12.dp))
            HeartRateStatusIcon(heartRateConnectionState)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚖️", fontSize = 36.sp)
            Spacer(modifier = Modifier.height(12.dp))
            ScaleStatusIcon(scaleConnectionState, scaleReading)
        }
    }

    val weighInComplete = scaleConnectionState == ScaleConnectionState.CONNECTED &&
        scaleReading?.weightKg != null &&
        scaleReading.impedanceOhm != null
    if (weighInComplete) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Pesata completata, puoi scendere ✓",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF66BB6A),
        )
    }
}

@Composable
private fun HeartRateStatusIcon(connectionState: ConnectionState) {
    when (connectionState) {
        ConnectionState.DISCONNECTED -> Icon(
            Icons.Default.LinkOff,
            contentDescription = "Non connesso",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ConnectionState.CONNECTING -> Icon(
            Icons.Default.Sync,
            contentDescription = "Connessione in corso",
            tint = Color(0xFFFFCA28),
        )
        ConnectionState.CONNECTED -> Icon(
            Icons.Default.BluetoothSearching,
            contentDescription = "Connesso",
            tint = Color(0xFF66BB6A),
        )
    }
}

/**
 * Two meaningful connected states: plain Bluetooth icon while connected and
 * waiting/measuring, and a checkmark once weight+impedance have both been
 * received for this weigh-in ("you can step off the scale now").
 */
@Composable
private fun ScaleStatusIcon(
    connectionState: ScaleConnectionState,
    reading: com.mygymapp.data.scale.ScaleReading?,
) {
    when (connectionState) {
        ScaleConnectionState.DISCONNECTED -> Icon(
            Icons.Default.LinkOff,
            contentDescription = "Non connessa",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ScaleConnectionState.SCANNING, ScaleConnectionState.CONNECTING -> Icon(
            Icons.Default.Sync,
            contentDescription = "Connessione in corso",
            tint = Color(0xFFFFCA28),
        )
        ScaleConnectionState.CONNECTED -> {
            val weighInComplete = reading?.weightKg != null && reading.impedanceOhm != null
            if (weighInComplete) {
                Icon(
                    Icons.Default.TaskAlt,
                    contentDescription = "Pesata completata, puoi scendere",
                    modifier = Modifier.size(32.dp),
                    tint = Color(0xFF66BB6A),
                )
            } else {
                Icon(
                    Icons.Default.BluetoothSearching,
                    contentDescription = "Connessa",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ProfileSection(
    profile: com.mygymapp.data.polar.UserProfile,
    onAgeChange: (Int) -> Unit,
    onGenderChange: (Boolean) -> Unit,
    onHeightChange: (Int) -> Unit,
) {
    Text(
        "Profile",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(12.dp))

    // Gender chips
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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

    Spacer(modifier = Modifier.height(12.dp))

    // Age and Weight pickers
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Age", style = MaterialTheme.typography.bodySmall)
            ScrollPickerInput(
                value = profile.age,
                onValueChange = { onAgeChange(it.toInt()) },
                buttonStep = 1.0,
                isModified = true,
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

    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "HRmax: ${profile.hrMax} BPM (Tanaka formula)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ColumnScope.ConnectedContent(
    uiState: HeartRateUiState,
    onDisconnect: () -> Unit,
) {
    // HR display
    Icon(
        Icons.Default.Favorite,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = Color.Red,
    )
    Text(
        text = "${uiState.heartRate ?: "--"}",
        fontSize = 72.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        "BPM",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Session stats: calories + TRIMP
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color(0xFFFF9800),
            )
            Text(
                "${uiState.sessionCalories.toInt()}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "kcal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "TRIMP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${uiState.sessionTrimp.toInt()}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = com.mygymapp.ui.components.trimpColor(uiState.sessionTrimp),
            )
            Text(
                trimpIntensityLabel(uiState.sessionTrimp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // HRV Readiness
    val readiness = uiState.readiness
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (readiness.readiness == Readiness.MEASURING) {
                Text(
                    "HRV Readiness",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Lie still... ${readiness.secondsRemaining}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { 1f - readiness.secondsRemaining / 60f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            } else {
                val readinessColor = when (readiness.readiness) {
                    Readiness.DELOAD_RECOMMENDED -> Color(0xFFEF5350)
                    Readiness.LIGHT_DAY -> Color(0xFFFFCA28)
                    Readiness.NORMAL -> Color(0xFF66BB6A)
                    Readiness.GOOD -> Color(0xFF4CAF50)
                    Readiness.PEAK -> Color(0xFF2196F3)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val readinessLabel = when (readiness.readiness) {
                    Readiness.DELOAD_RECOMMENDED -> "DELOAD"
                    Readiness.LIGHT_DAY -> "LIGHT DAY"
                    Readiness.NORMAL -> "NORMAL"
                    Readiness.GOOD -> "GOOD"
                    Readiness.PEAK -> "PEAK"
                    Readiness.NO_BASELINE -> "BASELINE ${if (readiness.lnRmssd > 0) "(collecting)" else ""}"
                    else -> ""
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Readiness", style = MaterialTheme.typography.titleSmall)
                        Text(
                            readinessLabel,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = readinessColor,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Resting HR: ${readiness.restingHr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (readiness.lnRmssd > 0) {
                            Text(
                                "LnRMSSD: %.1f".format(readiness.lnRmssd),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (uiState.vo2max != null) {
                            Text(
                                "VO2max: %.1f".format(uiState.vo2max),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Lands a moment after the rest of readiness (Health Connect query
                        // is async) — see PolarManager.finishReadinessMeasurement(). Absent
                        // entirely (not "0") until then, and stays absent if there was no
                        // previous checkpoint to diff against on a fresh install.
                        if (readiness.stepsAvgPerDay != null) {
                            Text(
                                "Passi: ${readiness.stepsAvgPerDay.toInt()}" +
                                    if (readiness.stepsDaysSpanned == 1) "" else " (media ${readiness.stepsDaysSpanned}gg)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (readiness.recommendation.isNotBlank()) {
                    Text(
                        readiness.recommendation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Battery + device info
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Connected to ${uiState.connectedDeviceId ?: "device"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (uiState.batteryLevel != null) {
            val batteryColor = if (uiState.batteryLow) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (uiState.batteryLow) Icons.Default.BatteryAlert else Icons.Default.BatteryFull,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = batteryColor,
                )
                Text(
                    "${uiState.batteryLevel}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = batteryColor,
                )
            }
        }
    }

    // The H10's percentage comes from cell voltage, which stays near 3V until the
    // CR2025 is nearly spent — the strap typically goes silent while still reporting
    // 50-60%. Hence the warning at 70% rather than the usual 20%.
    if (uiState.batteryLow) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                "Batteria fascia al ${uiState.batteryLevel}% — sostituisci la CR2025 a breve",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Spacer(modifier = Modifier.weight(1f))

    OutlinedButton(onClick = onDisconnect) {
        Text("Disconnect")
    }
    Spacer(modifier = Modifier.height(16.dp))
}

private fun trimpIntensityLabel(trimp: Double): String = when {
    trimp < 50 -> "light"
    trimp < 100 -> "moderate"
    trimp < 200 -> "hard"
    else -> "very hard"
}
