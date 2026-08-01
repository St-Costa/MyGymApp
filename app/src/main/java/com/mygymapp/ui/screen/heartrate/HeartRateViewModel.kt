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
import com.mygymapp.data.repository.ScaleTrendLoader
import com.mygymapp.data.repository.ScaleTrendReport
import com.mygymapp.data.scale.BleScaleManager
import com.mygymapp.data.scale.ScaleConnectionState
import com.mygymapp.data.scale.ScaleReading
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val scaleConnectionState: ScaleConnectionState = ScaleConnectionState.DISCONNECTED,
    val scaleReading: ScaleReading? = null,
    val scaleError: String? = null,
    val scaleTrend: ScaleTrendReport = ScaleTrendReport(),
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
    private val scaleManager: BleScaleManager,
    private val scaleTrendLoader: ScaleTrendLoader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HeartRateUiState())
    val uiState: StateFlow<HeartRateUiState> = _uiState

    init {
        _uiState.value = _uiState.value.copy(profile = profileRepo.get())

        viewModelScope.launch {
            val report = cardioTrendLoader.load()
            _uiState.value = _uiState.value.copy(cardioTrend = report)
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(scaleTrend = scaleTrendLoader.load())
        }

        viewModelScope.launch {
            combine(
                polarManager.connectionState,
                polarManager.heartRate,
                polarManager.batteryLevel,
                polarManager.discoveredDevices,
                polarManager.isScanning,
                polarManager.sessionCalories,
                polarManager.sessionTrimp,
                polarManager.readinessResult,
                polarManager.vo2max,
            ) { values ->
                val conn = values[0] as ConnectionState
                val hr = values[1] as Int?
                val battery = values[2] as Int?
                @Suppress("UNCHECKED_CAST")
                val devices = values[3] as List<com.polar.sdk.api.model.PolarDeviceInfo>
                val scanning = values[4] as Boolean
                val calories = values[5] as Double
                val trimp = values[6] as Double
                val readiness = values[7] as ReadinessResult
                val vo2 = values[8] as Double?

                _uiState.value.copy(
                    connectionState = conn,
                    heartRate = hr,
                    batteryLevel = battery,
                    discoveredDevices = devices.map { d ->
                        DiscoveredDevice(
                            deviceId = d.deviceId,
                            name = d.name,
                            rssi = d.rssi,
                        )
                    },
                    isScanning = scanning,
                    connectedDeviceId = polarManager.connectedDeviceId,
                    sessionCalories = calories,
                    sessionTrimp = trimp,
                    readiness = readiness,
                    vo2max = vo2,
                )
            }.collect { _uiState.value = it }
        }

        viewModelScope.launch {
            var previousState = scaleManager.connectionState.value
            combine(
                scaleManager.connectionState,
                scaleManager.lastReading,
                scaleManager.lastError,
            ) { connectionState, reading, error ->
                Triple(connectionState, reading, error)
            }.collect { (connectionState, reading, error) ->
                _uiState.value = _uiState.value.copy(
                    scaleConnectionState = connectionState,
                    scaleReading = reading,
                    scaleError = error,
                )
                // Reload the trend once the scale session ends — a weigh-in was
                // likely just persisted (BleScaleManager saves on stable weight).
                if (previousState == ScaleConnectionState.CONNECTED &&
                    connectionState == ScaleConnectionState.DISCONNECTED
                ) {
                    _uiState.value = _uiState.value.copy(scaleTrend = scaleTrendLoader.load())
                }
                previousState = connectionState
            }
        }
    }

    fun startScan() = polarManager.startScan()
    fun stopScan() = polarManager.stopScan()
    fun connectToDevice(deviceId: String) = polarManager.connectToDevice(deviceId)
    fun disconnect() = polarManager.disconnect()

    fun startScaleScan() = scaleManager.startScan()
    fun stopScaleScan() = scaleManager.stopScan()
    fun disconnectScale() = scaleManager.disconnect()

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

    fun updateHeight(heightCm: Int) {
        val profile = _uiState.value.profile.copy(heightCm = heightCm)
        _uiState.value = _uiState.value.copy(profile = profile)
        profileRepo.save(profile)
    }
}
