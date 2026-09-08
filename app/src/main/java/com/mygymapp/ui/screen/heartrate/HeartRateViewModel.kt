package com.mygymapp.ui.screen.heartrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.polar.BatteryLifeState
import com.mygymapp.data.polar.ConnectionState
import com.mygymapp.data.polar.PolarLinkStatus
import com.mygymapp.data.polar.PolarManager
import com.mygymapp.data.polar.ReadinessResult
import com.mygymapp.data.polar.UserProfile
import com.mygymapp.data.polar.UserProfileRepository
import com.mygymapp.data.repository.CardioMetricsTrendLoader
import com.mygymapp.data.repository.CardioMetricsTrendReport
import com.mygymapp.data.repository.ScaleTrendLoader
import com.mygymapp.data.repository.ScaleTrendReport
import com.mygymapp.data.scale.BleScaleManager
import com.mygymapp.data.scale.ScaleConnectionState
import com.mygymapp.data.scale.ScaleReading
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HeartRateUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val linkStatus: PolarLinkStatus = PolarLinkStatus.DISCONNECTED,
    val heartRate: Int? = null,
    val batteryLevel: Int? = null,
    val batteryLow: Boolean = false,
    val batteryLife: BatteryLifeState? = null,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val isScanning: Boolean = false,
    val connectedDeviceId: String? = null,
    val sessionCalories: Double = 0.0,
    val sessionTrimp: Double = 0.0,
    val readiness: ReadinessResult = ReadinessResult(),
    val vo2max: Double? = null,
    val profile: UserProfile = UserProfile(),
    val scaleConnectionState: ScaleConnectionState = ScaleConnectionState.DISCONNECTED,
    val scaleReading: ScaleReading? = null,
    val scaleError: String? = null,
    val scaleTrend: ScaleTrendReport = ScaleTrendReport(),
    val cardioMetricsTrend: CardioMetricsTrendReport = CardioMetricsTrendReport(),
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
    private val scaleManager: BleScaleManager,
    private val scaleTrendLoader: ScaleTrendLoader,
    private val cardioMetricsTrendLoader: CardioMetricsTrendLoader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HeartRateUiState())
    val uiState: StateFlow<HeartRateUiState> = _uiState
    val readinessStarted: SharedFlow<Unit> = polarManager.readinessStarted

    fun playReadinessSignal() {
        polarManager.signalReadiness()
    }

    init {
        // Every write below goes through update {} rather than
        // `value = value.copy(...)`: several coroutines mutate this state
        // concurrently, and a read-modify-write built from a stale snapshot
        // silently drops fields another coroutine set in between. That's what
        // made the async-loaded trends disappear — the Polar flow emits
        // constantly and kept overwriting them.
        _uiState.update { it.copy(profile = profileRepo.get()) }

        viewModelScope.launch {
            val loaded = scaleTrendLoader.load()
            _uiState.update { it.copy(scaleTrend = loaded) }
        }

        viewModelScope.launch {
            val loaded = cardioMetricsTrendLoader.load()
            _uiState.update { it.copy(cardioMetricsTrend = loaded) }
        }

        // Collected on its own rather than folded into the combine below, which is
        // already at the 9-flow vararg overload's practical limit.
        viewModelScope.launch {
            polarManager.batteryLow.collect { low ->
                _uiState.update { it.copy(batteryLow = low) }
            }
        }

        viewModelScope.launch {
            polarManager.batteryLife.collect { life ->
                _uiState.update { it.copy(batteryLife = life) }
            }
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
                polarManager.linkStatus,
            ) { values -> values }.collect { values ->
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
                val link = values[9] as PolarLinkStatus

                _uiState.update { current ->
                    current.copy(
                        connectionState = conn,
                        linkStatus = link,
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
                }
            }
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
                _uiState.update {
                    it.copy(
                        scaleConnectionState = connectionState,
                        scaleReading = reading,
                        scaleError = error,
                    )
                }
                // Reload the trend once the scale session ends — a weigh-in was
                // likely just persisted (BleScaleManager saves on stable weight
                // and also updates UserProfile.weightKg, the only source of
                // body weight now that manual entry is gone). No need to push this
                // into PolarManager separately — it now reads UserProfileRepository
                // fresh on every access instead of keeping its own cached copy.
                if (previousState == ScaleConnectionState.CONNECTED &&
                    connectionState == ScaleConnectionState.DISCONNECTED
                ) {
                    val reloaded = scaleTrendLoader.load()
                    val refreshedProfile = profileRepo.get()
                    _uiState.update { it.copy(scaleTrend = reloaded, profile = refreshedProfile) }
                }
                previousState = connectionState
            }
        }
    }

    fun startScan() = polarManager.startScan()
    fun stopScan() = polarManager.stopScan()
    fun connectToDevice(deviceId: String) = polarManager.connectToDevice(deviceId)
    fun disconnect() = polarManager.disconnect()

    /** Records the user's 1..5 sleep-quality rating on today's readiness measurement. */
    fun setSleepQuality(value: Int) = polarManager.setSleepQuality(value)

    fun startScaleScan() = scaleManager.startScan()
    fun stopScaleScan() = scaleManager.stopScan()
    fun disconnectScale() = scaleManager.disconnect()
}
