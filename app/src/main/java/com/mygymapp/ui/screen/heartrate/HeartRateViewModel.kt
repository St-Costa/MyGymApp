package com.mygymapp.ui.screen.heartrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.polar.ConnectionState
import com.mygymapp.data.polar.PolarManager
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
)

data class DiscoveredDevice(
    val deviceId: String,
    val name: String,
    val rssi: Int,
)

@HiltViewModel
class HeartRateViewModel @Inject constructor(
    private val polarManager: PolarManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HeartRateUiState())
    val uiState: StateFlow<HeartRateUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(
                polarManager.connectionState,
                polarManager.heartRate,
                polarManager.batteryLevel,
                polarManager.discoveredDevices,
                polarManager.isScanning,
            ) { conn, hr, battery, devices, scanning ->
                HeartRateUiState(
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
                )
            }.collect { _uiState.value = it }
        }
    }

    fun startScan() = polarManager.startScan()
    fun stopScan() = polarManager.stopScan()
    fun connectToDevice(deviceId: String) = polarManager.connectToDevice(deviceId)
    fun disconnect() = polarManager.disconnect()
}
