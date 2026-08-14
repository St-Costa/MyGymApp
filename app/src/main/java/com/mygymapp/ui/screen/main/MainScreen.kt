package com.mygymapp.ui.screen.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.components.GitgraphView

@Composable
fun MainScreen(
    onNavigateToWeekView: () -> Unit,
    onNavigateToExercises: () -> Unit,
    onNavigateToRoutines: () -> Unit,
    onNavigateToHeartRate: () -> Unit,
    onNavigateToOptions: () -> Unit,
    onNavigateToSessionProgress: (sessionId: String, date: String) -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
    permissionsViewModel: PermissionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadGitgraph()
    }

    RequestAllRuntimePermissions(permissionsViewModel)

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToOptions,
            ) {
                if (uiState.isSeedingData) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Settings, contentDescription = "Opzioni")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Gitgraph
            GitgraphView(
                days = uiState.gitgraphDays,
                todayIndex = uiState.todayIndex,
                tonnageChanges = uiState.gitgraphTonnageChanges,
                lastWeekRoutineNames = uiState.lastWeekRoutineNames,
                powerliftingWeeks = uiState.powerliftingWeeks,
                onLastRowCellClick = { col ->
                    val sessionId = uiState.lastWeekSessionIds.getOrNull(col)
                    val date = uiState.lastWeekSessionDates.getOrNull(col)
                    if (sessionId != null && date != null) {
                        onNavigateToSessionProgress(sessionId, date)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.weight(1f))

            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onNavigateToWeekView,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text("Week View", fontSize = 22.sp)
                }
                Button(
                    onClick = onNavigateToExercises,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text("Exercises", fontSize = 22.sp)
                }
                Button(
                    onClick = onNavigateToRoutines,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text("Routines", fontSize = 22.sp)
                }
                Button(
                    onClick = onNavigateToHeartRate,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text("❤️⚖️", fontSize = 22.sp)
                }
            }
            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}

/**
 * Fire-and-forget: requests every runtime permission the app can need, once, whenever Home
 * appears — including on every return to Home, not just the first launch, so a permission
 * revoked later (Settings, or the user changing their mind) gets re-prompted here rather
 * than silently staying missing until the user happens to open the one screen that needs
 * it. Neither request is gated behind a button click, since there's no specific user action
 * to hang either off from this screen — BLE scanning and Health Connect steps both start
 * lazily elsewhere (HeartRateScreen's own scan buttons, PolarManager's readiness flow)
 * whenever their permission is actually granted, whenever that ends up being.
 */
@Composable
private fun RequestAllRuntimePermissions(viewModel: PermissionsViewModel) {
    val context = LocalContext.current

    fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasBlePermissions(): Boolean = blePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op: HeartRateScreen checks+re-requests on its own before starting a scan */ }

    val stepPermissionLauncher = rememberLauncherForActivityResult(
        viewModel.stepPermissionContract
    ) { /* no-op: PolarManager/OptionsViewModel check the permission fresh whenever they next read */ }

    LaunchedEffect(Unit) {
        if (!hasBlePermissions()) {
            blePermissionLauncher.launch(blePermissions())
        }
        if (viewModel.isHealthConnectAvailable() && !viewModel.hasStepsPermission()) {
            stepPermissionLauncher.launch(setOf(viewModel.stepsReadPermission))
        }
    }
}
