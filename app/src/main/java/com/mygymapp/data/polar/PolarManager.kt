package com.mygymapp.data.polar

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.mygymapp.data.util.AppLogger
import com.mygymapp.ui.service.PolarStreamingService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.disposables.Disposable
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import com.mygymapp.data.sync.ReadinessSyncWorker
import com.mygymapp.data.sync.SyncConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
enum class RecoveryState { RECOVERING, ALMOST_READY, READY }

enum class Readiness {
    MEASURING,          // 60s measurement in progress
    DELOAD_RECOMMENDED, // LnRMSSD very low
    LIGHT_DAY,          // LnRMSSD moderately low
    NORMAL,             // within baseline
    GOOD,               // above baseline
    PEAK,               // unusually high
    NO_BASELINE,        // fewer than 7 days of data
}

data class ReadinessResult(
    val readiness: Readiness = Readiness.MEASURING,
    val lnRmssd: Double = 0.0,
    val restingHr: Int = 0,
    val secondsRemaining: Int = 60,
    val recommendation: String = "",
)

@Singleton
class PolarManager @Inject constructor(
    @ApplicationContext private val context: Context,
    profileRepo: UserProfileRepository,
    private val ecgRecorder: EcgRecorder,
    private val ecgAnalyzer: EcgAnalyzer,
    private val appLogger: AppLogger,
    private val readinessRepository: ReadinessRepository,
    private val readinessLedgerRepository: com.mygymapp.data.sync.ReadinessLedgerRepository,
    private val syncConfigRepository: SyncConfigRepository,
    private val knownPolarDeviceRepository: KnownPolarDeviceRepository,
) {
    // Fire-and-forget scope for persisting + syncing a readiness measurement the moment
    // it's computed. PolarManager is a singleton (app-lifetime), so this never needs
    // explicit cancellation — unlike the per-screen `clearScope` pattern in edit
    // ViewModels (see CONVENTIONS.md), there is no "cleared" moment to race against.
    private val readinessScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    companion object {
        private const val TAG = "PolarManager"
        private const val RR_BUFFER_SIZE = 30
        private const val RMSSD_READY_THRESHOLD = 20.0
        private const val HR_RECOVERY_THRESHOLD = 0.70f
        private const val HR_WINDOW_SIZE = 8 // ~8 seconds of HR samples
        private const val PEAK_MIN_RISE_BPM = 15 // HR must rise at least this much above resting to count as effort (recovery semaphore)
        // HRR-specific thresholds — stricter, to ensure only real "set" peaks count
        private const val HRR_PEAK_MIN_RISE_BPM = 25
        private const val HRR_PEAK_MIN_HRMAX_FRACTION = 0.6f
        private const val HRR_QUEUE_DEBOUNCE_MS = 90_000L
        // Safety cap on the session HR series: 8 hours at 1 Hz. Real workouts are well under this;
        // the cap only bounds memory if a lifecycle bug forgets to call stopHrSeriesCapture().
        private const val HR_SERIES_MAX_ENTRIES = 28800
        // Warn about the CR2025 at 70%, not at the usual 20%. The H10 derives its
        // percentage from cell voltage, and a lithium coin cell holds ~3V until it is
        // nearly spent — what actually kills it is rising internal resistance, which
        // the percentage never reflects. In practice the strap goes silent (can't
        // complete a BLE advertisement) while still reporting 50-60%, so anything
        // below this threshold means "replace it soon", not "still half full".
        private const val BATTERY_WARNING_THRESHOLD = 70
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel

    /** True when the strap's battery is at or below [BATTERY_WARNING_THRESHOLD]. */
    private val _batteryLow = MutableStateFlow(false)
    val batteryLow: StateFlow<Boolean> = _batteryLow

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

    // HR time series for cardiac drift (capture during active session)
    private var hrSeriesActive = false
    private val hrSeries = ArrayDeque<Pair<Long, Int>>() // (elapsedMs, hr), capped at HR_SERIES_MAX_ENTRIES
    private var hrSeriesStart = 0L

    // Heart Rate Recovery tracking: each entry is (peakHr, peakTimestampMs).
    // At ~60s after each peak we record the delta = peakHr - currentHr.
    private val pendingHrrPeaks = mutableListOf<Pair<Int, Long>>()
    private val hrrDeltas = mutableListOf<Int>()

    /** Last computed HRR (BPM dropped 60s after the most recent peak). null = no peak yet. */
    private val _liveHrrLast = MutableStateFlow<Int?>(null)
    val liveHrrLast: StateFlow<Int?> = _liveHrrLast

    // HRR queueing: we want one HRR measurement per set (not gated by the
    // "fully recovered" state, which during intense training may never occur).
    private var lastQueuedPeakAtMs = 0L

    // Live ECG waveform + analyzer (exposed while an active session is running)
    private val liveAnalyzer = LiveEcgAnalyzer(sampleRate = 130)
    private val waveformBuffer = ArrayDeque<Int>() // last ~4s of ECG samples (µV)
    private val waveformCapacity = 130 * 4
    private var samplesSinceLastEmit = 0

    private val _ecgWaveform = MutableStateFlow<IntArray>(IntArray(0))
    val ecgWaveform: StateFlow<IntArray> = _ecgWaveform

    private val _liveEcgSnapshot = MutableStateFlow(LiveEcgAnalyzer.Snapshot(0, 100.0, 0, 0, 0))
    val liveEcgSnapshot: StateFlow<LiveEcgAnalyzer.Snapshot> = _liveEcgSnapshot

    private val _liveCardiacDrift = MutableStateFlow(0.0)
    val liveCardiacDrift: StateFlow<Double> = _liveCardiacDrift
    private var lastDriftComputeMs = 0L

    private val _readinessResult = MutableStateFlow(ReadinessResult())
    val readinessResult: StateFlow<ReadinessResult> = _readinessResult

    private val _vo2max = MutableStateFlow<Double?>(null)
    val vo2max: StateFlow<Double?> = _vo2max

    var connectedDeviceId: String? = null
        private set

    // User profile for calorie/TRIMP calculations
    private var userProfile = profileRepo.get()
    private val profileRepository = profileRepo

    // Calorie/TRIMP tracking
    private var lastHrTimestamp = 0L

    // HRV Readiness measurement
    private var readinessMeasuring = false
    private var readinessStartTime = 0L
    private val readinessRR = mutableListOf<Int>()
    private var readinessMinHr = 200

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
    private var ecgDisposable: Disposable? = null

    // ECG streaming state
    private var streamingFeatureReady = false
    private var pendingEcgSessionId: String? = null
    private var activeEcgSessionId: String? = null
    private val ecgRestartHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hrRestartHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Reconnection: distinguish a user-initiated disconnect from an unexpected BLE drop,
    // and remember the last device so we can reconnect to it automatically.
    private var lastConnectedDeviceId: String? = null
    @Volatile private var userInitiatedDisconnect = false
    private var reconnectStartAtMs = 0L

    // Watchdog: timestamps of the most recent sample of each kind
    @Volatile private var lastEcgSampleAtMs = 0L
    @Volatile private var lastHrSampleAtMs = 0L
    // When the current ECG stream was kicked off — used to detect silent "no data" hangs.
    @Volatile private var ecgStreamStartedAt = 0L

    private val api: PolarBleApi = PolarBleApiDefaultImpl.defaultImplementation(
        context,
        setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
        )
    ).also { api ->
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BLE power: $powered")
                if (!powered) {
                    appLogger.w(TAG, "BLE powered off — tearing down foreground service and reconnect loop")
                    // Bluetooth disabled — reconnect is impossible; tear everything down immediately
                    // so the foreground-service notification doesn't linger.
                    userInitiatedDisconnect = true
                    reconnectStartAtMs = 0L
                    reconnectHandler.removeCallbacksAndMessages(null)
                    hrDisposable?.dispose(); hrDisposable = null
                    ecgDisposable?.dispose(); ecgDisposable = null
                    ecgRestartHandler.removeCallbacksAndMessages(null)
                    hrRestartHandler.removeCallbacksAndMessages(null)
                    stopDataWatchdog()
                    ecgRecorder.stop()
                    streamingFeatureReady = false
                    connectedDeviceId = null
                    lastConnectedDeviceId = null
                    _heartRate.value = null
                    _batteryLevel.value = null
                    _batteryLow.value = false
                    _connectionState.value = ConnectionState.DISCONNECTED
                    PolarStreamingService.stop(context)
                    PolarStreamingService.clearDisconnectAlert(context)
                }
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Connected: ${polarDeviceInfo.deviceId}")
                appLogger.i(TAG, "Connected: ${polarDeviceInfo.deviceId} (${polarDeviceInfo.name}) midSession=$hrSeriesActive")
                connectedDeviceId = polarDeviceInfo.deviceId
                lastConnectedDeviceId = polarDeviceInfo.deviceId
                knownPolarDeviceRepository.setKnownDeviceId(polarDeviceInfo.deviceId)
                userInitiatedDisconnect = false
                reconnectStartAtMs = 0L
                reconnectHandler.removeCallbacksAndMessages(null)
                _connectionState.value = ConnectionState.CONNECTED
                lastHrTimestamp = System.currentTimeMillis()
                lastHrSampleAtMs = 0L
                lastEcgSampleAtMs = 0L
                // Run the 60s readiness measurement only on the FIRST connect, not on a
                // mid-session reconnect (which would re-measure readiness and reset its beep).
                if (!hrSeriesActive) {
                    _sessionCalories.value = 0.0
                    _sessionTrimp.value = 0.0
                    maybeStartAutoReadinessMeasurement()
                }
                startDataWatchdog()
                PolarStreamingService.start(context, polarDeviceInfo.name)
                PolarStreamingService.clearDisconnectAlert(context)
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Connecting: ${polarDeviceInfo.deviceId}")
                _connectionState.value = ConnectionState.CONNECTING
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                val involuntary = !userInitiatedDisconnect
                Log.d(TAG, "Disconnected: ${polarDeviceInfo.deviceId} (involuntary=$involuntary)")
                appLogger.w(TAG, "Disconnected: ${polarDeviceInfo.deviceId} involuntary=$involuntary midSession=$hrSeriesActive")
                connectedDeviceId = null
                _heartRate.value = null
                _batteryLevel.value = null
                _batteryLow.value = false
                hrDisposable?.dispose()
                hrDisposable = null
                ecgDisposable?.dispose()
                ecgDisposable = null
                ecgRestartHandler.removeCallbacksAndMessages(null)
                hrRestartHandler.removeCallbacksAndMessages(null)
                stopDataWatchdog()
                ecgRecorder.stop()
                streamingFeatureReady = false
                // Keep activeEcgSessionId so that a reconnect resumes ECG for the same session.
                // Mirror it into pendingEcgSessionId so the feature-ready callback will restart.
                activeEcgSessionId?.let { pendingEcgSessionId = it }

                if (involuntary && hrSeriesActive) {
                    // Unexpected drop DURING a session (lost skin contact / out of range /
                    // interference). Keep the foreground service alive so the OS lets the BLE
                    // stack reconnect in the background, alert the user with sound, and retry
                    // until it comes back. Outside a session a drop is usually the user taking
                    // off the strap, so we fall through to a quiet stop (no alert, no retry).
                    _connectionState.value = ConnectionState.CONNECTING
                    PolarStreamingService.setReconnecting(context)
                    PolarStreamingService.notifyDisconnected(context, polarDeviceInfo.name)
                    scheduleReconnect()
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    PolarStreamingService.stop(context)
                }
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
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                        streamingFeatureReady = true
                        appLogger.i(TAG, "ONLINE_STREAMING feature ready on $identifier pendingEcg=$pendingEcgSessionId")
                        // If a session requested ECG before feature was ready, start now
                        pendingEcgSessionId?.let { sessionId ->
                            pendingEcgSessionId = null
                            startEcgStreamingInternal(identifier, sessionId)
                        }
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
                // Always log the level so app.log carries the decay curve across
                // battery cycles — the H10 reports a voltage-derived percentage, which
                // is nearly flat for most of a CR2025's life, so a single reading says
                // little but the trend across sessions is readable.
                appLogger.i(TAG, "Battery level on $identifier: $level%")
                val low = level <= BATTERY_WARNING_THRESHOLD
                _batteryLow.value = low
                if (low) {
                    appLogger.w(TAG, "Battery at $level% (<= $BATTERY_WARNING_THRESHOLD%) — replace the CR2025 soon")
                }
            }
        })
    }

    private var autoConnectAttempted = false

    fun startScan() {
        _discoveredDevices.value = emptyList()
        _isScanning.value = true
        autoConnectAttempted = false

        val knownDeviceId = knownPolarDeviceRepository.getKnownDeviceId()

        scanDisposable?.dispose()
        scanDisposable = api.searchForDevice()
            .subscribe(
                { deviceInfo ->
                    val current = _discoveredDevices.value
                    if (current.none { it.deviceId == deviceInfo.deviceId }) {
                        _discoveredDevices.value = current + deviceInfo
                    }
                    if (!autoConnectAttempted && knownDeviceId != null && deviceInfo.deviceId == knownDeviceId) {
                        autoConnectAttempted = true
                        connectToDevice(deviceInfo.deviceId)
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
        // User-initiated: suppress the auto-reconnect path even if we're mid-reconnect
        // (connectedDeviceId is null while reconnecting).
        userInitiatedDisconnect = true
        reconnectStartAtMs = 0L
        reconnectHandler.removeCallbacksAndMessages(null)
        val deviceId = connectedDeviceId ?: lastConnectedDeviceId
        hrDisposable?.dispose()
        hrDisposable = null
        ecgDisposable?.dispose()
        ecgDisposable = null
        ecgRestartHandler.removeCallbacksAndMessages(null)
        hrRestartHandler.removeCallbacksAndMessages(null)
        stopDataWatchdog()
        ecgRecorder.stop()
        activeEcgSessionId = null
        pendingEcgSessionId = null
        PolarStreamingService.stop(context)
        PolarStreamingService.clearDisconnectAlert(context)
        _connectionState.value = ConnectionState.DISCONNECTED
        connectedDeviceId = null
        lastConnectedDeviceId = null
        if (deviceId != null) {
            try {
                api.disconnectFromDevice(deviceId)
            } catch (t: Throwable) {
                Log.w(TAG, "Disconnect failed: $t")
            }
        }
    }

    /**
     * Periodically retry connecting to [lastConnectedDeviceId] after an unexpected drop.
     * Stops as soon as the device reconnects, the user disconnects, or 5 minutes elapse.
     */
    private fun scheduleReconnect() {
        val id = lastConnectedDeviceId ?: return
        if (reconnectStartAtMs == 0L) reconnectStartAtMs = System.currentTimeMillis()
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed(object : Runnable {
            override fun run() {
                if (userInitiatedDisconnect || connectedDeviceId != null) return
                // Give up after 5 minutes — the device is likely off or too far away.
                if (System.currentTimeMillis() - reconnectStartAtMs > 5 * 60_000L) {
                    Log.w(TAG, "Reconnect timed out after 5 min — stopping service")
                    appLogger.w(TAG, "Reconnect timed out after 5 min for $id — stopping service")
                    reconnectStartAtMs = 0L
                    _connectionState.value = ConnectionState.DISCONNECTED
                    hrSeriesActive = false
                    PolarStreamingService.stop(context)
                    PolarStreamingService.clearDisconnectAlert(context)
                    return
                }
                Log.d(TAG, "Auto-reconnect attempt to $id")
                appLogger.i(TAG, "Auto-reconnect attempt to $id (elapsed ${(System.currentTimeMillis() - reconnectStartAtMs) / 1000}s)")
                try {
                    api.connectToDevice(id)
                } catch (t: Throwable) {
                    Log.w(TAG, "Reconnect attempt failed: $t")
                }
                reconnectHandler.postDelayed(this, 10_000)
            }
        }, 3_000)
    }

    fun shutdown() {
        userInitiatedDisconnect = true
        reconnectHandler.removeCallbacksAndMessages(null)
        scanDisposable?.dispose()
        hrDisposable?.dispose()
        ecgDisposable?.dispose()
        ecgRestartHandler.removeCallbacksAndMessages(null)
        hrRestartHandler.removeCallbacksAndMessages(null)
        stopDataWatchdog()
        ecgRecorder.stop()
        activeEcgSessionId = null
        pendingEcgSessionId = null
        PolarStreamingService.stop(context)
        api.shutDown()
    }

    private fun startHrStreaming(deviceId: String) {
        hrDisposable?.dispose()
        hrRestartHandler.removeCallbacksAndMessages(null)
        hrDisposable = api.startHrStreaming(deviceId)
            .doOnComplete {
                Log.w(TAG, "HR stream completed (no more samples) — scheduling restart in 2s")
                scheduleHrRestart(deviceId)
            }
            .subscribe(
                { hrData ->
                    lastHrSampleAtMs = System.currentTimeMillis()
                    val sample = hrData.samples.lastOrNull()
                    if (sample != null) {
                        _heartRate.value = sample.hr
                        PolarStreamingService.updateHr(context, sample.hr)

                        // Capture HR series for cardiac drift analysis
                        if (hrSeriesActive) {
                            val now = System.currentTimeMillis()
                            val elapsed = now - hrSeriesStart
                            if (hrSeries.size >= HR_SERIES_MAX_ENTRIES) hrSeries.removeFirst()
                            hrSeries.addLast(elapsed to sample.hr)
                            // Recompute live drift every ~30s
                            if (now - lastDriftComputeMs >= 30_000) {
                                lastDriftComputeMs = now
                                _liveCardiacDrift.value = cardiacDriftBpmPerMinute()
                            }
                        }

                        // Track lowest observed HR as resting estimate
                        if (sample.hr in 30..199 && sample.hr < lowestObservedHr) {
                            lowestObservedHr = sample.hr
                            restingHr = lowestObservedHr
                        }

                        // Gate Uneven detection: only count when HR is in a relaxed
                        // range (< 70% HRmax). During intense effort the RR variability
                        // is dominated by physiology, not arrhythmia.
                        val hrMaxFrac = sample.hr.toFloat() / userProfile.hrMax.coerceAtLeast(1)
                        liveAnalyzer.setUnevenGate(active = hrMaxFrac >= 0.70f)

                        // Process RR intervals
                        for (rr in sample.rrsMs) {
                            if (rr in 300..2000) {
                                // Recovery buffer
                                if (recentRR.size >= RR_BUFFER_SIZE) recentRR.removeFirst()
                                recentRR.addLast(rr)
                                // Readiness measurement
                                if (readinessMeasuring) {
                                    readinessRR.add(rr)
                                }
                            }
                        }

                        // Readiness measurement: collect for 60s then compute
                        if (readinessMeasuring) {
                            if (sample.hr in 30..199 && sample.hr < readinessMinHr) {
                                readinessMinHr = sample.hr
                            }
                            val elapsed = ((System.currentTimeMillis() - readinessStartTime) / 1000).toInt()
                            val remaining = (60 - elapsed).coerceAtLeast(0)
                            _readinessResult.value = _readinessResult.value.copy(
                                secondsRemaining = remaining,
                            )
                            if (elapsed >= 60) {
                                finishReadinessMeasurement()
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
                    Log.e(TAG, "HR streaming error: $error — scheduling restart in 2s")
                    appLogger.e(TAG, "HR streaming error: $error")
                    _heartRate.value = null
                    scheduleHrRestart(deviceId)
                }
            )
    }

    private fun scheduleHrRestart(deviceId: String) {
        hrRestartHandler.removeCallbacksAndMessages(null)
        hrRestartHandler.postDelayed({
            if (connectedDeviceId == deviceId) {
                Log.d(TAG, "Restarting HR stream after drop")
                startHrStreaming(deviceId)
            }
        }, 2000)
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
        if (hrWasRising && !isRising) {
            val peakHr = hrWindow.max()
            if (peakHr - restingHr >= PEAK_MIN_RISE_BPM) {
                val now = System.currentTimeMillis()
                // Recovery-state peak: only one active at a time (semaphore logic)
                if (!isRecovering) {
                    peakHrAfterSet = peakHr
                    isRecovering = true
                    recentRR.clear()
                    _recoveryState.value = RecoveryState.RECOVERING
                    _rmssd.value = null
                    Log.d(TAG, "Peak detected: $peakHr BPM, starting recovery (resting=$restingHr)")
                }
                // HRR queue: independent from recovery flag. Stricter criteria
                // than the recovery semaphore to avoid false positives from
                // light activity (walking, stair climbing).
                val hrMax = userProfile.hrMax
                val meetsHrrThresholds = peakHr - restingHr >= HRR_PEAK_MIN_RISE_BPM &&
                        peakHr >= hrMax * HRR_PEAK_MIN_HRMAX_FRACTION
                if (meetsHrrThresholds && now - lastQueuedPeakAtMs > HRR_QUEUE_DEBOUNCE_MS) {
                    pendingHrrPeaks.add(peakHr to now)
                    lastQueuedPeakAtMs = now
                }
            }
        }
        hrWasRising = isRising

        // Check if any queued peak has reached +60s → record the delta
        val now = System.currentTimeMillis()
        val iter = pendingHrrPeaks.iterator()
        while (iter.hasNext()) {
            val (peakHr, peakTs) = iter.next()
            if (now - peakTs >= 60_000) {
                val delta = peakHr - hr
                if (delta in 0..120) {
                    hrrDeltas.add(delta)
                    _liveHrrLast.value = delta
                }
                iter.remove()
            }
        }
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

    /** Start capturing the HR time series for the duration of a session. */
    fun startHrSeriesCapture() {
        // Reset the session counters here (session start) rather than on connect, so a
        // mid-session reconnect doesn't wipe the accumulated calories/TRIMP.
        _sessionCalories.value = 0.0
        _sessionTrimp.value = 0.0
        hrSeries.clear()
        hrSeriesStart = System.currentTimeMillis()
        hrSeriesActive = true
        lastDriftComputeMs = 0L
        _liveCardiacDrift.value = 0.0
        pendingHrrPeaks.clear()
        hrrDeltas.clear()
        _liveHrrLast.value = null
        lastQueuedPeakAtMs = 0L
    }

    /** Average HR recovery (BPM) 60s after each detected peak during the session. */
    fun averageHrr60s(): Double = if (hrrDeltas.isNotEmpty()) hrrDeltas.average() else 0.0

    /** Resting HR observed during the readiness measurement (or fallback to lowest seen). */
    fun sessionRestingHr(): Int = restingHr

    fun stopHrSeriesCapture() {
        hrSeriesActive = false
        // Session over: stop chasing a reconnection and clear any disconnect alert. If we were
        // still mid-reconnect (no device), tear down the now-pointless foreground service.
        reconnectHandler.removeCallbacksAndMessages(null)
        PolarStreamingService.clearDisconnectAlert(context)
        if (connectedDeviceId == null) {
            _connectionState.value = ConnectionState.DISCONNECTED
            PolarStreamingService.stop(context)
        }
    }

    /**
     * Cardiac drift rate in BPM/min over the captured HR series.
     * Requires ≥5 minutes of data, otherwise returns 0.
     * Positive value = HR drifted upward (possible dehydration/heat).
     */
    fun cardiacDriftBpmPerMinute(): Double {
        val data = hrSeries.toList()
        if (data.size < 60) return 0.0
        val totalMinutes = data.last().first / 60000.0
        if (totalMinutes < 5.0) return 0.0
        // Linear regression slope (HR vs minutes)
        val xs = data.map { it.first / 60000.0 }
        val ys = data.map { it.second.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            num += dx * (ys[i] - meanY)
            den += dx * dx
        }
        return if (den > 0) num / den else 0.0
    }

    /** Start raw ECG recording for [sessionId]. No-op if not connected. */
    fun startEcgRecording(sessionId: String) {
        val deviceId = connectedDeviceId ?: run {
            Log.d(TAG, "ECG start requested but not connected; queued")
            pendingEcgSessionId = sessionId
            return
        }
        if (streamingFeatureReady) {
            startEcgStreamingInternal(deviceId, sessionId)
        } else {
            pendingEcgSessionId = sessionId
            Log.d(TAG, "ECG start queued: streaming feature not yet ready")
        }
    }

    fun stopEcgRecording() {
        ecgDisposable?.dispose()
        ecgDisposable = null
        ecgRestartHandler.removeCallbacksAndMessages(null)
        ecgRecorder.stop()
        pendingEcgSessionId = null
        activeEcgSessionId = null
        ecgStreamStartedAt = 0L
        lastEcgSampleAtMs = 0L
    }

    /** Delete the recorded ECG file for a session. Called after analysis. */
    fun deleteEcgFile(sessionId: String) {
        ecgRecorder.delete(sessionId)
    }

    /**
     * Run post-session ECG analysis on the recorded file.
     * Returns null if no file or file too short.
     */
    fun analyzeSessionEcg(sessionId: String): EcgAnalysisResult? {
        val file = ecgRecorder.fileFor(sessionId)
        return ecgAnalyzer.analyze(file)
    }

    /** File size in bytes of the raw ECG for [sessionId], or 0 if missing. */
    fun ecgFileSize(sessionId: String): Long {
        val file = ecgRecorder.fileFor(sessionId)
        return if (file.exists()) file.length() else 0L
    }

    /** The raw ECG file for [sessionId] (may not exist). Used by [com.mygymapp.data.sync.EcgSyncWorker]. */
    fun ecgFileFor(sessionId: String): File = ecgRecorder.fileFor(sessionId)

    private fun startEcgStreamingInternal(deviceId: String, sessionId: String) {
        ecgDisposable?.dispose()
        // Reset live analyzer + waveform for a fresh session
        liveAnalyzer.reset()
        waveformBuffer.clear()
        _ecgWaveform.value = IntArray(0)
        _liveEcgSnapshot.value = LiveEcgAnalyzer.Snapshot(0, 100.0, 0, 0, 0)
        samplesSinceLastEmit = 0
        lastEcgSampleAtMs = 0L
        ecgStreamStartedAt = System.currentTimeMillis()
        // Remember which session this ECG belongs to, so we can auto-restart on error
        activeEcgSessionId = sessionId

        // Request the supported ECG settings and then start streaming at max (130Hz on H10)
        appLogger.i(TAG, "ECG start requested: session=$sessionId device=$deviceId")
        ecgDisposable = api.requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ECG)
            .map { it.maxSettings() }
            .flatMapPublisher { settings: PolarSensorSetting ->
                ecgRecorder.start(sessionId, sampleRate = 130, startTimestampNs = System.nanoTime())
                Log.d(TAG, "ECG streaming started for session $sessionId")
                appLogger.i(TAG, "ECG streaming started: session=$sessionId settings=${settings.settings}")
                api.startEcgStreaming(deviceId, settings)
            }
            .doOnComplete {
                Log.w(TAG, "ECG stream completed — scheduling restart in 2s")
                appLogger.w(TAG, "ECG stream completed unexpectedly for session=$sessionId — restarting")
                scheduleEcgRestart(deviceId, sessionId)
            }
            .subscribe(
                { ecgData ->
                    lastEcgSampleAtMs = System.currentTimeMillis()
                    for (sample in ecgData.samples) {
                        if (sample is EcgSample) {
                            val v = sample.voltage
                            ecgRecorder.writeSample(v)
                            liveAnalyzer.onSample(v)
                            // Waveform buffer
                            waveformBuffer.addLast(v)
                            if (waveformBuffer.size > waveformCapacity) waveformBuffer.removeFirst()
                            samplesSinceLastEmit++
                        }
                    }
                    // Emit waveform + snapshot on every block so the UI stays fresh
                    // even when BLE delivers small batches infrequently (e.g. screen off).
                    _ecgWaveform.value = waveformBuffer.toIntArray()
                    _liveEcgSnapshot.value = liveAnalyzer.snapshot()
                    samplesSinceLastEmit = 0
                },
                { error ->
                    Log.e(TAG, "ECG streaming error: $error — scheduling restart in 2s")
                    appLogger.e(TAG, "ECG streaming error for session=$sessionId: $error")
                    ecgRecorder.stop()
                    scheduleEcgRestart(deviceId, sessionId)
                }
            )
    }

    private fun scheduleEcgRestart(deviceId: String, sessionId: String) {
        ecgRestartHandler.removeCallbacksAndMessages(null)
        ecgRestartHandler.postDelayed({
            if (connectedDeviceId == deviceId &&
                streamingFeatureReady &&
                activeEcgSessionId == sessionId
            ) {
                Log.d(TAG, "Restarting ECG stream for session $sessionId")
                appLogger.w(TAG, "ECG stream restart for session=$sessionId")
                startEcgStreamingInternal(deviceId, sessionId)
            } else {
                appLogger.w(TAG, "ECG restart skipped: connected=${connectedDeviceId == deviceId} featureReady=$streamingFeatureReady activeSession=${activeEcgSessionId == sessionId}")
            }
        }, 2000)
    }

    /**
     * Periodic data-presence watchdog. Fires every 5s while the Polar is connected.
     * Forces a stream restart if no sample has arrived in a while — catches cases
     * where the Flowable neither errors nor completes, just stops delivering.
     */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val deviceId = connectedDeviceId
            if (deviceId != null) {
                // HR: if running and no sample for >15s, restart
                if (hrDisposable != null && lastHrSampleAtMs > 0 &&
                    now - lastHrSampleAtMs > 15_000
                ) {
                    Log.w(TAG, "HR watchdog: no sample for ${(now - lastHrSampleAtMs) / 1000}s — restarting")
                    lastHrSampleAtMs = 0L
                    startHrStreaming(deviceId)
                }
                // ECG: restart if (a) samples were flowing but stopped, OR
                // (b) stream was started but never delivered a first sample within 15s
                // (silent hang — the watchdog previously missed this case).
                val activeSession = activeEcgSessionId
                if (activeSession != null && streamingFeatureReady && ecgDisposable != null) {
                    val noFirstSample = lastEcgSampleAtMs == 0L &&
                        ecgStreamStartedAt > 0 &&
                        now - ecgStreamStartedAt > 15_000
                    val sampleTimeout = lastEcgSampleAtMs > 0 &&
                        now - lastEcgSampleAtMs > 10_000
                    if (noFirstSample || sampleTimeout) {
                        val reason = if (noFirstSample)
                            "no first sample after ${(now - ecgStreamStartedAt) / 1000}s"
                        else
                            "no sample for ${(now - lastEcgSampleAtMs) / 1000}s"
                        Log.w(TAG, "ECG watchdog: $reason — restarting")
                        appLogger.w(TAG, "ECG watchdog triggered ($reason) for session=$activeSession — restarting stream")
                        ecgStreamStartedAt = 0L
                        lastEcgSampleAtMs = 0L
                        startEcgStreamingInternal(deviceId, activeSession)
                    }
                }
            }
            watchdogHandler.postDelayed(this, 5_000)
        }
    }

    private fun startDataWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, 5_000)
    }

    private fun stopDataWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
    }

    /** Latest cutoff hour for the automatic on-connect readiness measurement (local time). */
    private val autoReadinessCutoff: LocalTime = LocalTime.of(10, 0)

    /**
     * Gates the automatic 60s readiness measurement that fires on first connect: only before
     * 10:00 local time, and only if today doesn't already have a measurement (so a strap
     * disconnect/reconnect later in the day reuses today's earlier result instead of
     * re-measuring). Manual re-measurement (if ever exposed in the UI) can still call
     * [startReadinessMeasurement] directly, bypassing these checks.
     */
    private fun maybeStartAutoReadinessMeasurement() {
        if (LocalTime.now().isAfter(autoReadinessCutoff)) {
            Log.d(TAG, "Skipping auto readiness measurement: after ${autoReadinessCutoff}")
            return
        }
        readinessScope.launch {
            val today = readinessRepository.getLatestForDate(LocalDate.now())
            if (today != null) {
                Log.d(TAG, "Skipping auto readiness measurement: already measured today (id=${today.id})")
                _readinessResult.value = ReadinessResult(
                    readiness = runCatching { Readiness.valueOf(today.readiness) }.getOrDefault(Readiness.NO_BASELINE),
                    lnRmssd = today.lnRmssd,
                    restingHr = today.restingHr,
                    secondsRemaining = 0,
                    recommendation = today.recommendation,
                )
                _vo2max.value = today.vo2max.takeIf { it > 0 }
                restingHr = today.restingHr.takeIf { it > 0 } ?: restingHr
                lowestObservedHr = today.restingHr.takeIf { it > 0 } ?: lowestObservedHr
                return@launch
            }
            startReadinessMeasurement()
        }
    }

    private fun startReadinessMeasurement() {
        readinessMeasuring = true
        readinessStartTime = System.currentTimeMillis()
        readinessRR.clear()
        readinessMinHr = 200
        _readinessResult.value = ReadinessResult(
            readiness = Readiness.MEASURING,
            secondsRemaining = 60,
        )
        _vo2max.value = null
        Log.d(TAG, "Readiness measurement started")
    }

    private fun finishReadinessMeasurement() {
        readinessMeasuring = false
        signalReadinessComplete()

        // Filter artifacts from collected RR
        val cleanRR = filterArtifacts(readinessRR)

        if (cleanRR.size < 20) {
            _readinessResult.value = ReadinessResult(
                readiness = Readiness.NO_BASELINE,
                restingHr = readinessMinHr.takeIf { it < 200 } ?: 0,
                secondsRemaining = 0,
                recommendation = "Not enough clean data. Try again staying still.",
            )
            return
        }

        // Calculate LnRMSSD
        val rmssdVal = calculateRMSSD(cleanRR)
        val lnRmssd = if (rmssdVal > 0) ln(rmssdVal) else 0.0

        // Use readiness min HR as resting HR
        val measuredRestingHr = readinessMinHr.takeIf { it < 200 } ?: 70
        restingHr = measuredRestingHr
        lowestObservedHr = measuredRestingHr

        // Persist today's resting HR and derive the 7-day baseline.
        // VO2max uses the min of the last 7 valid readings to reduce day-to-day
        // noise (caffeine, sleep, stress). Falls back to today's value if fewer.
        saveHrRestToBaseline(measuredRestingHr)
        val hrRestBaseline = loadHrRestBaseline()
        val hrRestForVo2 = hrRestBaseline.minOrNull() ?: measuredRestingHr

        // Calculate VO2max (Uth formula)
        val hrMax = userProfile.hrMax
        val vo2 = if (hrRestForVo2 > 0) 15.3 * (hrMax.toDouble() / hrRestForVo2) else null
        _vo2max.value = vo2

        // Load baseline from SharedPreferences (last 7 LnRMSSD values)
        val baseline = loadLnRmssdBaseline()
        saveLnRmssdToBaseline(lnRmssd)

        val readiness: Readiness
        val recommendation: String

        if (baseline.size < 7) {
            readiness = Readiness.NO_BASELINE
            recommendation = "Collecting baseline data (${baseline.size + 1}/7 days). LnRMSSD: %.1f".format(lnRmssd)
        } else {
            val mean = baseline.average()
            val sd = sqrt(baseline.map { (it - mean).pow(2) }.average())
            val zScore = if (sd > 0) (lnRmssd - mean) / sd else 0.0

            readiness = when {
                zScore < -1.5 -> Readiness.DELOAD_RECOMMENDED
                zScore < -1.0 -> Readiness.LIGHT_DAY
                zScore < 1.0 -> Readiness.NORMAL
                zScore > 1.5 -> Readiness.PEAK
                else -> Readiness.GOOD
            }
            recommendation = when (readiness) {
                Readiness.DELOAD_RECOMMENDED ->
                    "HRV significantly below baseline. Consider rest or light session."
                Readiness.LIGHT_DAY ->
                    "HRV moderately suppressed. Reduce volume or intensity by 20%."
                Readiness.NORMAL ->
                    "HRV within normal range. Proceed with planned workout."
                Readiness.GOOD ->
                    "HRV above baseline. Good day to push intensity."
                Readiness.PEAK ->
                    "HRV unusually high. Consider testing a PR."
                else -> ""
            }
        }

        _readinessResult.value = ReadinessResult(
            readiness = readiness,
            lnRmssd = lnRmssd,
            restingHr = measuredRestingHr,
            secondsRemaining = 0,
            recommendation = recommendation,
        )

        Log.d(TAG, "Readiness: $readiness, LnRMSSD=%.2f, restingHR=$measuredRestingHr (7d-min=$hrRestForVo2, n=${hrRestBaseline.size}), VO2max=${vo2?.let { "%.1f".format(it) }}".format(lnRmssd))
        appLogger.i(TAG, "Readiness: $readiness lnRMSSD=${"%.2f".format(lnRmssd)} restingHr=$measuredRestingHr vo2max=${vo2?.let { "%.1f".format(it) } ?: "n/a"} rrSamples=${cleanRR.size}")

        // Persist + sync immediately (docs/SYNC.md) — independent of whether the user
        // goes on to complete a workout session today. Fire-and-forget on readinessScope:
        // must never block/delay the UI update above, and a save/sync failure here must
        // never crash a BLE callback thread.
        readinessScope.launch {
            try {
                val event = readinessRepository.save(
                    readiness = readiness.name,
                    lnRmssd = lnRmssd,
                    restingHr = measuredRestingHr,
                    vo2max = vo2 ?: 0.0,
                    recommendation = recommendation,
                )
                appLogger.i(TAG, "Readiness event persisted: id=${event.id}")
                // Sync enqueue is gated the same way session sync is (docs/SYNC.md §1.5):
                // only the automatic path respects the enabled toggle. A manual resync
                // action for readiness events can be added later the same way "Resync
                // all" works for sessions, if that's ever needed.
                if (syncConfigRepository.isEnabled() && syncConfigRepository.isConfigured()) {
                    val file = readinessRepository.fileFor(event)
                    if (file.exists()) {
                        readinessLedgerRepository.enqueue(event.id, "readiness/${event.id}.md", file)
                        ReadinessSyncWorker.Scheduler.runExpedited(context)
                    }
                }
            } catch (e: Throwable) {
                appLogger.e(TAG, "Failed to persist/queue readiness event", e)
            }
        }
    }

    /** Short vibration + beep, fired when the 60s post-connection readiness window ends. */
    private fun signalReadinessComplete() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (t: Throwable) {
            Log.w(TAG, "Readiness vibration failed: $t")
        }
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                tone.release()
            }, 300)
        } catch (t: Throwable) {
            Log.w(TAG, "Readiness beep failed: $t")
        }
    }

    private fun filterArtifacts(rrIntervals: List<Int>): List<Int> {
        val filtered = rrIntervals.filter { it in 300..2000 }
        if (filtered.size < 3) return filtered
        val sorted = filtered.sorted()
        val median = sorted[sorted.size / 2]
        return filtered.filter { abs(it - median) < median * 0.20 }
    }

    private fun loadLnRmssdBaseline(): List<Double> {
        val prefs = context.getSharedPreferences("hrv_baseline", Context.MODE_PRIVATE)
        val csv = prefs.getString("lnrmssd_values", "") ?: ""
        if (csv.isBlank()) return emptyList()
        return csv.split(",").mapNotNull { it.toDoubleOrNull() }
    }

    private fun saveLnRmssdToBaseline(lnRmssd: Double) {
        val existing = loadLnRmssdBaseline().toMutableList()
        existing.add(lnRmssd)
        // Keep last 14 days
        while (existing.size > 14) existing.removeFirst()
        val prefs = context.getSharedPreferences("hrv_baseline", Context.MODE_PRIVATE)
        prefs.edit().putString("lnrmssd_values", existing.joinToString(",")).apply()
    }

    private fun loadHrRestBaseline(): List<Int> {
        val prefs = context.getSharedPreferences("hrv_baseline", Context.MODE_PRIVATE)
        val csv = prefs.getString("hrrest_values", "") ?: ""
        if (csv.isBlank()) return emptyList()
        return csv.split(",").mapNotNull { it.toIntOrNull() }
    }

    private fun saveHrRestToBaseline(hrRest: Int) {
        val existing = loadHrRestBaseline().toMutableList()
        existing.add(hrRest)
        // Keep last 7 readings (rolling window used for VO2max)
        while (existing.size > 7) existing.removeFirst()
        val prefs = context.getSharedPreferences("hrv_baseline", Context.MODE_PRIVATE)
        prefs.edit().putString("hrrest_values", existing.joinToString(",")).apply()
    }

    private fun calculateRMSSD(rrIntervals: List<Int>): Double {
        if (rrIntervals.size < 2) return 0.0
        val diffs = rrIntervals.zipWithNext { a, b -> (b - a).toDouble().pow(2) }
        return sqrt(diffs.average())
    }
}
