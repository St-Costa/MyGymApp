package com.mygymapp.ui.screen.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.ui.components.GitgraphView

@Composable
fun MainScreen(
    onNavigateToExercises: () -> Unit,
    onNavigateToRoutines: () -> Unit,
    onNavigateToHeartRate: () -> Unit,
    onNavigateToOptions: () -> Unit,
    onNavigateToSessionProgress: (sessionId: String, date: String) -> Unit,
    onNavigateToRoutine: (routineId: String) -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
    permissionsViewModel: PermissionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadGitgraph()
    }

    RequestAllRuntimePermissions(permissionsViewModel)

    Scaffold { padding ->
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
                tonnageChanges = uiState.gitgraphTonnageChanges,
                cardioMinutes = uiState.gitgraphCardioMinutes,
                routineNames = uiState.routineNames,
                sessionIds = uiState.sessionIds,
                sessionDates = uiState.sessionDates,
                powerliftingWeeks = uiState.powerliftingWeeks,
                onCellClick = { sessionId, date -> onNavigateToSessionProgress(sessionId, date) },
                scheduleCells = uiState.scheduleCells,
                todayDowIndex = uiState.todayDowIndex,
                todayStatus = uiState.todayStatus,
                todayTonnageChange = uiState.todayTonnageChange,
                todayCardioMinutes = uiState.todayCardioMinutes,
                todayRoutineName = uiState.todayRoutineName,
                todaySessionId = uiState.todaySessionId,
                todaySessionDate = uiState.todaySessionDate,
                onScheduleCellClick = { routineId -> onNavigateToRoutine(routineId) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.weight(1f))

            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MainMenuTile(
                        label = "Exercises",
                        icon = Icons.Default.FitnessCenter,
                        onClick = onNavigateToExercises,
                        modifier = Modifier.weight(1f),
                    )
                    MainMenuTile(
                        label = "Routines",
                        icon = Icons.Default.ListAlt,
                        onClick = onNavigateToRoutines,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MainMenuTile(
                        label = "Heart & Scale",
                        icon = Icons.Default.Bluetooth,
                        onClick = onNavigateToHeartRate,
                        modifier = Modifier.weight(1f),
                    )
                    MainMenuTile(
                        label = "Opzioni",
                        icon = Icons.Default.Settings,
                        onClick = onNavigateToOptions,
                        isLoading = uiState.isSeedingData,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MainMenuTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
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
