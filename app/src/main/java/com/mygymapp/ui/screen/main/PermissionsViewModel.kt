package com.mygymapp.ui.screen.main

import com.mygymapp.data.steps.HealthConnectStepsReader
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Backs [MainScreen]'s centralized runtime-permission requests — kept separate from
 * [MainViewModel] (already carrying gitgraph + debug-seed logic unrelated to permissions)
 * rather than folded in, so this stays a clean single-purpose surface as more permissions
 * get centralized here over time.
 *
 * All app runtime permissions are requested from here, once, when the Home screen opens —
 * not scattered per-screen (BLE used to be requested from HeartRateScreen only, and Health
 * Connect steps briefly the same) — so a permission that's missing or gets revoked later is
 * re-prompted from the one screen the user always passes through, not only if/when they
 * happen to navigate to the specific screen that needs it.
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val healthConnectStepsReader: HealthConnectStepsReader,
) : ViewModel() {
    val stepPermissionContract = healthConnectStepsReader.permissionRequestContract
    val stepsReadPermission: String = HealthConnectStepsReader.READ_STEPS_PERMISSION
    fun isHealthConnectAvailable(): Boolean = healthConnectStepsReader.isAvailable()
    suspend fun hasStepsPermission(): Boolean = healthConnectStepsReader.hasReadPermission()
}
