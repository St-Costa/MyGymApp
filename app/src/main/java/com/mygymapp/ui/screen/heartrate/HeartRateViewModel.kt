package com.mygymapp.ui.screen.heartrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.polar.ConnectionState
import com.mygymapp.data.polar.PolarManager
import com.mygymapp.data.polar.ReadinessResult
import com.mygymapp.data.polar.UserProfile
import com.mygymapp.data.polar.UserProfileRepository
import com.mygymapp.data.repository.CardioTrendLoader
import com.mygymapp.data.repository.CardioTrendReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HeartRateUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val heartRate: Int? = null,
    val batteryLevel: Int? = null,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val isScanning: Boolean = false,
    val connectedDeviceId: String? = null,
    val sessionCalories: Double = 0.0,
    val sessionTrimp: Double = 0.0,
    val readiness: ReadinessResult = ReadinessResult(),
    val vo2max: Double? = null,
    val profile: UserProfile = UserProfile(),
    val cardioTrend: CardioTrendReport = CardioTrendReport(),
)

data class DiscoveredDevice(
    val deviceId: String,
    val name: String,
    val rssi: Int,
)

@HiltViewModel
class HeartRateViewModel @Inject constructor(
    private val polarManager: PolarManager,
    private val profileRepo: UserProfileRepository,
    private val cardioTrendLoader: CardioTrendLoader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HeartRateUiState())
    val uiState: StateFlow<HeartRateUiState> = _uiState

    init {
        _uiState.value = _uiState.value.copy(profile = profileRepo.get())

        viewModelScope.launch {
            val report = cardioTrendLoader.load()
            _uiState.value = _uiState.value.copy(cardioTrend = report)
        }

        // "Static-ish" flows: change rarely (scan cycle, pairing, readiness result).
        // Kept apart from the per-second heart-rate stream so a new HR sample
        // doesn't rebuild the discoveredDevices list on every tick.
        viewModelScope.launch {
            combine(
                polarManager.connectionState,
                polarManager.batteryLevel,
                polarManager.discoveredDevices,
                polarManager.isScanning,
                polarManager.readinessResult,
                polarManager.vo2max,
            ) { conn, battery, devices, scanning, readiness, vo2 ->
                StaticSlice(conn, battery, devices, scanning, readiness, vo2)
            }.distinctUntilChanged().collect { slice ->
                _uiState.update {
                    it.copy(
                        connectionState = slice.conn,
                        batteryLevel = slice.battery,
                        discoveredDevices = slice.devices.map { d ->
                            DiscoveredDevice(deviceId = d.deviceId, name = d.name, rssi = d.rssi)
                        },
                        isScanning = slice.scanning,
                        connectedDeviceId = polarManager.connectedDeviceId,
                        readiness = slice.readiness,
                        vo2max = slice.vo2,
                    )
                }
            }
        }

        // Live per-sample flows: HR (1 Hz), calories, TRIMP.
        viewModelScope.launch {
            combine(
                polarManager.heartRate,
                polarManager.sessionCalories,
                polarManager.sessionTrimp,
            ) { hr, calories, trimp ->
                Triple(hr, calories, trimp)
            }.collect { (hr, calories, trimp) ->
                _uiState.update {
                    it.copy(heartRate = hr, sessionCalories = calories, sessionTrimp = trimp)
                }
            }
        }
    }

    private data class StaticSlice(
        val conn: ConnectionState,
        val battery: Int?,
        val devices: List<com.polar.sdk.api.model.PolarDeviceInfo>,
        val scanning: Boolean,
        val readiness: ReadinessResult,
        val vo2: Double?,
    )

    fun startScan() = polarManager.startScan()
    fun stopScan() = polarManager.stopScan()
    fun connectToDevice(deviceId: String) = polarManager.connectToDevice(deviceId)
    fun disconnect() = polarManager.disconnect()

    fun updateAge(age: Int) {
        val profile = _uiState.value.profile.copy(age = age)
        _uiState.value = _uiState.value.copy(profile = profile)
        profileRepo.save(profile)
        polarManager.updateUserProfile(profile)
    }

    fun updateWeight(weight: Double) {
        val profile = _uiState.value.profile.copy(weightKg = weight)
        _uiState.value = _uiState.value.copy(profile = profile)
        profileRepo.save(profile)
        polarManager.updateUserProfile(profile)
    }

    fun updateGender(isMale: Boolean) {
        val profile = _uiState.value.profile.copy(isMale = isMale)
        _uiState.value = _uiState.value.copy(profile = profile)
        profileRepo.save(profile)
        polarManager.updateUserProfile(profile)
    }
}
