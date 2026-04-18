package com.mygymapp.data.polar

import android.content.Context
import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.androidcommunications.api.ble.model.DisInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

@Singleton
class PolarManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "PolarManager"
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel

    private val _discoveredDevices = MutableStateFlow<List<PolarDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<PolarDeviceInfo>> = _discoveredDevices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    var connectedDeviceId: String? = null
        private set

    private var scanDisposable: Disposable? = null
    private var hrDisposable: Disposable? = null

    private val api: PolarBleApi = PolarBleApiDefaultImpl.defaultImplementation(
        context,
        setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
        )
    ).also { api ->
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BLE power: $powered")
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Connected: ${polarDeviceInfo.deviceId}")
                connectedDeviceId = polarDeviceInfo.deviceId
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Connecting: ${polarDeviceInfo.deviceId}")
                _connectionState.value = ConnectionState.CONNECTING
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Disconnected: ${polarDeviceInfo.deviceId}")
                connectedDeviceId = null
                _connectionState.value = ConnectionState.DISCONNECTED
                _heartRate.value = null
                _batteryLevel.value = null
                hrDisposable?.dispose()
                hrDisposable = null
            }

            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature,
            ) {
                Log.d(TAG, "Feature ready: $feature for $identifier")
                when (feature) {
                    PolarBleApi.PolarBleSdkFeature.FEATURE_HR -> {
                        startHrStreaming(identifier)
                    }
                    else -> {}
                }
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                Log.d(TAG, "DIS info: $uuid = $value")
            }

            override fun disInformationReceived(identifier: String, disInfo: DisInfo) {
                // Extended DIS info callback
            }

            override fun htsNotificationReceived(
                identifier: String,
                data: com.polar.sdk.api.model.PolarHealthThermometerData,
            ) {
                // Health thermometer notification - not used
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                Log.d(TAG, "Battery: $level%")
                _batteryLevel.value = level
            }
        })
    }

    fun startScan() {
        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        scanDisposable?.dispose()
        scanDisposable = api.searchForDevice()
            .subscribe(
                { deviceInfo ->
                    val current = _discoveredDevices.value
                    if (current.none { it.deviceId == deviceInfo.deviceId }) {
                        _discoveredDevices.value = current + deviceInfo
                    }
                },
                { error ->
                    Log.e(TAG, "Scan error: $error")
                    _isScanning.value = false
                },
                {
                    _isScanning.value = false
                }
            )
    }

    fun stopScan() {
        scanDisposable?.dispose()
        scanDisposable = null
        _isScanning.value = false
    }

    fun connectToDevice(deviceId: String) {
        stopScan()
        api.connectToDevice(deviceId)
    }

    fun disconnect() {
        val deviceId = connectedDeviceId ?: return
        hrDisposable?.dispose()
        hrDisposable = null
        api.disconnectFromDevice(deviceId)
    }

    fun shutdown() {
        scanDisposable?.dispose()
        hrDisposable?.dispose()
        api.shutDown()
    }

    private fun startHrStreaming(deviceId: String) {
        hrDisposable?.dispose()
        hrDisposable = api.startHrStreaming(deviceId)
            .subscribe(
                { hrData ->
                    val sample = hrData.samples.lastOrNull()
                    if (sample != null) {
                        _heartRate.value = sample.hr
                    }
                },
                { error ->
                    Log.e(TAG, "HR streaming error: $error")
                    _heartRate.value = null
                }
            )
    }
}
