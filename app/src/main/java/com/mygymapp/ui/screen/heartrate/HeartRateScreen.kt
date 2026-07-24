package com.mygymapp.ui.screen.heartrate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.mygymapp.ui.components.CardioTrendSection
import com.mygymapp.ui.components.ScrollPickerInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartRateScreen(
    onBack: () -> Unit,
    viewModel: HeartRateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.startScan()
        }
    }

    fun startScanWithPermissionCheck() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val allGranted = required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            viewModel.startScan()
        } else {
            permissionLauncher.launch(required)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Heart Rate") },
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
            when (uiState.connectionState) {
                ConnectionState.DISCONNECTED -> {
                    DisconnectedContent(
                        uiState = uiState,
                        onStartScan = { startScanWithPermissionCheck() },
                        onStopScan = { viewModel.stopScan() },
                        onConnectDevice = { viewModel.connectToDevice(it) },
                    )
                }
                ConnectionState.CONNECTING -> {
                    Spacer(modifier = Modifier.height(48.dp))
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Connecting...", style = MaterialTheme.typography.titleMedium)
                }
                ConnectionState.CONNECTED -> {
                    ConnectedContent(
                        uiState = uiState,
                        onDisconnect = { viewModel.disconnect() },
                    )
                }
            }

            // Cardio trend (last 4 weeks)
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            CardioTrendSection(report = uiState.cardioTrend)

            // Profile section (always visible)
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            ProfileSection(
                profile = uiState.profile,
                onAgeChange = { viewModel.updateAge(it) },
                onWeightChange = { viewModel.updateWeight(it) },
                onGenderChange = { viewModel.updateGender(it) },
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProfileSection(
    profile: com.mygymapp.data.polar.UserProfile,
    onAgeChange: (Int) -> Unit,
    onWeightChange: (Double) -> Unit,
    onGenderChange: (Boolean) -> Unit,
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
            Text("Weight (kg)", style = MaterialTheme.typography.bodySmall)
            ScrollPickerInput(
                value = profile.weightKg,
                onValueChange = { onWeightChange(it.toDouble()) },
                buttonStep = 1.0,
                isDecimal = true,
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
private fun ColumnScope.DisconnectedContent(
    uiState: HeartRateUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (String) -> Unit,
) {
    if (uiState.isScanning) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text("Scanning for devices...", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onStopScan) {
            Text("Stop Scan")
        }
    } else {
        Icon(
            Icons.Default.BluetoothSearching,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onStartScan) {
            Icon(Icons.Default.Bluetooth, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Search for Devices")
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    if (uiState.discoveredDevices.isNotEmpty()) {
        Text(
            "Devices Found",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (device in uiState.discoveredDevices) {
                DeviceCard(
                    device = device,
                    onClick = { onConnectDevice(device.deviceId) },
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredDevice,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "ID: ${device.deviceId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${device.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BatteryFull,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${uiState.batteryLevel}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
