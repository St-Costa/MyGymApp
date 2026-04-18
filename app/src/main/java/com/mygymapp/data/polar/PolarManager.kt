package com.mygymapp.data.polar

import android.content.Context
import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.mygymapp.ui.service.PolarStreamingService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.disposables.Disposable
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
enum class RecoveryState { RECOVERING, ALMOST_READY, READY }

@Singleton
class PolarManager @Inject constructor(
    @ApplicationContext private val context: Context,
    profileRepo: UserProfileRepository,
) {
    companion object {
        private const val TAG = "PolarManager"
        private const val RR_BUFFER_SIZE = 30
        private const val RMSSD_READY_THRESHOLD = 20.0
        private const val HR_RECOVERY_THRESHOLD = 0.70f
        private const val HR_WINDOW_SIZE = 8 // ~8 seconds of HR samples
        private const val PEAK_MIN_RISE_BPM = 15 // HR must rise at least this much above resting to count as effort
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

    private val _recoveryState = MutableStateFlow(RecoveryState.READY)
    val recoveryState: StateFlow<RecoveryState> = _recoveryState

    private val _rmssd = MutableStateFlow<Double?>(null)
    val rmssd: StateFlow<Double?> = _rmssd

    private val _sessionCalories = MutableStateFlow(0.0)
    val sessionCalories: StateFlow<Double> = _sessionCalories

    private val _sessionTrimp = MutableStateFlow(0.0)
    val sessionTrimp: StateFlow<Double> = _sessionTrimp

    var connectedDeviceId: String? = null
        private set

    // User profile for calorie/TRIMP calculations
    private var userProfile = profileRepo.get()

    // Calorie/TRIMP tracking
    private var lastHrTimestamp = 0L

    // Recovery tracking
    private var peakHrAfterSet: Int = 0
    private var restingHr: Int = 70 // default, refines over time
    private var isRecovering = false
    private val recentRR = ArrayDeque<Int>(RR_BUFFER_SIZE)
    private var lowestObservedHr: Int = 200

    // Automatic peak detection: rolling HR window to detect rising→falling transition
    private val hrWindow = ArrayDeque<Int>(HR_WINDOW_SIZE)
    private var hrWasRising = false

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
                _sessionCalories.value = 0.0
                _sessionTrimp.value = 0.0
                lastHrTimestamp = System.currentTimeMillis()
                PolarStreamingService.start(context, polarDeviceInfo.name)
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
                PolarStreamingService.stop(context)
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
        PolarStreamingService.stop(context)
        api.disconnectFromDevice(deviceId)
    }

    fun shutdown() {
        scanDisposable?.dispose()
        hrDisposable?.dispose()
        PolarStreamingService.stop(context)
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
                        PolarStreamingService.updateHr(context, sample.hr)

                        // Track lowest observed HR as resting estimate
                        if (sample.hr in 30..199 && sample.hr < lowestObservedHr) {
                            lowestObservedHr = sample.hr
                            restingHr = lowestObservedHr
                        }

                        // Process RR intervals for recovery
                        for (rr in sample.rrsMs) {
                            if (rr in 300..2000) {
                                if (recentRR.size >= RR_BUFFER_SIZE) recentRR.removeFirst()
                                recentRR.addLast(rr)
                            }
                        }

                        // Automatic peak detection → triggers recovery
                        detectPeakAndTriggerRecovery(sample.hr)

                        updateRecoveryState(sample.hr)

                        // Accumulate calories and TRIMP
                        accumulateCaloriesAndTrimp(sample.hr)
                    }
                },
                { error ->
                    Log.e(TAG, "HR streaming error: $error")
                    _heartRate.value = null
                }
            )
    }

    /**
     * Automatic peak detection: tracks a rolling HR window.
     * When HR was rising and starts falling, and the peak is significantly
     * above resting HR, we know the user just finished a set → start recovery.
     */
    private fun detectPeakAndTriggerRecovery(hr: Int) {
        if (hrWindow.size >= HR_WINDOW_SIZE) hrWindow.removeFirst()
        hrWindow.addLast(hr)
        if (hrWindow.size < 4) return // need enough data

        // Compare first half average vs second half average of the window
        val half = hrWindow.size / 2
        val firstHalf = hrWindow.toList().take(half).average()
        val secondHalf = hrWindow.toList().takeLast(half).average()
        val isRising = secondHalf > firstHalf + 1.0 // rising if second half > first half by >1 BPM

        // Peak detected: was rising, now falling, and HR is well above resting
        if (hrWasRising && !isRising && !isRecovering) {
            val peakHr = hrWindow.max()
            if (peakHr - restingHr >= PEAK_MIN_RISE_BPM) {
                peakHrAfterSet = peakHr
                isRecovering = true
                recentRR.clear()
                _recoveryState.value = RecoveryState.RECOVERING
                _rmssd.value = null
                Log.d(TAG, "Peak detected: $peakHr BPM, starting recovery (resting=$restingHr)")
            }
        }
        hrWasRising = isRising
    }

    private fun updateRecoveryState(hr: Int) {
        if (!isRecovering) {
            _recoveryState.value = RecoveryState.READY
            return
        }

        // HR-based recovery: has HR dropped enough toward resting?
        val hrDelta = peakHrAfterSet - restingHr
        val hrReady = if (hrDelta > 0) {
            val recovery = (peakHrAfterSet - hr).toFloat() / hrDelta
            recovery >= HR_RECOVERY_THRESHOLD
        } else true

        // RMSSD-based recovery: parasympathetic reactivation
        val currentRmssd = calculateRMSSD(recentRR.toList())
        _rmssd.value = currentRmssd
        val rmssdReady = currentRmssd > RMSSD_READY_THRESHOLD && recentRR.size >= 15

        val state = when {
            hrReady && rmssdReady -> RecoveryState.READY
            hrReady || rmssdReady -> RecoveryState.ALMOST_READY
            else -> RecoveryState.RECOVERING
        }
        _recoveryState.value = state

        if (state == RecoveryState.READY) {
            isRecovering = false
        }
    }

    private fun accumulateCaloriesAndTrimp(hr: Int) {
        val now = System.currentTimeMillis()
        val elapsedMin = (now - lastHrTimestamp) / 60000.0
        lastHrTimestamp = now

        // Clamp to reasonable interval (skip if >10s gap, e.g. reconnection)
        if (elapsedMin <= 0 || elapsedMin > 0.2) return

        val p = userProfile

        // Keytel et al. (2005) calorie formula (kcal/min)
        val kcalPerMin = if (p.isMale) {
            (-55.0969 + 0.6309 * hr + 0.1988 * p.weightKg + 0.2017 * p.age) / 4.184
        } else {
            (-20.4022 + 0.4472 * hr - 0.1263 * p.weightKg + 0.074 * p.age) / 4.184
        }
        if (kcalPerMin > 0) {
            _sessionCalories.value += kcalPerMin * elapsedMin
        }

        // Banister TRIMP: duration × HRR fraction × exponential weighting
        val hrr = (hr - restingHr).toDouble() / (p.hrMax - restingHr)
        val clampedHrr = hrr.coerceIn(0.0, 1.0)
        val genderExp = if (p.isMale) 1.92 else 1.67
        val trimpContribution = elapsedMin * clampedHrr * 0.64 * exp(genderExp * clampedHrr)
        _sessionTrimp.value += trimpContribution
    }

    fun updateUserProfile(profile: UserProfile) {
        userProfile = profile
    }

    private fun calculateRMSSD(rrIntervals: List<Int>): Double {
        if (rrIntervals.size < 2) return 0.0
        val diffs = rrIntervals.zipWithNext { a, b -> (b - a).toDouble().pow(2) }
        return sqrt(diffs.average())
    }
}
