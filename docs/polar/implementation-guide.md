# Polar H10 Integration Guide for Android Gym App

> **Purpose**: This document is a technical reference for implementing Polar H10 heart rate monitor integration into a custom Android gym app. It covers BLE connection setup, Polar SDK integration, and all planned features with implementation details.

---

## Table of Contents

1. [Hardware & SDK Overview](#1-hardware--sdk-overview)
2. [Project Setup & Dependencies](#2-project-setup--dependencies)
3. [Android Permissions & Lifecycle](#3-android-permissions--lifecycle)
4. [BLE Connection Architecture](#4-ble-connection-architecture)
5. [Polar SDK Integration](#5-polar-sdk-integration)
6. [Data Streams Reference](#6-data-streams-reference)
7. [Feature Implementations](#7-feature-implementations)
   - 7.1 [Real-Time HR Display](#71-real-time-hr-display)
   - 7.2 [Smart Recovery Timer](#72-smart-recovery-timer-between-sets)
   - 7.3 [Calorie Estimation](#73-calorie-estimation-from-continuous-hr)
   - 7.4 [HRV Readiness Score](#74-hrv-readiness-score-pre-workout)
   - 7.5 [Rep Counting via Accelerometer](#75-automatic-rep-counting-via-accelerometer)
   - 7.6 [Tempo Under Tension (TUT)](#76-tempo-under-tension-tut)
   - 7.7 [Intra-Set Fatigue Detection](#77-intra-set-fatigue-detection-via-hrv)
   - 7.8 [Arrhythmia Screening](#78-passive-arrhythmia-screening)
   - 7.9 [Cardiac Drift Detection](#79-cardiac-drift-detection)
   - 7.10 [VO2max Estimation](#710-vo2max-estimation-from-submaximal-test)
   - 7.11 [Exercise Classification](#711-automatic-exercise-classification)
   - 7.12 [TRIMP & Training Load](#712-trimp--training-load-quantification)
   - 7.13 [Respiratory Rate from ECG](#713-respiratory-rate-estimation-from-ecg)
   - 7.14 [ANS Balance Post-Session](#714-autonomic-nervous-system-balance-post-session)
   - 7.15 [Blood Pressure Surrogate (Experimental)](#715-blood-pressure-surrogate-experimental)
   - 7.16 [Overreaching Detection](#716-overreaching-detection-multi-week)
8. [Dashboard UI: The "Set Screen"](#8-dashboard-ui-the-set-screen)
9. [Data Persistence & Export](#9-data-persistence--export)
10. [Testing Strategy](#10-testing-strategy)

---

## 1. Hardware & SDK Overview

### Polar H10 Capabilities

The Polar H10 chest strap exposes three sensor streams via the Polar BLE SDK:

| Stream | Sample Rate | Data | Use Cases |
|--------|-------------|------|-----------|
| **ECG** | 130 Hz | Voltage in µV (single-lead, Lead I modified) | HRV, arrhythmia screening, respiratory rate extraction |
| **Accelerometer** | 200 Hz | 3-axis (X, Y, Z) in mg | Rep counting, exercise classification, TUT |
| **HR + RR** | ~1 Hz (HR), variable (RR) | BPM + RR intervals in ms | Recovery timer, calorie estimation, training load |

**Physical constraints to remember**:
- Single-lead ECG only (no 12-lead morphology)
- 130 Hz ECG: Nyquist at 65 Hz, sufficient for R-peak detection and HRV (useful ECG content is below 40 Hz)
- Accelerometer is on the chest, not the limbs: captures trunk movement patterns
- Requires skin contact to activate BLE advertising
- Supports **2 simultaneous BLE connections** (e.g., phone + watch)
- Battery: CR2025, ~400 hours

### Two Integration Paths

| Path | What You Get | When to Use |
|------|-------------|-------------|
| **Standard BLE HR Profile** (0x180D) | HR (BPM) + RR intervals + sensor contact status | Universal, works with any chest strap |
| **Polar BLE SDK** | All of the above + ECG raw + accelerometer + internal recording + SDK Mode | Polar H10 only, needed for advanced features |

**Architecture recommendation**: Implement standard BLE HR profile as the base layer (for users with non-Polar straps), then layer Polar SDK features on top for H10 users. The app should detect at connection time whether the device is a Polar H10 and unlock advanced features accordingly.

---

## 2. Project Setup & Dependencies

### Gradle Dependencies

```kotlin
// build.gradle.kts (app module)
dependencies {
    // Polar BLE SDK (check for latest version on GitHub: polarofficial/polar-ble-sdk)
    implementation("com.github.polarofficial:polar-ble-sdk:6.15.0")
    
    // RxJava 3 (required by Polar SDK)
    implementation("io.reactivex.rxjava3:rxjava:3.1.9")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    
    // Optional: BLESSED library for standard BLE fallback (non-Polar devices)
    implementation("com.github.weliem:blessed-android-coroutines:0.8.0")
    
    // Charting library for ECG waveform display
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
}

// settings.gradle.kts - add JitPack repository
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

### Min SDK & Compile SDK

```kotlin
android {
    compileSdk = 34
    defaultConfig {
        minSdk = 24  // Required by Polar SDK
        targetSdk = 34
    }
}
```

---

## 3. Android Permissions & Lifecycle

### AndroidManifest.xml

```xml
<!-- BLE permissions -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>

<!-- Android 12+ (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>

<!-- Android 11 and below -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>

<!-- Required for BLE -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true"/>

<!-- Foreground service for continuous streaming -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>

<application>
    <service
        android:name=".service.PolarStreamingService"
        android:foregroundServiceType="connectedDevice"
        android:exported="false"/>
</application>
```

### Runtime Permission Flow

```kotlin
// CRITICAL: Permission handling differs by Android version
// Android 12+ (API 31+): BLUETOOTH_SCAN + BLUETOOTH_CONNECT
// Android 11 and below: ACCESS_FINE_LOCATION

private val requiredPermissions: Array<String>
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

// Request permissions before any BLE operation
private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { grants ->
    if (grants.values.all { it }) {
        initPolarApi()
    } else {
        // Show rationale: BLE requires these permissions
    }
}
```

### Foreground Service (MANDATORY for Continuous Streaming)

**Why**: Android kills background BLE connections aggressively. Without a foreground service, the ECG/ACC stream will drop within 1-2 minutes of screen-off. This is the #1 cause of "it stops working" bugs.

```kotlin
class PolarStreamingService : Service() {
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HR Monitor Active")
            .setContentText("Recording heart rate data")
            .setSmallIcon(R.drawable.ic_heart)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }
    
    // The PolarBleApi instance should live here, not in the Activity
    // This ensures streaming continues through configuration changes
    // and screen-off states
}
```

---

## 4. BLE Connection Architecture

### Connection State Machine

```
[DISCONNECTED] --> scan/search --> [SEARCHING] --> device found --> [CONNECTING]
     ^                                                                    |
     |                                                    success         |
     |                                                       v            |
     +---- connection lost ---- [RECONNECTING] <--- [CONNECTED]          |
     |                              |                    |                |
     |                              +--- max retries --->+                |
     |                                                                    |
     +<------------------- failure ------------------------------------->+
```

### Key BLE Gotchas on Android

```kotlin
// GOTCHA 1: Never pair via Android system Bluetooth settings
// System-level pairing can block app-level GATT connections
// Always handle discovery and connection within your app code

// GOTCHA 2: The H10 requires skin contact to start BLE advertising
// Your scan may return nothing if the strap is not being worn
// Implement: "Put on the strap and wet the electrodes" user prompt

// GOTCHA 3: First 60-90 seconds of HR data may be erratic
// Dry electrodes produce noise until sweat develops
// Implement: "Sensor warming up" indicator, discard data with
// sensorContact == false

// GOTCHA 4: Low battery (<3V) causes inverted HR patterns
// Monitor battery level and warn user

// GOTCHA 5: Polar H10 firmware 3.3.1+ required for Android 13+ stability
// Check firmware version after connection and prompt update if needed

// GOTCHA 6: BLE GATT operations must be serialized
// Never call readCharacteristic() while another operation is pending
// The Polar SDK handles this internally, but if you mix raw BLE
// calls with SDK calls, you WILL get GATT_BUSY errors
```

### Reconnection Strategy

```kotlin
// Implement exponential backoff for reconnection
// Polar SDK has built-in reconnection, but you should handle
// the UI states yourself

private val reconnectDelays = listOf(1000L, 2000L, 4000L, 8000L, 16000L)
private var reconnectAttempt = 0

fun onDisconnected(deviceId: String) {
    if (reconnectAttempt < reconnectDelays.size) {
        val delay = reconnectDelays[reconnectAttempt]
        reconnectAttempt++
        handler.postDelayed({ api.connectToDevice(deviceId) }, delay)
        updateUI(ConnectionState.RECONNECTING, attempt = reconnectAttempt)
    } else {
        updateUI(ConnectionState.DISCONNECTED)
        // Prompt user to check strap placement
    }
}

fun onConnected() {
    reconnectAttempt = 0
    updateUI(ConnectionState.CONNECTED)
}
```

---

## 5. Polar SDK Integration

### Initialization

```kotlin
class PolarManager(private val context: Context) {
    
    private lateinit var api: PolarBleApi
    
    // Disposables for RxJava streams
    private val disposables = CompositeDisposable()
    
    fun init() {
        api = PolarBleApi.defaultImplementation(
            context,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO
            )
        )
        
        // Set API callbacks
        api.setApiCallback(object : PolarBleApiCallback() {
            
            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Connected: ${polarDeviceInfo.deviceId}")
                onConnected()
            }
            
            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Disconnected: ${polarDeviceInfo.deviceId}")
                onDisconnected(polarDeviceInfo.deviceId)
            }
            
            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                updateUI(ConnectionState.CONNECTING)
            }
            
            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature
            ) {
                // IMPORTANT: Only start streaming AFTER the feature is ready
                when (feature) {
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                        // Now safe to call startEcgStreaming, startAccStreaming
                        onStreamingReady(identifier)
                    }
                    PolarBleApi.PolarBleSdkFeature.FEATURE_HR -> {
                        // HR is available via standard BLE profile
                    }
                    else -> {}
                }
            }
            
            override fun batteryLevelReceived(identifier: String, level: Int) {
                // Track battery, warn if low
                updateBatteryUI(level)
            }
        })
    }
    
    fun connect(deviceId: String) {
        // deviceId is the Polar device ID printed on the sensor (e.g., "A0B1C2D3")
        // NOT a MAC address
        api.connectToDevice(deviceId)
    }
    
    fun searchForDevice() {
        // Alternative: scan for nearby Polar devices
        val disposable = api.searchForDevice()
            .subscribe(
                { polarDeviceInfo ->
                    // Show device in UI for user to select
                    // polarDeviceInfo.deviceId, .name, .rssi
                },
                { error -> Log.e(TAG, "Search error: $error") }
            )
        disposables.add(disposable)
    }
    
    fun disconnect(deviceId: String) {
        api.disconnectFromDevice(deviceId)
    }
    
    fun shutdown() {
        disposables.clear()
        api.shutDown()
    }
}
```

### Starting Data Streams

```kotlin
fun startAllStreams(deviceId: String) {
    // OPTIONAL BUT RECOMMENDED: Enable SDK Mode for raw ECG
    // This disables on-device algorithms for cleaner signal
    api.enableSDKMode(deviceId)
        .subscribe(
            { Log.d(TAG, "SDK Mode enabled") },
            { Log.e(TAG, "SDK Mode failed: $it") }
        )
    
    // --- ECG Stream ---
    val ecgDisposable = api.startEcgStreaming(deviceId)
        .subscribe(
            { ecgData: PolarEcgData ->
                for (sample in ecgData.samples) {
                    // sample.timeStamp: Long (nanoseconds since epoch)
                    // sample.voltage: Int (µV, e.g., -234, 512, 1089)
                    processEcgSample(sample.timeStamp, sample.voltage)
                }
            },
            { error -> Log.e(TAG, "ECG error: $error") }
        )
    disposables.add(ecgDisposable)
    
    // --- Accelerometer Stream ---
    val accSettings = PolarSensorSetting(
        mapOf(PolarSensorSetting.SettingType.SAMPLE_RATE to 200)
    )
    val accDisposable = api.startAccStreaming(deviceId, accSettings)
        .subscribe(
            { accData: PolarAccelerometerData ->
                for (sample in accData.samples) {
                    // sample.timeStamp: Long (nanoseconds)
                    // sample.x, sample.y, sample.z: Int (in mg, 1000 = 1g)
                    processAccSample(sample.timeStamp, sample.x, sample.y, sample.z)
                }
            },
            { error -> Log.e(TAG, "ACC error: $error") }
        )
    disposables.add(accDisposable)
    
    // --- HR Stream (standard BLE, always available) ---
    val hrDisposable = api.startHrStreaming(deviceId)
        .subscribe(
            { hrData: PolarHrData ->
                for (sample in hrData.samples) {
                    // sample.hr: Int (BPM)
                    // sample.rrsMs: List<Int> (RR intervals in milliseconds)
                    // sample.contactStatus: Boolean
                    // sample.contactStatusSupported: Boolean
                    processHrSample(sample.hr, sample.rrsMs, sample.contactStatus)
                }
            },
            { error -> Log.e(TAG, "HR error: $error") }
        )
    disposables.add(hrDisposable)
}

fun stopAllStreams() {
    disposables.clear()
    // Streams stop automatically when disposables are cleared
}
```

### Standard BLE Fallback (Non-Polar Devices)

```kotlin
// For users with CooSpo, Garmin, Wahoo, Magene, etc.
// Use the BLESSED library to read standard BLE HR profile

class StandardBleHrManager(private val context: Context) {
    
    private val central = BluetoothCentralManager(context, callback, Handler(Looper.getMainLooper()))
    
    private val callback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            // Filter by HR service UUID
            if (scanResult.scanRecord?.serviceUuids?.contains(
                ParcelUuid(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))
            ) == true) {
                central.connectPeripheral(peripheral, peripheralCallback)
            }
        }
    }
    
    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (characteristic.uuid == UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")) {
                parseHeartRateMeasurement(value)
            }
        }
    }
    
    private fun parseHeartRateMeasurement(data: ByteArray) {
        // Byte 0: Flags
        val flags = data[0].toInt()
        val is16Bit = (flags and 0x01) != 0
        val hasRR = (flags and 0x10) != 0
        val hasContact = (flags and 0x06) != 0
        val contactDetected = if (hasContact) (flags and 0x02) != 0 else null
        
        // Heart rate value
        var offset = 1
        val hr = if (is16Bit) {
            val value = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            offset = 3
            value
        } else {
            val value = data[1].toInt() and 0xFF
            offset = 2
            value
        }
        
        // RR intervals (in 1/1024 seconds, convert to ms)
        val rrIntervals = mutableListOf<Int>()
        if (hasRR) {
            while (offset + 1 < data.size) {
                val rr1024 = (data[offset].toInt() and 0xFF) or 
                             ((data[offset + 1].toInt() and 0xFF) shl 8)
                val rrMs = (rr1024 * 1000) / 1024  // Convert to milliseconds
                rrIntervals.add(rrMs)
                offset += 2
            }
        }
        
        // Feed into the same data pipeline as Polar SDK data
        processHrSample(hr, rrIntervals, contactDetected ?: true)
    }
    
    fun startScan() {
        central.scanForPeripheralsWithServices(
            arrayOf(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))
        )
    }
}
```

---

## 6. Data Streams Reference

### ECG Data Format

```
Sample rate: 130 Hz (fixed, not configurable)
Resolution: 14-bit ADC
Unit: µV (microvolts)
Range: approximately -1500 to +1500 µV (typical QRS complex)
Lead configuration: Modified Lead I (left-right across chest)
Timestamp: nanoseconds (Long)

Data arrives in blocks of ~13-20 samples per callback (~100-150ms of data per block)

Time reconstruction:
  t[n] = block_timestamp + (n * 1/130) seconds
  OR use individual sample timestamps if provided
  Inter-sample interval = 7.692 ms
```

### Accelerometer Data Format

```
Sample rate: 25, 50, 100, or 200 Hz (configurable, use 200 for rep counting)
Unit: mg (milligravity, 1000 mg = 1g = 9.81 m/s²)
Axes (when strap is worn normally, logo facing out):
  X: lateral (left-right across chest)
  Y: vertical (head-to-feet)
  Z: anterior-posterior (chest-to-back, includes gravity component ~1000mg)
Range: ±8g
```

### HR + RR Data Format

```
HR: integer BPM (beats per minute)
RR intervals: List<Int> in milliseconds
  - Multiple RR values can arrive per HR update (if multiple beats occurred)
  - RR intervals are the time between successive R-peaks
  - Normal range: 300-1500 ms (corresponds to 40-200 BPM)
Contact status: boolean (true = electrodes have skin contact)
```

---

## 7. Feature Implementations

### 7.1 Real-Time HR Display

**Complexity**: Low
**Data needed**: HR stream (standard BLE, works with any strap)

```kotlin
// Basic real-time HR display with zone coloring
data class HrZone(val name: String, val minPct: Float, val maxPct: Float, val color: Int)

val defaultZones = listOf(
    HrZone("Rest",     0.0f,  0.5f,  Color.GRAY),
    HrZone("Warm-up",  0.5f,  0.6f,  Color.BLUE),
    HrZone("Fat Burn", 0.6f,  0.7f,  Color.GREEN),
    HrZone("Cardio",   0.7f,  0.8f,  Color.YELLOW),
    HrZone("Peak",     0.8f,  0.9f,  Color.RED),
    HrZone("Max",      0.9f,  1.0f,  Color.MAGENTA)
)

// HRmax estimation: use Tanaka formula (more accurate than 220-age)
// HRmax = 208 - (0.7 * age)
// Allow user to override with measured HRmax
fun getCurrentZone(hr: Int, hrMax: Int): HrZone {
    val pct = hr.toFloat() / hrMax
    return defaultZones.last { pct >= it.minPct }
}
```

### 7.2 Smart Recovery Timer Between Sets

**Complexity**: Medium
**Data needed**: HR + RR stream
**Key insight**: Don't use a fixed timer. Monitor parasympathetic reactivation via HR decay and RMSSD recovery.

```kotlin
class RecoveryTimer(
    private val userHrMax: Int,
    private val userRestingHr: Int
) {
    // Recovery threshold: HR must drop to this % of the delta above resting
    // Default: 70% recovery (HR dropped 70% of the way back to resting)
    var recoveryThreshold = 0.70f
    
    private var peakHrAfterSet: Int = 0
    private var isRecovering = false
    private val recentRR = ArrayDeque<Int>(30) // Last 30 RR intervals for RMSSD
    
    fun onSetCompleted(currentHr: Int) {
        peakHrAfterSet = currentHr
        isRecovering = true
        recentRR.clear()
    }
    
    fun onHrUpdate(hr: Int, rrIntervals: List<Int>) {
        if (!isRecovering) return
        
        rrIntervals.forEach { rr ->
            if (recentRR.size >= 30) recentRR.removeFirst()
            recentRR.addLast(rr)
        }
        
        // Method 1: HR-based recovery
        val hrDelta = peakHrAfterSet - userRestingHr
        val currentRecovery = (peakHrAfterSet - hr).toFloat() / hrDelta
        val hrReady = currentRecovery >= recoveryThreshold
        
        // Method 2: RMSSD-based recovery (parasympathetic reactivation)
        val rmssd = calculateRMSSD(recentRR.toList())
        // RMSSD > 20ms generally indicates sufficient parasympathetic tone
        // Personalize this threshold over time based on user's baseline
        val rmssdReady = rmssd > 20.0 && recentRR.size >= 15
        
        // Combined signal
        val readyState = when {
            hrReady && rmssdReady -> ReadyState.READY        // Green
            hrReady || rmssdReady -> ReadyState.ALMOST_READY // Yellow
            else -> ReadyState.RECOVERING                    // Red
        }
        
        updateRecoveryUI(readyState, currentRecovery, rmssd, hr)
    }
    
    private fun calculateRMSSD(rrIntervals: List<Int>): Double {
        if (rrIntervals.size < 2) return 0.0
        val diffs = rrIntervals.zipWithNext { a, b -> (b - a).toDouble().pow(2) }
        return sqrt(diffs.average())
    }
}
```

**Adaptive threshold**: After 4+ weeks of data, calculate the user's personal RMSSD recovery baseline per exercise type. Squat recovery RMSSD will differ from bicep curl RMSSD.

### 7.3 Calorie Estimation from Continuous HR

**Complexity**: Low-Medium
**Data needed**: HR stream (continuous)

```kotlin
// Keytel et al. (2005) formula - validated for mixed exercise
// Returns kcal/min

fun caloriesPerMinute(
    hr: Int,          // current heart rate in BPM
    weightKg: Double, // body weight
    age: Int,
    isMale: Boolean
): Double {
    return if (isMale) {
        // Male formula
        (-55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * age) / 4.184
    } else {
        // Female formula
        (-20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.074 * age) / 4.184
    }
}

// IMPORTANT CAVEATS FOR RESISTANCE TRAINING:
// 1. HR-based calorie estimation underestimates actual energy expenditure
//    during resistance training by ~20-30% because:
//    - Isometric contractions raise HR without proportional VO2 increase
//    - Valsalva maneuver transiently spikes HR
//    - EPOC (excess post-exercise oxygen consumption) is not captured
//
// 2. To partially compensate, add EPOC estimation:
//    After each set, calculate the "excess HR area" above baseline
//    and apply a correction factor

fun estimateEPOC(hrTimeSeries: List<Pair<Long, Int>>, baselineHr: Int): Double {
    // Integrate HR above baseline for 2 minutes post-set
    // Each BPM-minute above baseline ≈ 0.05 extra kcal (rough approximation)
    var epocKcal = 0.0
    for ((_, hr) in hrTimeSeries) {
        if (hr > baselineHr) {
            epocKcal += (hr - baselineHr) * 0.05 / 60.0 // per-second contribution
        }
    }
    return epocKcal
}

// Display strategy: show "XX kcal (estimated)" with clear labeling
// Never present as exact. Show range: "185-240 kcal" rather than "212 kcal"
```

### 7.4 HRV Readiness Score Pre-Workout

**Complexity**: Medium
**Data needed**: ECG or RR intervals (60-second morning measurement)

```kotlin
class ReadinessScore(private val dbHelper: HrvDatabaseHelper) {
    
    // Protocol: 60 seconds of supine/seated rest, breathing normally
    // Run this before every workout
    
    fun measureReadiness(
        rrIntervals: List<Int>,  // 60 seconds of RR data (~60-80 beats)
        deviceId: String
    ): ReadinessResult {
        
        // Step 1: Filter artifacts
        val cleanRR = filterArtifacts(rrIntervals)
        if (cleanRR.size < 40) return ReadinessResult.InsufficientData
        
        // Step 2: Calculate LnRMSSD (natural log of RMSSD)
        // LnRMSSD is preferred over raw RMSSD because it normalizes
        // the skewed distribution of RMSSD values
        val rmssd = calculateRMSSD(cleanRR)
        val lnRmssd = ln(rmssd)
        
        // Step 3: Compare to rolling 7-day baseline
        val baseline = dbHelper.getLast7DaysLnRmssd()
        val baselineMean = baseline.average()
        val baselineSD = baseline.standardDeviation()
        
        // Step 4: Calculate z-score
        val zScore = (lnRmssd - baselineMean) / baselineSD
        
        // Step 5: Interpret
        // Plews et al. (2013): LnRMSSD < baseline - 1 SD = parasympathetic suppression
        val readiness = when {
            zScore < -1.5 -> Readiness.DELOAD_RECOMMENDED  // Significantly suppressed
            zScore < -1.0 -> Readiness.LIGHT_DAY            // Moderately suppressed
            zScore < 1.0  -> Readiness.NORMAL                // Within normal range
            zScore > 1.5  -> Readiness.PEAK                  // Unusually high - might be good OR might indicate overcompensation
            else -> Readiness.GOOD
        }
        
        // Step 6: Store for trend tracking
        dbHelper.insertMorningHrv(
            timestamp = System.currentTimeMillis(),
            lnRmssd = lnRmssd,
            rmssd = rmssd,
            meanRR = cleanRR.average(),
            readiness = readiness
        )
        
        return ReadinessResult.Score(
            lnRmssd = lnRmssd,
            baselineMean = baselineMean,
            zScore = zScore,
            readiness = readiness,
            recommendation = getRecommendation(readiness)
        )
    }
    
    private fun filterArtifacts(rrIntervals: List<Int>): List<Int> {
        // Remove physiologically implausible intervals
        // and intervals that deviate >20% from local median
        val filtered = rrIntervals.filter { it in 300..2000 }
        val median = filtered.sorted()[filtered.size / 2]
        return filtered.filter { 
            abs(it - median) < median * 0.20 
        }
    }
    
    private fun getRecommendation(readiness: Readiness): String {
        return when (readiness) {
            Readiness.DELOAD_RECOMMENDED -> 
                "HRV significantly below baseline. Consider a rest day or light functional session."
            Readiness.LIGHT_DAY -> 
                "HRV moderately suppressed. Reduce volume or intensity by 20%."
            Readiness.NORMAL -> 
                "HRV within normal range. Proceed with planned workout."
            Readiness.GOOD -> 
                "HRV above baseline. Good day to push intensity."
            Readiness.PEAK -> 
                "HRV unusually high. Consider testing a PR or increasing volume."
        }
    }
}
```

### 7.5 Automatic Rep Counting via Accelerometer

**Complexity**: Medium-High
**Data needed**: Accelerometer stream (200 Hz)

```kotlin
class RepCounter {
    
    // The accelerometer sits on the chest
    // Different exercises produce different dominant axis patterns:
    //   Squat:      Y-axis (vertical) dominant, ~2-4 sec period
    //   Bench:      Z-axis (chest-to-back) dominant, shorter period
    //   Deadlift:   Y-axis dominant, distinct lift+lockout pattern
    //   OHP:        Y-axis dominant, overhead press pattern
    //   Row:        Z-axis or X-axis depending on grip
    
    private val buffer = CircularBuffer<AccSample>(400) // 2 seconds at 200Hz
    private var repCount = 0
    private var lastPeakTime = 0L
    
    // Minimum time between reps (prevents double-counting)
    private val minRepIntervalMs = 800L  // Max ~75 reps/min (plenty of headroom)
    
    fun onAccSample(timestamp: Long, x: Int, y: Int, z: Int) {
        buffer.add(AccSample(timestamp, x, y, z))
        
        if (buffer.isFull) {
            detectRep(timestamp)
        }
    }
    
    private fun detectRep(currentTime: Long) {
        // Step 1: Compute magnitude of acceleration vector
        // This makes the algorithm axis-agnostic (works regardless of strap rotation)
        val magnitudes = buffer.map { 
            sqrt((it.x * it.x + it.y * it.y + it.z * it.z).toDouble()) 
        }
        
        // Step 2: Apply low-pass filter (Butterworth, 2nd order, cutoff 3Hz)
        // Removes high-frequency noise while preserving rep motion (0.2-1 Hz)
        val filtered = lowPassFilter(magnitudes, cutoffHz = 3.0, sampleRate = 200.0)
        
        // Step 3: Peak detection
        // A rep = one complete cycle: eccentric + concentric
        // Detected as a local maximum in filtered acceleration magnitude
        val peaks = findPeaks(
            filtered, 
            minProminence = 200.0,  // Minimum peak height above neighbors (in mg)
            minDistance = (minRepIntervalMs * 200 / 1000).toInt()  // Min samples between peaks
        )
        
        // Step 4: Validate peak timing
        for (peak in peaks) {
            val peakTime = buffer[peak.index].timestamp
            if (peakTime - lastPeakTime > minRepIntervalMs * 1_000_000) { // ns
                repCount++
                lastPeakTime = peakTime
                onRepDetected(repCount)
            }
        }
    }
    
    fun resetForNewSet() {
        repCount = 0
        lastPeakTime = 0L
        buffer.clear()
    }
    
    // IMPROVEMENT PATH:
    // Phase 1: Simple peak detection (above)
    // Phase 2: Exercise-specific templates (matched filter per exercise)
    // Phase 3: ML classifier (random forest or small LSTM) trained on labeled sessions
    //          Features: axis ratios, peak shape, frequency, periodicity
    //          Training data: ~50 sessions per exercise type (your own data)
}
```

### 7.6 Tempo Under Tension (TUT)

**Complexity**: Medium
**Data needed**: Accelerometer stream (200 Hz)
**Depends on**: Rep counting (7.5) for rep boundary detection

```kotlin
class TempoAnalyzer {
    
    // For each detected rep, analyze the acceleration profile to extract:
    // - Eccentric phase duration (lowering)
    // - Pause at bottom
    // - Concentric phase duration (lifting)
    // - Pause at top
    // Result: "3-1-2-0" format (3s down, 1s pause, 2s up, 0s top)
    
    data class RepTempo(
        val eccentricMs: Long,
        val pauseBottomMs: Long,
        val concentricMs: Long,
        val pauseTopMs: Long
    ) {
        fun toDisplayString(): String {
            fun ms2s(ms: Long) = String.format("%.1f", ms / 1000.0)
            return "${ms2s(eccentricMs)}-${ms2s(pauseBottomMs)}-${ms2s(concentricMs)}-${ms2s(pauseTopMs)}"
        }
        
        val totalTUT: Long get() = eccentricMs + pauseBottomMs + concentricMs + pauseTopMs
    }
    
    fun analyzeRep(accSegment: List<AccSample>): RepTempo {
        // Extract Y-axis (vertical) acceleration, filtered
        val yFiltered = lowPassFilter(
            accSegment.map { it.y.toDouble() }, 
            cutoffHz = 2.0, 
            sampleRate = 200.0
        )
        
        // Velocity by integrating acceleration (remove gravity bias first)
        val gravityBias = yFiltered.average()
        val acceleration = yFiltered.map { it - gravityBias }
        val velocity = cumulativeIntegral(acceleration, dt = 1.0 / 200.0)
        
        // Phase detection:
        // Eccentric: velocity negative (moving down)
        // Concentric: velocity positive (moving up)
        // Pauses: velocity near zero (|v| < threshold)
        
        val velocityThreshold = velocity.maxAbsolute() * 0.05 // 5% of peak velocity
        
        // Find zero-crossings and near-zero regions
        val phases = segmentByVelocity(velocity, velocityThreshold, sampleRate = 200)
        
        return RepTempo(
            eccentricMs = phases.eccentricDurationMs,
            pauseBottomMs = phases.bottomPauseDurationMs,
            concentricMs = phases.concentricDurationMs,
            pauseTopMs = phases.topPauseDurationMs
        )
    }
    
    // UI: Show target tempo vs actual tempo per rep
    // Alert if deviation > 20% from target
    // Example: Target "3-1-2-0", actual "1.8-0.2-1.5-0"
    //          Alert: "Slow down the eccentric! Target 3s, you did 1.8s"
}
```

### 7.7 Intra-Set Fatigue Detection via HRV

**Complexity**: High
**Data needed**: ECG stream (for beat-by-beat RR during the set)

```kotlin
class FatigueDetector {
    
    // During a heavy set, sympathetic nervous system dominance increases
    // as fatigue accumulates. This manifests as:
    // 1. Decreasing RR interval variability (HRV drops)
    // 2. Increasing HR
    // 3. DFA alpha1 dropping below 0.5
    
    // DFA alpha1 (Detrended Fluctuation Analysis):
    // > 1.0 = correlated (rest, parasympathetic dominant)
    // 0.5-1.0 = mixed (moderate intensity)
    // < 0.5 = uncorrelated (high intensity, sympathetic dominant)
    
    private val rrBuffer = mutableListOf<Double>()
    
    fun onRRInterval(rrMs: Int) {
        rrBuffer.add(rrMs.toDouble())
        
        // Need minimum ~30 beats for meaningful DFA
        if (rrBuffer.size >= 30) {
            val alpha1 = calculateDFAalpha1(rrBuffer.takeLast(60))
            val fatigue = interpretFatigue(alpha1)
            updateFatigueUI(fatigue, alpha1)
        }
    }
    
    private fun calculateDFAalpha1(rrIntervals: List<Double>): Double {
        // DFA Algorithm:
        // 1. Integrate the RR time series (cumulative sum of deviations from mean)
        val mean = rrIntervals.average()
        val integrated = rrIntervals.runningFold(0.0) { acc, rr -> acc + (rr - mean) }
        
        // 2. Divide into windows of size n (use n = 4 to 16 for short-term alpha1)
        // 3. Fit linear trend in each window, calculate residual variance
        // 4. Plot log(fluctuation) vs log(window size)
        // 5. alpha1 = slope of the log-log plot
        
        val windowSizes = (4..16).toList()
        val fluctuations = windowSizes.map { n ->
            val numWindows = integrated.size / n
            if (numWindows < 2) return@map Double.NaN
            
            var totalVariance = 0.0
            for (w in 0 until numWindows) {
                val segment = integrated.subList(w * n, (w + 1) * n)
                val trend = linearFit(segment)
                val residuals = segment.mapIndexed { i, v -> (v - trend[i]).pow(2) }
                totalVariance += residuals.sum()
            }
            sqrt(totalVariance / (numWindows * n))
        }
        
        // Log-log regression
        val validPoints = windowSizes.zip(fluctuations)
            .filter { !it.second.isNaN() && it.second > 0 }
        
        if (validPoints.size < 3) return Double.NaN
        
        val logN = validPoints.map { ln(it.first.toDouble()) }
        val logF = validPoints.map { ln(it.second) }
        
        return linearRegressionSlope(logN, logF)
    }
    
    // Use case in the app:
    // Between sets, show: "Fatigue level: 72% (alpha1: 0.38)"
    // Compare current set's fatigue to previous sets
    // Suggest: "This set fatigued you 40% more than set 2. Consider reducing weight."
}
```

### 7.8 Passive Arrhythmia Screening

**Complexity**: High
**Data needed**: ECG stream (130 Hz)
**IMPORTANT DISCLAIMER**: This is NOT diagnostic. Must include clear medical disclaimer in UI.

```kotlin
class ArrhythmiaScreener {
    
    // Detects:
    // - PAC (Premature Atrial Contraction): short RR followed by long RR
    // - PVC (Premature Ventricular Contraction): wide QRS + compensatory pause
    // - Pauses: RR > 2.0 seconds
    // - Irregular rhythm (possible AFib): high RR variability without pattern
    
    data class EctopicEvent(
        val type: EctopicType,
        val timestamp: Long,
        val rrBefore: Int,    // RR interval before the event
        val rrDuring: Int,    // The short/long interval
        val rrAfter: Int      // RR interval after
    )
    
    enum class EctopicType { PAC, PVC, PAUSE, IRREGULAR }
    
    private val events = mutableListOf<EctopicEvent>()
    private val rrHistory = ArrayDeque<Pair<Long, Int>>(10) // (timestamp, rrMs)
    
    fun onRRInterval(timestamp: Long, rrMs: Int) {
        rrHistory.addLast(Pair(timestamp, rrMs))
        if (rrHistory.size > 10) rrHistory.removeFirst()
        if (rrHistory.size < 3) return
        
        val rrs = rrHistory.map { it.second }
        val localMedian = rrs.sorted()[rrs.size / 2]
        
        val current = rrs[rrs.size - 2]  // Check the previous beat (need context after)
        val before = rrs[rrs.size - 3]
        val after = rrs.last()
        
        // PAC detection: premature beat (RR < 85% of median) followed by compensatory pause
        if (current < localMedian * 0.85 && after > localMedian * 1.10) {
            events.add(EctopicEvent(
                EctopicType.PAC,
                rrHistory[rrHistory.size - 2].first,
                before, current, after
            ))
        }
        
        // Pause detection: RR > 2000ms
        if (current > 2000) {
            events.add(EctopicEvent(
                EctopicType.PAUSE,
                rrHistory[rrHistory.size - 2].first,
                before, current, after
            ))
        }
    }
    
    // For PVC detection from ECG waveform (requires QRS morphology analysis):
    fun onEcgSegment(samples: List<EcgSample>) {
        // Step 1: R-peak detection (Pan-Tompkins algorithm)
        val rPeaks = panTompkinsDetect(samples)
        
        // Step 2: For each QRS complex, measure:
        //   - QRS duration (normal: 80-120ms, wide: >120ms suggests PVC)
        //   - QRS amplitude
        //   - QRS morphology (template matching against normal beats)
        
        for (peak in rPeaks) {
            val qrsWidth = measureQRSWidth(samples, peak)
            if (qrsWidth > 120) { // ms
                // Wide QRS + premature timing = likely PVC
                // Flag for review
            }
        }
    }
    
    fun getSessionSummary(): ArrhythmiaSummary {
        return ArrhythmiaSummary(
            totalEctopicBeats = events.size,
            pacCount = events.count { it.type == EctopicType.PAC },
            pvcCount = events.count { it.type == EctopicType.PVC },
            pauseCount = events.count { it.type == EctopicType.PAUSE },
            // Clinical significance threshold: >5% ectopic burden during exercise
            ectopicBurdenPct = calculateEctopicBurden(),
            recommendation = getRecommendation()
        )
    }
    
    private fun getRecommendation(): String? {
        val burden = calculateEctopicBurden()
        return when {
            burden > 10.0 -> "High ectopic burden (${burden.roundToInt()}%). Consult a cardiologist."
            burden > 5.0 -> "Moderate ectopic burden. Consider mentioning to your doctor."
            events.any { it.type == EctopicType.PAUSE && it.rrDuring > 3000 } ->
                "Detected a pause > 3 seconds. This should be evaluated by a physician."
            else -> null  // No concerning findings
        }
    }
}

// PAN-TOMPKINS ALGORITHM (simplified implementation)
fun panTompkinsDetect(ecgSamples: List<EcgSample>): List<Int> {
    val sampleRate = 130.0
    val voltages = ecgSamples.map { it.voltage.toDouble() }
    
    // Step 1: Bandpass filter (5-15 Hz)
    val bandpassed = bandpassFilter(voltages, lowCut = 5.0, highCut = 15.0, fs = sampleRate)
    
    // Step 2: Derivative
    val derivative = bandpassed.windowed(5) { window ->
        (-window[0] - 2*window[1] + 2*window[3] + window[4]) / 8.0
    }
    
    // Step 3: Square
    val squared = derivative.map { it * it }
    
    // Step 4: Moving average integration (150ms window)
    val windowSize = (0.150 * sampleRate).toInt()
    val integrated = squared.windowed(windowSize) { it.average() }
    
    // Step 5: Adaptive threshold peak detection
    val peaks = mutableListOf<Int>()
    var threshold = integrated.max() * 0.3
    var lastPeakIndex = -1000
    val refractoryPeriod = (0.2 * sampleRate).toInt() // 200ms refractory
    
    for (i in 1 until integrated.size - 1) {
        if (integrated[i] > threshold &&
            integrated[i] > integrated[i-1] &&
            integrated[i] > integrated[i+1] &&
            i - lastPeakIndex > refractoryPeriod) {
            peaks.add(i)
            lastPeakIndex = i
            threshold = 0.75 * threshold + 0.25 * integrated[i] // Adaptive threshold
        }
    }
    
    return peaks
}
```

### 7.9 Cardiac Drift Detection

**Complexity**: Low
**Data needed**: HR stream (continuous, during steady-state cardio)

```kotlin
class CardiacDriftDetector {
    
    // During sustained exercise at constant intensity, HR gradually rises
    // due to: dehydration, thermal drift, reduced stroke volume
    // Rate of drift correlates with hydration status
    
    private val hrTimeSeries = mutableListOf<Pair<Long, Int>>() // (timestamp, hr)
    
    fun onHrUpdate(timestamp: Long, hr: Int) {
        hrTimeSeries.add(Pair(timestamp, hr))
        
        // Only analyze after 5+ minutes of data
        if (hrTimeSeries.size < 300) return // ~5 min at 1Hz
        
        // Calculate drift rate: BPM/min via linear regression
        val minutes = hrTimeSeries.map { (it.first - hrTimeSeries.first().first) / 60000.0 }
        val hrs = hrTimeSeries.map { it.second.toDouble() }
        
        val slope = linearRegressionSlope(minutes, hrs) // BPM per minute
        
        // Normal drift: <0.5 BPM/min
        // Moderate drift: 0.5-1.0 BPM/min (mild dehydration likely)
        // High drift: >1.0 BPM/min (significant dehydration or overheating)
        
        if (slope > 0.5 && hrTimeSeries.size > 600) { // 10+ min
            notifyUser(
                "Cardiac drift detected: +${String.format("%.1f", slope)} BPM/min. " +
                "Consider hydrating."
            )
        }
    }
}
```

### 7.10 VO2max Estimation from Submaximal Test

**Complexity**: Medium
**Data needed**: HR stream during a structured protocol

```kotlin
class VO2maxEstimator {
    
    // Åstrand-Ryhming nomogram method (submaximal)
    // Protocol: 6 minutes on stationary bike at constant load
    // Steady-state HR (last 2 minutes) + workload -> VO2max estimate
    
    // Alternative: step test protocol (more practical in gym setting)
    // Queens College Step Test:
    // Step up/down on a 16.25" bench at 24 steps/min (men) for 3 minutes
    // Measure recovery HR at 5-20 seconds post-test
    
    fun queensCollegeStepTest(
        recoveryHr: Int,  // HR at 5-20 seconds post-test
        isMale: Boolean
    ): Double {
        return if (isMale) {
            111.33 - (0.42 * recoveryHr)
        } else {
            65.81 - (0.1847 * recoveryHr)
        }
        // Result in ml/kg/min
    }
    
    // More accurate: Uth et al. (2004) formula using HRmax and HRrest
    // VO2max ≈ 15.3 × (HRmax / HRrest)
    // Requires: reliable HRmax (from max test or best observed) and
    //           resting HR (from morning measurement)
    
    fun uthEstimate(hrMax: Int, hrRest: Int): Double {
        return 15.3 * (hrMax.toDouble() / hrRest)
    }
    
    // Track VO2max over weeks/months as a fitness indicator
    // Increasing VO2max = cardiovascular adaptation to training
    // Decreasing VO2max = possible overtraining, detraining, or illness
}
```

### 7.11 Automatic Exercise Classification

**Complexity**: High
**Data needed**: Accelerometer stream (200 Hz)

```kotlin
class ExerciseClassifier {
    
    // Approach: extract features from 5-second sliding windows of accelerometer data
    // Train a lightweight ML model (Random Forest or k-NN) on labeled data
    
    // Phase 1: Rule-based classification (simple, no training needed)
    // Phase 2: ML-based classification (requires labeled data collection)
    
    // Feature extraction per 5-second window (1000 samples at 200Hz):
    data class AccFeatures(
        val meanX: Double, val meanY: Double, val meanZ: Double,
        val stdX: Double, val stdY: Double, val stdZ: Double,
        val dominantFreqX: Double, val dominantFreqY: Double, val dominantFreqZ: Double,
        val peakAmplitude: Double,
        val axisRatio: Double,     // ratio of dominant axis std to others
        val periodicity: Double    // autocorrelation at dominant frequency
    )
    
    fun extractFeatures(window: List<AccSample>): AccFeatures {
        val xs = window.map { it.x.toDouble() }
        val ys = window.map { it.y.toDouble() }
        val zs = window.map { it.z.toDouble() }
        
        // FFT for dominant frequency per axis
        val fftY = fft(ys)
        val dominantFreqY = findDominantFrequency(fftY, sampleRate = 200.0)
        
        // Periodicity: normalized autocorrelation at the dominant frequency lag
        val autoCorr = normalizedAutocorrelation(ys, lag = (200.0 / dominantFreqY).toInt())
        
        return AccFeatures(
            meanX = xs.average(), meanY = ys.average(), meanZ = zs.average(),
            stdX = xs.std(), stdY = ys.std(), stdZ = zs.std(),
            dominantFreqX = findDominantFrequency(fft(xs), 200.0),
            dominantFreqY = dominantFreqY,
            dominantFreqZ = findDominantFrequency(fft(zs), 200.0),
            peakAmplitude = maxOf(xs.max() - xs.min(), ys.max() - ys.min(), zs.max() - zs.min()),
            axisRatio = maxOf(xs.std(), ys.std(), zs.std()) / 
                        minOf(xs.std(), ys.std(), zs.std()).coerceAtLeast(0.001),
            periodicity = autoCorr
        )
    }
    
    // Rule-based classification (Phase 1):
    fun classifyRuleBased(features: AccFeatures): Exercise {
        return when {
            // Squat: high Y-axis variability, ~0.3-0.5 Hz (2-3 sec/rep), high periodicity
            features.stdY > features.stdX * 2 && 
            features.dominantFreqY in 0.2..0.6 &&
            features.periodicity > 0.7 -> Exercise.SQUAT
            
            // Bench press: high Z-axis variability (chest compression), higher freq
            features.stdZ > features.stdY * 1.5 &&
            features.dominantFreqZ in 0.3..0.8 -> Exercise.BENCH_PRESS
            
            // Deadlift: very high Y-axis peak amplitude, low frequency
            features.peakAmplitude > 2000 &&  // strong vertical motion
            features.dominantFreqY in 0.15..0.4 -> Exercise.DEADLIFT
            
            // OHP: Y-axis dominant, higher frequency than squat
            features.stdY > features.stdX * 2 &&
            features.dominantFreqY in 0.4..0.8 -> Exercise.OHP
            
            // Rest: low variability on all axes
            features.stdX < 100 && features.stdY < 100 && features.stdZ < 100 -> Exercise.REST
            
            else -> Exercise.UNKNOWN
        }
    }
    
    // ML classification (Phase 2):
    // 1. Collect 50+ labeled sessions using the app
    //    (user confirms or corrects the rule-based classification)
    // 2. Export features + labels as CSV
    // 3. Train Random Forest in Python (scikit-learn)
    // 4. Export model weights or use ONNX Runtime on Android
    // 5. Replace rule-based logic with ML inference
    //
    // Alternative: TensorFlow Lite with a small 1D-CNN on raw acceleration windows
    // Model size: <500KB, inference time: <10ms on modern phones
}
```

### 7.12 TRIMP & Training Load Quantification

**Complexity**: Medium
**Data needed**: HR stream (continuous throughout session)

```kotlin
class TrainingLoadTracker(
    private val userHrMax: Int,
    private val userHrRest: Int,
    private val isMale: Boolean,
    private val dbHelper: TrainingLoadDatabase
) {
    
    // TRIMP (Training Impulse) - Banister (1991)
    // Integrates exercise duration × intensity (HR-based)
    // Higher TRIMP = higher training stress
    
    fun calculateSessionTRIMP(hrTimeSeries: List<Pair<Long, Int>>): Double {
        if (hrTimeSeries.size < 2) return 0.0
        
        // Gender-specific weighting factor (exponential)
        val genderFactor = if (isMale) 1.92 else 1.67
        val genderExponent = if (isMale) 1.92 else 1.67
        
        var trimp = 0.0
        
        for (i in 1 until hrTimeSeries.size) {
            val durationMin = (hrTimeSeries[i].first - hrTimeSeries[i-1].first) / 60000.0
            val hr = hrTimeSeries[i].second
            
            // Heart rate reserve fraction
            val hrReserveFraction = (hr - userHrRest).toDouble() / (userHrMax - userHrRest)
            val clampedHRR = hrReserveFraction.coerceIn(0.0, 1.0)
            
            // Exponential weighting: higher intensities contribute disproportionately more
            trimp += durationMin * clampedHRR * 0.64 * exp(genderExponent * clampedHRR)
        }
        
        return trimp
    }
    
    // Zone-based TRIMP (simpler, Edwards method)
    fun calculateEdwardsTRIMP(hrTimeSeries: List<Pair<Long, Int>>): Double {
        var trimp = 0.0
        val zoneWeights = mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5)
        
        for (i in 1 until hrTimeSeries.size) {
            val durationMin = (hrTimeSeries[i].first - hrTimeSeries[i-1].first) / 60000.0
            val zone = getHRZone(hrTimeSeries[i].second, userHrMax)
            trimp += durationMin * (zoneWeights[zone] ?: 1)
        }
        
        return trimp
    }
    
    // ACUTE:CHRONIC WORKLOAD RATIO (ACWR)
    // Tracks injury/overtraining risk over weeks
    fun calculateACWR(): ACWRResult {
        val last28days = dbHelper.getTrimpLast28Days() // List of daily TRIMP values
        
        if (last28days.size < 28) return ACWRResult.InsufficientData
        
        // Acute load: last 7 days average
        val acuteLoad = last28days.takeLast(7).average()
        
        // Chronic load: last 28 days average
        val chronicLoad = last28days.average()
        
        val acwr = if (chronicLoad > 0) acuteLoad / chronicLoad else 0.0
        
        // Interpretation (Gabbett 2016):
        // 0.8 - 1.3 = "sweet spot" (optimal training load)
        // > 1.5 = high injury risk (acute spike)
        // < 0.8 = detraining
        
        val risk = when {
            acwr > 1.5 -> RiskLevel.HIGH    // "You've ramped up too fast this week"
            acwr > 1.3 -> RiskLevel.MODERATE // "Training load is above normal"
            acwr > 0.8 -> RiskLevel.OPTIMAL  // "Good balance"
            else -> RiskLevel.LOW            // "You may be detraining"
        }
        
        return ACWRResult.Calculated(acwr, acuteLoad, chronicLoad, risk)
    }
}
```

### 7.13 Respiratory Rate Estimation from ECG

**Complexity**: High
**Data needed**: ECG stream (130 Hz)

```kotlin
class RespiratoryRateEstimator {
    
    // ECG-Derived Respiration (EDR) methods:
    // 1. R-wave amplitude modulation: inspiration changes chest impedance,
    //    modulating QRS amplitude
    // 2. RR interval modulation: respiratory sinus arrhythmia (RSA) causes
    //    HR to increase during inspiration and decrease during expiration
    
    private val rPeakAmplitudes = mutableListOf<Pair<Long, Double>>() // (timestamp, amplitude)
    private val rrIntervals = mutableListOf<Pair<Long, Double>>() // (timestamp, rrMs)
    
    fun onRPeakDetected(timestamp: Long, amplitude: Double, rrMs: Double) {
        rPeakAmplitudes.add(Pair(timestamp, amplitude))
        rrIntervals.add(Pair(timestamp, rrMs))
        
        // Need at least 30 seconds of data
        if (rPeakAmplitudes.size < 30) return
        
        // Keep last 60 seconds
        val cutoff = timestamp - 60_000_000_000L // 60s in ns
        rPeakAmplitudes.removeAll { it.first < cutoff }
        rrIntervals.removeAll { it.first < cutoff }
        
        estimateRR()
    }
    
    private fun estimateRR() {
        // Method 1: FFT of R-wave amplitude series
        // Resample to uniform 4Hz (interpolate between R-peaks)
        val resampledAmplitudes = resampleToUniform(rPeakAmplitudes, targetHz = 4.0)
        val fftResult = fft(resampledAmplitudes)
        
        // Respiratory rate is typically 0.15 - 0.5 Hz (9-30 breaths/min)
        val respiratoryFreq = findPeakInRange(fftResult, lowHz = 0.15, highHz = 0.5, sampleRate = 4.0)
        val breathsPerMin = respiratoryFreq * 60.0
        
        // Method 2: FFT of RR interval series (respiratory sinus arrhythmia)
        val resampledRR = resampleToUniform(rrIntervals, targetHz = 4.0)
        val fftRR = fft(resampledRR)
        val respiratoryFreqRR = findPeakInRange(fftRR, lowHz = 0.15, highHz = 0.5, sampleRate = 4.0)
        val breathsPerMinRR = respiratoryFreqRR * 60.0
        
        // Combine both estimates (average if they agree within 20%, else use Method 1)
        val finalEstimate = if (abs(breathsPerMin - breathsPerMinRR) < breathsPerMin * 0.2) {
            (breathsPerMin + breathsPerMinRR) / 2.0
        } else {
            breathsPerMin
        }
        
        updateRespiratoryRateUI(finalEstimate.roundToInt())
    }
    
    // USE CASES:
    // 1. Valsalva detection during heavy lifts:
    //    Respiratory rate drops to ~0 during breath hold
    //    Excessive Valsalva duration (>5-6 seconds) can be flagged
    //
    // 2. Recovery breathing assessment:
    //    Faster return to low respiratory rate post-set = better recovery
    //
    // 3. Breathing pattern during cardio:
    //    Erratic or very rapid breathing (>35/min) may indicate
    //    exceeding ventilatory threshold
}
```

### 7.14 Autonomic Nervous System Balance Post-Session

**Complexity**: Medium
**Data needed**: ECG or RR intervals (3-minute post-workout recording)

```kotlin
class ANSBalanceAnalyzer {
    
    // Post-exercise HRV analysis in frequency domain
    // Measures how quickly the parasympathetic system reactivates
    
    fun analyzePostSession(rrIntervals: List<Int>): ANSResult {
        // Need 3 minutes of clean data (seated, quiet)
        val cleanRR = filterArtifacts(rrIntervals)
        if (cleanRR.size < 150) return ANSResult.InsufficientData
        
        // Frequency domain analysis via FFT on RR intervals
        // Resample RR to uniform 4Hz using cubic interpolation
        val uniformRR = cubicInterpolateToUniform(cleanRR, targetHz = 4.0)
        
        // Detrend (remove linear trend)
        val detrended = linearDetrend(uniformRR)
        
        // FFT
        val spectrum = powerSpectralDensity(detrended, sampleRate = 4.0)
        
        // Band powers:
        // VLF: 0.003 - 0.04 Hz (not meaningful in 3-min recording)
        // LF:  0.04 - 0.15 Hz  (mixed sympathetic + parasympathetic)
        // HF:  0.15 - 0.40 Hz  (parasympathetic / vagal tone)
        
        val lfPower = integrateBand(spectrum, 0.04, 0.15)
        val hfPower = integrateBand(spectrum, 0.15, 0.40)
        val lfHfRatio = lfPower / hfPower
        
        // Interpretation:
        // Low LF/HF ratio (<1.0): parasympathetic dominant (good recovery)
        // High LF/HF ratio (>2.0): sympathetic still dominant (incomplete recovery)
        // Track this over weeks: decreasing LF/HF post-workout = improving fitness
        
        return ANSResult.Analysis(
            lfPower = lfPower,
            hfPower = hfPower,
            lfHfRatio = lfHfRatio,
            rmssd = calculateRMSSD(cleanRR),
            interpretation = when {
                lfHfRatio < 1.0 -> "Excellent parasympathetic reactivation"
                lfHfRatio < 2.0 -> "Normal recovery"
                lfHfRatio < 4.0 -> "Slow recovery. Consider longer cooldown."
                else -> "Very slow recovery. You may be overtrained."
            }
        )
    }
}
```

### 7.15 Blood Pressure Surrogate (Experimental)

**Complexity**: Very High
**Data needed**: ECG (R-peak timestamp) + phone camera PPG (pulse arrival)

```kotlin
// EXPERIMENTAL - Pulse Transit Time (PTT) based BP estimation
// Requires: R-peak from ECG + pulse wave arrival from phone camera

class PulseTransitTimeEstimator {
    
    // Theory: Blood pressure ∝ 1/PTT²
    // PTT = time from ECG R-peak to pulse arrival at fingertip
    // Lower PTT = higher BP (stiffer arteries, faster wave propagation)
    
    // IMPORTANT: Requires one-time calibration with a real BP cuff
    // Can only track RELATIVE changes, not absolute values
    
    data class CalibrationPoint(
        val pttMs: Double,
        val systolicMmHg: Int,
        val diastolicMmHg: Int
    )
    
    private var calibration: List<CalibrationPoint>? = null
    
    fun calibrate(points: List<CalibrationPoint>) {
        // Need at least 2 calibration points (rest + post-exercise)
        // More points = better accuracy
        require(points.size >= 2) { "Need at least 2 calibration points" }
        calibration = points
    }
    
    fun estimateBP(currentPttMs: Double): BPEstimate? {
        val cal = calibration ?: return null
        
        // Linear regression: SBP = a * (1/PTT²) + b
        val inversePtt2 = cal.map { 1.0 / (it.pttMs * it.pttMs) }
        val sbps = cal.map { it.systolicMmHg.toDouble() }
        
        val (a, b) = linearRegression(inversePtt2, sbps)
        val estimatedSBP = a * (1.0 / (currentPttMs * currentPttMs)) + b
        
        return BPEstimate(
            systolic = estimatedSBP.roundToInt(),
            confidence = "Low - experimental feature",
            note = "Relative change from baseline: ${estimatedSBP - cal.first().systolicMmHg} mmHg"
        )
    }
    
    // IMPLEMENTATION NOTE:
    // Getting phone camera PPG to work reliably requires:
    // 1. Camera2 API to control flashlight as illumination
    // 2. Frame analysis of red channel intensity over the fingertip
    // 3. Peak detection on the PPG waveform
    // 4. Precise synchronization between ECG timestamp and camera frame timestamp
    // This is a significant engineering effort and should be treated as a v2+ feature
}
```

### 7.16 Overreaching Detection (Multi-Week)

**Complexity**: Medium
**Data needed**: Historical HRV data + training load data

```kotlin
class OverreachingDetector(private val db: HistoricalDatabase) {
    
    // Combines multiple signals to detect non-functional overreaching
    // Run weekly or when readiness score is persistently low
    
    data class WeeklySnapshot(
        val weekNumber: Int,
        val avgLnRmssd: Double,     // From morning HRV measurements
        val avgRestingHr: Double,   // From morning measurements
        val totalTrimp: Double,     // Sum of weekly training load
        val avgPerformance: Double  // Average weight × reps across key lifts
    )
    
    fun analyzeOverreaching(): OverreachingResult {
        val last8weeks = db.getWeeklySnapshots(8)
        if (last8weeks.size < 4) return OverreachingResult.InsufficientData
        
        // Signal 1: HRV trend (declining LnRMSSD = bad)
        val hrvTrend = linearRegressionSlope(
            last8weeks.map { it.weekNumber.toDouble() },
            last8weeks.map { it.avgLnRmssd }
        )
        
        // Signal 2: Resting HR trend (increasing = bad)
        val hrTrend = linearRegressionSlope(
            last8weeks.map { it.weekNumber.toDouble() },
            last8weeks.map { it.avgRestingHr }
        )
        
        // Signal 3: Performance trend (declining despite training = bad)
        val perfTrend = linearRegressionSlope(
            last8weeks.map { it.weekNumber.toDouble() },
            last8weeks.map { it.avgPerformance }
        )
        
        // Signal 4: Training load trend
        val loadTrend = linearRegressionSlope(
            last8weeks.map { it.weekNumber.toDouble() },
            last8weeks.map { it.totalTrimp }
        )
        
        // Scoring: each negative signal contributes to overreaching risk
        var riskScore = 0.0
        val flags = mutableListOf<String>()
        
        if (hrvTrend < -0.1) {
            riskScore += 0.3
            flags.add("HRV declining over ${last8weeks.size} weeks")
        }
        if (hrTrend > 0.5) { // >0.5 BPM/week increase
            riskScore += 0.2
            flags.add("Resting HR increasing")
        }
        if (perfTrend < 0 && loadTrend > 0) {
            riskScore += 0.4 // Training more but performing worse = classic overreaching
            flags.add("Performance declining despite increased training load")
        }
        if (last8weeks.takeLast(2).all { 
            it.avgLnRmssd < last8weeks.take(4).map { w -> w.avgLnRmssd }.average() - 
            last8weeks.take(4).map { w -> w.avgLnRmssd }.std() 
        }) {
            riskScore += 0.3
            flags.add("HRV persistently below baseline for 2+ weeks")
        }
        
        return when {
            riskScore >= 0.7 -> OverreachingResult.NonFunctional(
                riskScore, flags,
                "Strong signs of non-functional overreaching. Recommend 1 week deload: " +
                "reduce volume by 50%, maintain intensity. Focus on sleep and nutrition."
            )
            riskScore >= 0.4 -> OverreachingResult.Functional(
                riskScore, flags,
                "Possible functional overreaching. This can lead to supercompensation " +
                "if followed by adequate recovery. Consider a lighter week soon."
            )
            else -> OverreachingResult.Normal(riskScore, flags)
        }
    }
}
```

---

## 8. Dashboard UI: The "Set Screen"

### Layout During Active Set

```
┌──────────────────────────────────────┐
│ [Exercise: SQUAT]  Set 3/4           │
│                                      │
│         ❤️ 142 BPM                   │
│         Zone: CARDIO (78% HRmax)     │
│                                      │
│  ┌──────────────────────────────┐    │
│  │  ECG Waveform (scrolling)    │    │
│  │  ∧∧∧∧∧∧∧∧∧∧∧∧∧∧∧∧∧∧∧∧∧∧   │    │
│  └──────────────────────────────┘    │
│                                      │
│  Reps: 7  |  Tempo: 3.1-1.0-2.2-0   │
│  TUT: 44s |  Est. kcal: 12          │
│                                      │
│  [  FINISH SET  ]                    │
└──────────────────────────────────────┘
```

### Layout During Recovery (Between Sets)

```
┌──────────────────────────────────────┐
│ Recovery: Set 3 → Set 4              │
│                                      │
│    ❤️ 118 BPM  ↓ (dropping)          │
│    RMSSD: 24ms  ↑ (recovering)       │
│                                      │
│    ┌────────────────────────┐        │
│    │ 🔴 → 🟡 → 🟢            │        │
│    │   [=====>    ] 68%     │        │
│    │   Recovery progress     │        │
│    └────────────────────────┘        │
│                                      │
│    Time elapsed: 1:42                │
│    Estimated ready: ~0:30            │
│                                      │
│    Last set: 7 reps @ 100kg          │
│    Fatigue: moderate (α1: 0.42)      │
│                                      │
│  [  READY - START NEXT SET  ]        │
└──────────────────────────────────────┘
```

### Post-Session Summary Screen

```
┌──────────────────────────────────────┐
│ SESSION SUMMARY                      │
│ Pull Day - March 28, 2026            │
│                                      │
│ Duration: 62 min                     │
│ Avg HR: 128 BPM  |  Peak: 172 BPM   │
│ Calories: 485-630 kcal (estimated)   │
│ TRIMP: 187 (moderate-high)           │
│                                      │
│ ┌──────────────────────────────┐     │
│ │ HR over time (chart)         │     │
│ │ with set markers             │     │
│ └──────────────────────────────┘     │
│                                      │
│ Recovery metrics:                    │
│  Avg recovery time: 2:15            │
│  Fastest recovery: 1:30 (set 2→3)   │
│  Slowest recovery: 3:45 (set 5→6)   │
│                                      │
│ Cardiac notes:                       │
│  Ectopic beats: 3 PACs (normal)      │
│  Respiratory rate: 18-24 br/min      │
│  ANS balance: LF/HF 1.8 (normal)    │
│                                      │
│ Training load:                       │
│  ACWR: 1.12 (optimal range)         │
│  Weekly trend: ↑ 8% vs last week     │
│                                      │
│ [SAVE] [EXPORT] [SHARE]             │
└──────────────────────────────────────┘
```

---

## 9. Data Persistence & Export

### Database Schema (Room)

```kotlin
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val sessionType: String,  // "PPL_PULL", "PPL_PUSH", "PPL_LEG", "FUNCTIONAL"
    val trimpBanister: Double,
    val trimpEdwards: Double,
    val avgHr: Int,
    val maxHr: Int,
    val totalCalories: Double,
    val ectopicCount: Int,
    val avgRespiratoryRate: Double?,
    val postSessionLfHfRatio: Double?
)

@Entity(tableName = "sets")
data class SetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseName: String,
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double?,
    val peakHr: Int,
    val avgHr: Int,
    val recoveryTimeMs: Long,
    val recoveryRmssd: Double,
    val tempoString: String?,  // "3.1-1.0-2.2-0"
    val totalTutMs: Long?,
    val dfaAlpha1: Double?,
    val estimatedCalories: Double
)

@Entity(tableName = "morning_hrv")
data class MorningHrvEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val lnRmssd: Double,
    val rmssd: Double,
    val meanRR: Double,
    val restingHr: Int,
    val readinessScore: String  // "NORMAL", "DELOAD_RECOMMENDED", etc.
)

@Entity(tableName = "ecg_segments")
data class EcgSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startTimestamp: Long,
    val durationMs: Long,
    val sampleRate: Int,  // 130
    val voltages: ByteArray  // Compressed array of Int16 values (µV)
    // Store as compressed binary to save space
    // 130 Hz × 60 sec = 7800 samples × 2 bytes = 15.6 KB per minute
    // A 60-min session = ~936 KB uncompressed, ~200-300 KB compressed
)

// STORAGE ESTIMATE:
// Per session (60 min):
//   ECG raw: ~300 KB compressed
//   Accelerometer raw: ~2 MB compressed (200Hz × 3 axes × 60 min)
//   HR/RR: ~50 KB
//   Metadata: ~5 KB
// Total: ~2.5 MB per session
// 6 sessions/week × 52 weeks = ~780 MB/year
// Consider: only store ECG/ACC raw for the last N sessions,
//           keep derived metrics (HRV, TRIMP, etc.) forever
```

### Export Formats

```kotlin
// Export session data as:
// 1. CSV (for spreadsheet analysis)
// 2. JSON (for programmatic access)
// 3. FIT file (for Garmin Connect / Strava compatibility)
// 4. HRV data in Kubios-compatible format (for clinical analysis)

fun exportToCsv(session: SessionEntity, sets: List<SetEntity>): File {
    // Columns: timestamp, hr, rr_intervals, exercise, set_number, reps, weight, etc.
}

fun exportEcgToEdf(ecgSegment: EcgSegmentEntity): File {
    // European Data Format - standard for physiological signals
    // Compatible with most ECG analysis software
}
```

---

## 10. Testing Strategy

### Without Wearing the Strap

```kotlin
// Use an ESP32 BLE HR simulator for automated testing
// Project: github.com/ondrejhanak/blehr-sim
// Broadcasts configurable HR values via standard BLE 0x180D service

// For Polar SDK-specific features, use the Polar SDK's test utilities
// or create a mock PolarBleApi implementation:

class MockPolarBleApi : PolarBleApi {
    
    private val ecgSubject = PublishSubject.create<PolarEcgData>()
    
    override fun startEcgStreaming(deviceId: String): Flowable<PolarEcgData> {
        return ecgSubject.toFlowable(BackpressureStrategy.BUFFER)
    }
    
    // Simulate ECG data from a file (MIT-BIH Arrhythmia Database)
    fun simulateEcgFromFile(filePath: String) {
        // Read pre-recorded ECG data and emit samples at 130Hz
        // MIT-BIH provides annotated ECG with known arrhythmias
        // Perfect for testing arrhythmia detection
    }
    
    // Simulate specific scenarios
    fun simulateHighHR(bpm: Int) { /* ... */ }
    fun simulateDropout() { /* ... */ }
    fun simulatePVC() { /* ... */ }
}
```

### Testing Checklist

```
Connection:
[ ] Device discovery works with strap on skin
[ ] Connection survives screen-off (foreground service)
[ ] Reconnection works after Bluetooth toggle
[ ] Graceful handling of out-of-range disconnection
[ ] Battery level reading and low-battery warning
[ ] Multiple phone BLE connections don't conflict

Data Quality:
[ ] ECG waveform displays correctly (no inverted QRS)
[ ] RR intervals are physiologically plausible (300-2000ms)
[ ] First 60-90 seconds flagged as "warming up" if contact is poor
[ ] Artifact filtering doesn't remove valid beats during exercise

Features:
[ ] Recovery timer transitions green at appropriate HR/HRV levels
[ ] Rep counter accuracy >85% for compound lifts (squat, bench, deadlift)
[ ] TRIMP calculation matches manual calculation from HR export
[ ] Arrhythmia detection flags known PVCs in test data
[ ] Readiness score correlates with subjective fatigue over 2+ weeks

Performance:
[ ] ECG rendering at 130Hz doesn't drop frames
[ ] All real-time calculations complete within 100ms
[ ] Database writes don't block UI thread
[ ] Battery drain on phone acceptable for 90-min session (<15%)
```

---

## Implementation Priority

Recommended build order for incremental development:

| Phase | Features | Timeline |
|-------|----------|----------|
| **1** | BLE connection + standard HR display + zone coloring | Week 1 |
| **2** | Polar SDK integration + ECG waveform display | Week 2 |
| **3** | Smart recovery timer (HR + RMSSD) | Week 3 |
| **4** | Calorie estimation + session TRIMP | Week 3-4 |
| **5** | Rep counting via accelerometer | Week 4-5 |
| **6** | Morning HRV readiness score | Week 5 |
| **7** | TUT analysis | Week 6 |
| **8** | Arrhythmia screening | Week 7-8 |
| **9** | Respiratory rate estimation | Week 8 |
| **10** | Exercise classification (rule-based) | Week 9 |
| **11** | ACWR + overreaching detection | Week 10 |
| **12** | ANS balance post-session | Week 10 |
| **13** | Cardiac drift detection | Week 11 |
| **14** | VO2max estimation | Week 11 |
| **15** | Exercise classification (ML) | Week 12+ |
| **16** | Blood pressure surrogate | Future/experimental |
