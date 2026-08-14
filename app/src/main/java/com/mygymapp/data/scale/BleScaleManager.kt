package com.mygymapp.data.scale

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mygymapp.data.polar.UserProfileRepository
import com.mygymapp.data.repository.ScaleHistoryRepository
import com.mygymapp.data.sync.ScaleWeighInLedgerRepository
import com.mygymapp.data.sync.ScaleWeighInSyncWorker
import com.mygymapp.data.sync.SyncConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class ScaleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
}

data class ScaleDeviceInfo(
    val address: String,
    val name: String?,
)

/**
 * Facade for the VitaFit VT701 (VTrump "SenHe" scale protocol) BLE smart scale.
 *
 * Phase 1: scan, connect, subscribe, parse raw weight/impedance. No reconnect
 * watchdog or foreground service yet — those are added once the protocol is
 * confirmed against the real device. Mirrors [com.mygymapp.data.polar.PolarManager]'s
 * facade-over-StateFlow shape, but talks directly to Android's BLE stack since
 * there is no vendor SDK for this device.
 *
 * Weight and body-composition (impedance) arrive in separate notify packets
 * (commands 0x10 and 0x11) — [lastReading] merges the latest of each so
 * consumers see both once available.
 */
@Singleton
class BleScaleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knownScaleRepository: KnownScaleRepository,
    private val userProfileRepository: UserProfileRepository,
    private val scaleHistoryRepository: ScaleHistoryRepository,
    private val scaleWeighInLedgerRepository: ScaleWeighInLedgerRepository,
    private val syncConfigRepository: SyncConfigRepository,
) {
    companion object {
        private const val TAG = "BleScaleManager"
    }

    private var autoConnectAttempted = false
    private var savedThisSession = false
    private val weightSamples = mutableListOf<Double>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null

    private val _connectionState = MutableStateFlow(ScaleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ScaleConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<ScaleDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<ScaleDeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _lastReading = MutableStateFlow<ScaleReading?>(null)
    val lastReading: StateFlow<ScaleReading?> = _lastReading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _lastError.value = "Bluetooth non disponibile o disattivato"
            return
        }
        if (_connectionState.value != ScaleConnectionState.DISCONNECTED) return

        _discoveredDevices.value = emptyList()
        _lastError.value = null
        _connectionState.value = ScaleConnectionState.SCANNING
        autoConnectAttempted = false

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val knownAddress = knownScaleRepository.getKnownAddress()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val info = ScaleDeviceInfo(address = device.address, name = device.name)
                val current = _discoveredDevices.value
                if (current.none { it.address == info.address }) {
                    _discoveredDevices.value = current + info
                }
                if (!autoConnectAttempted && knownAddress != null && device.address == knownAddress) {
                    autoConnectAttempted = true
                    connectToDevice(device.address)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _lastError.value = "Scan BLE fallito (codice $errorCode)"
                _connectionState.value = ScaleConnectionState.DISCONNECTED
            }
        }
        scanCallback = callback

        // No ScanFilter: the VT701 doesn't reliably advertise the fff0
        // service UUID in its primary advertising payload (confirmed by
        // empty results with a service-UUID filter against the real device).
        // Phase-1 validation scans everything and lets the UI show names.
        scanner.startScan(emptyList(), settings, callback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        scanCallback?.let { scanner.stopScan(it) }
        scanCallback = null
        if (_connectionState.value == ScaleConnectionState.SCANNING) {
            _connectionState.value = ScaleConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        stopScan()
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: run {
            _lastError.value = "Indirizzo dispositivo non valido"
            return
        }
        _connectionState.value = ScaleConnectionState.CONNECTING
        savedThisSession = false
        weightSamples.clear()
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
    }

    /**
     * Persists one weigh-in per connection session, triggered by the impedance
     * reading (the scale sends it once, near the end of the BIA measurement).
     * Weight is the average of every weight sample seen this session, since the
     * scale's own stability flag doesn't reliably line up with when impedance
     * arrives.
     */
    private fun maybeSaveWeighIn(impedanceOhm: Int) {
        if (savedThisSession) return
        if (weightSamples.isEmpty()) return
        val weightKg = weightSamples.average()
        savedThisSession = true

        val profile = userProfileRepository.get()
        val composition = BodyCompositionCalculator.calculate(
            weightKg = weightKg,
            heightCm = profile.heightCm,
            age = profile.age,
            isMale = profile.isMale,
            impedanceOhm = impedanceOhm,
        )
        // Keep UserProfile.weightKg in sync with the scale — it's the only
        // source of body weight now (no manual entry), and PolarManager reads
        // it for calorie-burn calculations during heart-rate sessions.
        userProfileRepository.save(profile.copy(weightKg = weightKg))

        scope.launch {
            val weighIn = scaleHistoryRepository.save(
                weightKg = weightKg,
                bmi = composition.bmi,
                bodyFatPercent = composition.bodyFatPercent,
                leanMassPercent = composition.leanMassPercent,
            )
            // Sync immediately (docs/SYNC.md) — same isEnabled() scoping rule as
            // sessions/readiness: only gates the automatic enqueue, never blocks the
            // save itself, never inline on the network.
            if (syncConfigRepository.isEnabled() && syncConfigRepository.isConfigured()) {
                val file = scaleHistoryRepository.fileFor(weighIn.id)
                if (file.exists()) {
                    scaleWeighInLedgerRepository.enqueue(
                        weighIn.id,
                        scaleHistoryRepository.relPathFor(weighIn.id),
                        file,
                    )
                    ScaleWeighInSyncWorker.Scheduler.runExpedited(context)
                }
            }
        }

        // Weigh-in is complete (weight + impedance both received) — no need to
        // keep the connection open. Brief delay so the UI has a moment to show
        // the "complete" state before it flips back to disconnected.
        mainHandler.postDelayed({ disconnect() }, 1500)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            mainHandler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _connectionState.value = ScaleConnectionState.DISCONNECTED
                        g.close()
                        if (gatt === g) gatt = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            mainHandler.post {
                Log.d(TAG, "onServicesDiscovered status=$status services=${g.services.map { it.uuid }}")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _lastError.value = "Scoperta servizi GATT fallita (status $status)"
                    g.disconnect()
                    return@post
                }
                val service = g.getService(VtrumpSenheProtocol.SERVICE_UUID)
                Log.d(TAG, "fff0 service=${service?.uuid} chars=${service?.characteristics?.map { it.uuid }}")
                val notifyChar = service?.getCharacteristic(VtrumpSenheProtocol.NOTIFY_CHARACTERISTIC_UUID)
                if (notifyChar == null) {
                    _lastError.value = "Servizio/caratteristica attesi non trovati sul dispositivo"
                    g.disconnect()
                    return@post
                }
                val setNotifOk = g.setCharacteristicNotification(notifyChar, true)
                Log.d(TAG, "setCharacteristicNotification ok=$setNotifOk")
                val cccd = notifyChar.getDescriptor(VtrumpSenheProtocol.CLIENT_CHARACTERISTIC_CONFIG_UUID)
                Log.d(TAG, "cccd=${cccd?.uuid}")
                if (cccd != null) {
                    val writeOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptorCompat.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptorCompat.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                    Log.d(TAG, "writeDescriptor initiated=$writeOk")
                }
                knownScaleRepository.setKnownAddress(g.device.address)
                _connectionState.value = ScaleConnectionState.CONNECTED
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: android.bluetooth.BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite ${descriptor.uuid} status=$status")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            Log.d(TAG, "onCharacteristicChanged ${characteristic.uuid} hex=${value.joinToString("") { "%02x".format(it) }}")
            if (characteristic.uuid != VtrumpSenheProtocol.NOTIFY_CHARACTERISTIC_UUID) return
            val reading = VtrumpSenheProtocol.parse(value)
            Log.d(TAG, "parsed=$reading")
            if (reading == null) return
            mainHandler.post {
                reading.weightKg?.let { weightSamples.add(it) }

                val previous = _lastReading.value
                val merged = ScaleReading(
                    weightKg = reading.weightKg ?: previous?.weightKg,
                    weightStable = if (reading.weightKg != null) reading.weightStable else (previous?.weightStable ?: false),
                    impedanceOhm = reading.impedanceOhm ?: previous?.impedanceOhm,
                    displayUnit = reading.displayUnit ?: previous?.displayUnit,
                )
                _lastReading.value = merged

                // The scale's own stability flag isn't reliable timing-wise —
                // what matters is the impedance reading itself: as soon as a
                // real (non-sentinel) impedance arrives, the weigh-in is done.
                if (reading.impedanceOhm != null) {
                    maybeSaveWeighIn(reading.impedanceOhm)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onCharacteristicChanged(g, characteristic, value)
        }
    }
}

private object BluetoothGattDescriptorCompat {
    val ENABLE_NOTIFICATION_VALUE: ByteArray =
        android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
}
