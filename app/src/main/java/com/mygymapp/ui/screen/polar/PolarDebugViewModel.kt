package com.mygymapp.ui.screen.polar

import androidx.lifecycle.ViewModel
import com.mygymapp.data.polar.ConnectionState
import com.mygymapp.data.polar.KnownPolarDeviceRepository
import com.mygymapp.data.polar.PolarLinkStatus
import com.mygymapp.data.polar.PolarManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

data class PolarDebugDevice(val deviceId: String, val name: String, val rssi: Int)

data class PolarDebugUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val linkStatus: PolarLinkStatus = PolarLinkStatus.DISCONNECTED,
    val isScanning: Boolean = false,
    val discoveredDevices: List<PolarDebugDevice> = emptyList(),
    val connectedDeviceId: String? = null,
    val heartRate: Int? = null,
    val batteryLevel: Int? = null,
    val knownDeviceId: String? = null,
)

/**
 * Debug screen — the Polar mirror of [com.mygymapp.ui.screen.scale.ScaleDebugScreen].
 * Scan, connect, and watch the connection come up. The first successful connect makes
 * [PolarManager] persist the device ID via [KnownPolarDeviceRepository], so from then on
 * every scan auto-connects to it. Exists because an accidental reinstall/data-wipe clears
 * that pref, leaving the strap unable to auto-connect until it is paired once more.
 */
@HiltViewModel
class PolarDebugViewModel @Inject constructor(
    private val polarManager: PolarManager,
    private val knownPolarDeviceRepository: KnownPolarDeviceRepository,
) : ViewModel() {

    val uiState: StateFlow<PolarDebugUiState> = combine(
        polarManager.connectionState,
        polarManager.linkStatus,
        polarManager.isScanning,
        polarManager.discoveredDevices,
        combine(polarManager.heartRate, polarManager.batteryLevel) { hr, battery -> hr to battery },
    ) { connectionState, link, scanning, devices, hrBattery ->
        PolarDebugUiState(
            connectionState = connectionState,
            linkStatus = link,
            isScanning = scanning,
            discoveredDevices = devices.map {
                PolarDebugDevice(
                    deviceId = it.deviceId,
                    name = it.name.ifBlank { "(senza nome)" },
                    rssi = it.rssi,
                )
            },
            connectedDeviceId = polarManager.connectedDeviceId,
            heartRate = hrBattery.first,
            batteryLevel = hrBattery.second,
            knownDeviceId = knownPolarDeviceRepository.getKnownDeviceId(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PolarDebugUiState())

    fun startScan() = polarManager.startScan()
    fun stopScan() = polarManager.stopScan()
    fun connectToDevice(deviceId: String) = polarManager.connectToDevice(deviceId)
    fun disconnect() = polarManager.disconnect()
    fun forgetKnownDevice() = knownPolarDeviceRepository.forget()
}
