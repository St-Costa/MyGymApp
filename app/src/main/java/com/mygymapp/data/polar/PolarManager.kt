package com.mygymapp.data.polar

import android.content.Context
import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.mygymapp.ui.service.PolarStreamingService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.disposables.Disposable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    // HR time series for cardiac drift (capture during active session)
    private var hrSeriesActive = false
    private val hrSeries = mutableListOf<Pair<Long, Int>>() // (elapsedMs, hr)
    private var hrSeriesStart = 0L

    // Heart Rate Recovery tracking: each entry is (peakHr, peakTimestampMs).
    // At ~60s after each peak we record the delta = peakHr - currentHr.
    private val pendingHrrPeaks = mutableListOf<Pair<Int, Long>>()
    private val hrrDeltas = mutableListOf<Int>()

    /** Last computed HRR (BPM dropped 60s after the most recent peak). null = no peak yet. */
    private val _liveHrrLast = MutableStateFlow<Int?>(null)
    val liveHrrLast: StateFlow<Int?> = _liveHrrLast

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
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Connected: ${polarDeviceInfo.deviceId}")
                connectedDeviceId = polarDeviceInfo.deviceId
                _connectionState.value = ConnectionState.CONNECTED
                _sessionCalories.value = 0.0
                _sessionTrimp.value = 0.0
                lastHrTimestamp = System.currentTimeMillis()
                startReadinessMeasurement()
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
                ecgDisposable?.dispose()
                ecgDisposable = null
                ecgRecorder.stop()
                streamingFeatureReady = false
                pendingEcgSessionId = null
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
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                        streamingFeatureReady = true
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
        ecgDisposable?.dispose()
        ecgDisposable = null
        ecgRecorder.stop()
        PolarStreamingService.stop(context)
        api.disconnectFromDevice(deviceId)
    }

    fun shutdown() {
        scanDisposable?.dispose()
        hrDisposable?.dispose()
        ecgDisposable?.dispose()
        ecgRecorder.stop()
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

                        // Capture HR series for cardiac drift analysis
                        if (hrSeriesActive) {
                            val now = System.currentTimeMillis()
                            val elapsed = now - hrSeriesStart
                            hrSeries.add(elapsed to sample.hr)
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
                // Queue this peak for HRR measurement at +60s
                pendingHrrPeaks.add(peakHr to System.currentTimeMillis())
                Log.d(TAG, "Peak detected: $peakHr BPM, starting recovery (resting=$restingHr)")
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
        hrSeries.clear()
        hrSeriesStart = System.currentTimeMillis()
        hrSeriesActive = true
        lastDriftComputeMs = 0L
        _liveCardiacDrift.value = 0.0
        pendingHrrPeaks.clear()
        hrrDeltas.clear()
        _liveHrrLast.value = null
    }

    /** Average HR recovery (BPM) 60s after each detected peak during the session. */
    fun averageHrr60s(): Double = if (hrrDeltas.isNotEmpty()) hrrDeltas.average() else 0.0

    /** Resting HR observed during the readiness measurement (or fallback to lowest seen). */
    fun sessionRestingHr(): Int = restingHr

    fun stopHrSeriesCapture() {
        hrSeriesActive = false
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
        ecgRecorder.stop()
        pendingEcgSessionId = null
    }

    /** Delete the recorded ECG file for a session. Called after analysis. */
    fun deleteEcgFile(sessionId: String) {
        ecgRecorder.delete(sessionId)
    }

    /**
     * Run post-session ECG analysis on the recorded file.
     * Returns null if no file or file too short. Caller should delete the file.
     */
    fun analyzeSessionEcg(sessionId: String): EcgAnalysisResult? {
        val file = ecgRecorder.fileFor(sessionId)
        return ecgAnalyzer.analyze(file)
    }

    private fun startEcgStreamingInternal(deviceId: String, sessionId: String) {
        ecgDisposable?.dispose()
        // Reset live analyzer + waveform for a fresh session
        liveAnalyzer.reset()
        waveformBuffer.clear()
        _ecgWaveform.value = IntArray(0)
        _liveEcgSnapshot.value = LiveEcgAnalyzer.Snapshot(0, 100.0, 0, 0, 0)
        samplesSinceLastEmit = 0

        // Request the supported ECG settings and then start streaming at max (130Hz on H10)
        ecgDisposable = api.requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ECG)
            .map { it.maxSettings() }
            .flatMapPublisher { settings: PolarSensorSetting ->
                ecgRecorder.start(sessionId, sampleRate = 130, startTimestampNs = System.nanoTime())
                Log.d(TAG, "ECG streaming started for session $sessionId")
                api.startEcgStreaming(deviceId, settings)
            }
            .subscribe(
                { ecgData ->
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
                    // Emit waveform + snapshot ~2x per second (every 65 samples @130Hz)
                    if (samplesSinceLastEmit >= 65) {
                        _ecgWaveform.value = waveformBuffer.toIntArray()
                        _liveEcgSnapshot.value = liveAnalyzer.snapshot()
                        samplesSinceLastEmit = 0
                    }
                },
                { error ->
                    Log.e(TAG, "ECG streaming error: $error")
                    ecgRecorder.stop()
                }
            )
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

        // Calculate VO2max (Uth formula)
        val hrMax = userProfile.hrMax
        val vo2 = if (measuredRestingHr > 0) 15.3 * (hrMax.toDouble() / measuredRestingHr) else null
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

        Log.d(TAG, "Readiness: $readiness, LnRMSSD=%.2f, restingHR=$measuredRestingHr, VO2max=${vo2?.let { "%.1f".format(it) }}".format(lnRmssd))
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

    private fun calculateRMSSD(rrIntervals: List<Int>): Double {
        if (rrIntervals.size < 2) return 0.0
        val diffs = rrIntervals.zipWithNext { a, b -> (b - a).toDouble().pow(2) }
        return sqrt(diffs.average())
    }
}
