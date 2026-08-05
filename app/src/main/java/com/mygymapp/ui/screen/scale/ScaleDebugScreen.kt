package com.mygymapp.ui.screen.scale

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.scale.ScaleConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaleDebugScreen(
    onBack: () -> Unit,
    viewModel: ScaleDebugViewModel = hiltViewModel(),
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
                title = { Text("Scale BLE Debug") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Stato: ${uiState.connectionState}")

            uiState.lastError?.let { error ->
                Text("Errore: $error")
            }

            when (uiState.connectionState) {
                ScaleConnectionState.DISCONNECTED -> {
                    Button(onClick = { startScanWithPermissionCheck() }) {
                        Text("Avvia scan")
                    }
                }
                ScaleConnectionState.SCANNING -> {
                    Button(onClick = { viewModel.stopScan() }) {
                        Text("Ferma scan")
                    }
                }
                ScaleConnectionState.CONNECTING, ScaleConnectionState.CONNECTED -> {
                    Button(onClick = { viewModel.disconnect() }) {
                        Text("Disconnetti")
                    }
                }
            }

            uiState.lastReading?.let { reading ->
                Card(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Peso: ${reading.weightKg?.let { "$it kg" } ?: "n/d"} (stabile: ${reading.weightStable})")
                        Text("Impedenza: ${reading.impedanceOhm?.let { "$it Ω" } ?: "n/d"}")
                        Text("Unità display: ${reading.displayUnit ?: "n/d"}")
                    }
                }
            }

            OutlinedButton(onClick = { viewModel.forgetKnownScale() }) {
                Text("Dimentica bilancia associata")
            }

            OutlinedButton(onClick = { viewModel.seedDebugWeighIns() }) {
                Text("Genera pesate di debug (65 giorni)")
            }

            Text("Dispositivi trovati:")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.discoveredDevices) { device ->
                    Card(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp),
                        ) {
                            Text(device.name ?: "(senza nome)")
                            Text(device.address)
                            Button(onClick = { viewModel.connectToDevice(device.address) }) {
                                Text("Connetti")
                            }
                        }
                    }
                }
            }
        }
    }
}
