package com.mygymapp.ui.screen.polar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.polar.ConnectionState
import com.mygymapp.ui.components.PolarLinkStatusIcon
import com.mygymapp.ui.components.polarLinkStatusLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolarDebugScreen(
    onBack: () -> Unit,
    viewModel: PolarDebugViewModel = hiltViewModel(),
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.startScan()
        }
    }

    fun startScanWithPermissionCheck() {
        val required = blePermissions()
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
                title = { Text("Polar BLE Debug") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PolarLinkStatusIcon(uiState.linkStatus)
                Text("Status: ${polarLinkStatusLabel(uiState.linkStatus)}")
            }
            Text("Raw BLE state: ${uiState.connectionState}")
            Text(
                "Dispositivo salvato: ${uiState.knownDeviceId ?: "nessuno"}",
            )
            uiState.connectedDeviceId?.let { Text("Connected to: $it") }
            uiState.heartRate?.let { Text("HR: $it bpm") }
            uiState.batteryLevel?.let { Text("Battery: $it%") }

            if (uiState.isScanning) {
                Button(onClick = { viewModel.stopScan() }) {
                    Text(
                        "STOP",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            } else {
                when (uiState.connectionState) {
                    ConnectionState.CONNECTING, ConnectionState.CONNECTED -> {
                        Button(onClick = { viewModel.disconnect() }) {
                            Text(
                                "DISCONNECT",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        }
                    }
                    ConnectionState.DISCONNECTED -> {
                        Button(onClick = { startScanWithPermissionCheck() }) {
                            Text(
                                "START",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

            OutlinedButton(onClick = { viewModel.forgetKnownDevice() }) {
                Text(
                    "FORGET",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }

            Text("Found devices:")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.discoveredDevices, key = { it.deviceId }) { device ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(device.name)
                            Text("${device.deviceId}  (RSSI ${device.rssi})")
                            Button(onClick = { viewModel.connectToDevice(device.deviceId) }) {
                                Text(
                                    "CONNECT",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
