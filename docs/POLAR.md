# Polar H10 subsystem

MyGymApp integrates with a Polar H10 chest strap to record real-time heart rate, ECG, HRV, recovery, and training-load metrics. This document describes how the pieces fit together; the step-by-step development guide with formulas and references lives in [polar/implementation-guide.md](polar/implementation-guide.md).

## Components

```
data/polar/
├── PolarManager.kt         — BLE lifecycle + StateFlow facade for the whole subsystem
├── EcgRecorder.kt          — Writes raw ECG samples to disk
├── EcgAnalyzer.kt          — Post-session analysis of a recorded file (Pan-Tompkins + arrhythmia)
├── LiveEcgAnalyzer.kt      — Incremental analyzer that updates while streaming
├── UserProfile.kt          — Age / weight / sex (for Keytel, TRIMP, VO2max)
└── (CardioTrendLoader.kt lives under data/repository/)

ui/service/
└── PolarStreamingService.kt  — Foreground service (connectedDevice type)

ui/screen/heartrate/           — Heart rate screen (pairing + live metrics + trends)
ui/components/HeartRateBar.kt  — In-workout HR + recovery semaphore
ui/components/LiveEcgCard.kt   — Live ECG waveform + beat counter
ui/components/CardioTrendSection.kt — 4-week cardio self-diagnosis card
```

## PolarManager: the facade

[PolarManager](../app/src/main/java/com/mygymapp/data/polar/PolarManager.kt) is the only thing the rest of the app touches. It owns the `PolarBleApi` instance, exposes a read-only view of every derived metric as a `StateFlow`, and encapsulates all the RxJava plumbing.

### Exposed state

| StateFlow | Type | Updated on | Consumed by |
|---|---|---|---|
| `connectionState` | `DISCONNECTED` / `CONNECTING` / `CONNECTED` | SDK callbacks | Heart rate screen badge |
| `discoveredDevices` | `List<PolarDeviceInfo>` | During scan | Device picker |
| `isScanning` | `Boolean` | `startScan` / `stopScan` | Spinner |
| `heartRate` | `Int?` | Every HR sample | HeartRateBar, status-bar notification |
| `batteryLevel` | `Int?` | SDK callback | UI indicator |
| `recoveryState` | `RECOVERING` / `ALMOST_READY` / `READY` | Every HR sample | Recovery semaphore |
| `rmssd` | `Double?` | During recovery tracking | (not currently displayed — kept for future recovery card) |
| `sessionCalories` | `Double` | Every HR sample (Keytel) | HR bar + session save |
| `sessionTrimp` | `Double` | Every HR sample (Banister) | HR bar + session save |
| `liveHrrLast` | `Int?` | 60s after each detected peak | (not currently displayed — LiveEcgCard intentionally hides live HRR; per-set values are persisted post-session as `hrrPerSet`) |
| `readinessResult` | `ReadinessResult` | 60s measurement + baseline | HR screen readiness card + session save |
| `vo2max` | `Double?` | End of readiness (Uth) | HR screen + session save |
| `ecgWaveform` | `IntArray` (~520 samples) | Every ECG block (~100 ms) | LiveEcgCard canvas |
| `liveEcgSnapshot` | beats/regular%/PAC/pause/irregular | Every ECG block | LiveEcgCard counters |
| `liveCardiacDrift` | `Double` (BPM / min) | Every 30s of HR history | LiveEcgCard drift line |

All flows are hot and survive the `HeartRateViewModel` lifecycle — they live on the `@Singleton` manager, not on the VM.

## Lifecycle flows

### Scan → connect → session

```
startScan()  ──► api.searchForDevice()  ──► _discoveredDevices updates
                                          │
user taps device                          │
     │                                    │
     ▼                                    │
connectToDevice(id)  ─► SDK callback deviceConnected()
                              │
                              ├─► reset sessionCalories / sessionTrimp
                              ├─► startReadinessMeasurement() (60s)
                              ├─► PolarStreamingService.start() (foreground)
                              └─► startHrStreaming(id)
```

### HRV readiness (60s after connect)

1. RR intervals accumulate from each `PolarHrData` sample.
2. Min HR is tracked as the session resting HR estimate.
3. At 60s:
   - Artifacts (Δ > 20% from median) are filtered out.
   - LnRMSSD is computed over the remaining RR intervals.
   - Today's LnRMSSD is appended to the rolling 14-day baseline stored in `SharedPreferences("hrv_baseline")`.
   - Z-score vs baseline → one of `DELOAD_RECOMMENDED` / `LIGHT_DAY` / `NORMAL` / `GOOD` / `PEAK`.
   - First 7 days show `NO_BASELINE` until enough data exists.
4. Today's HRrest is appended to a rolling 7-reading baseline in `SharedPreferences("hrv_baseline")` under key `hrrest_values`.
5. VO2max is estimated via the Uth-Sørensen-Overgaard formula using `HRmax` (Tanaka) and `min(HRrest)` over the last 7 readings (falls back to today's value when the baseline is shorter). Using the 7-reading minimum reduces day-to-day noise (caffeine, sleep, stress) vs. picking a single session's value.

Formula details and references in [polar/implementation-guide.md](polar/implementation-guide.md).

### ECG streaming

ECG follows a separate stream (`FEATURE_POLAR_ONLINE_STREAMING`), requested on top of HR.

```
ActiveRoutineViewModel.init
    │
    └─► startEcgRecording(sessionId)
            │
            ├─ feature ready?  yes ─► startEcgStreamingInternal(deviceId, sessionId)
            │                          ├─ LiveEcgAnalyzer.reset()
            │                          ├─ EcgRecorder.start(sessionId)
            │                          ├─ request max settings (130 Hz on H10)
            │                          └─ subscribe → for each sample:
            │                                 recorder.write(µV)
            │                                 liveAnalyzer.onSample(µV)
            │                                 waveformBuffer += µV (cap 520)
            │                                 every ~100ms emit _ecgWaveform + _liveEcgSnapshot
            │
            └─ feature not ready?  queue in pendingEcgSessionId, start on SDK ready callback
```

Stream errors trigger an auto-restart via `ecgRestartHandler` with a ≤2 s back-off. The restart is skipped if the device has disconnected or the session is no longer active.

### Post-session analysis

On session completion (or abandon), `ActiveRoutineViewModel`:

1. Calls `PolarManager.stopEcgRecording()` → `EcgRecorder.close()`.
2. Calls `EcgAnalyzer.analyze(sessionId)` off the main thread.
   - Pan-Tompkins QRS detector → beat times.
   - RR intervals → RMSSD, SDNN, pNN50, Poincaré SD1/SD2/ratio.
   - Arrhythmia markers: PAC, pauses, irregular beats, AFib suspicion episodes.
3. Writes the 14 derived metrics into the session's YAML frontmatter.
4. Deletes `ecg/{sessionId}.ecg` — the raw waveform is not retained long-term.

### HRR (heart rate recovery)

PolarManager watches a rolling window for automatic peak detection:

1. Rolling 8-sample HR window detects a rising → falling transition.
2. Peaks qualify only if at least `PEAK_MIN_RISE_BPM` (15) above the current resting HR.
3. Qualified peaks are queued as `(peakHr, timestamp)`.
4. 60 s after each peak: `delta = peakHr − currentHr` is appended to `hrrDeltas` and emitted via `_liveHrrLast`.
5. Session average is saved as `hrr60s` in the session file; the full ordered list (one value per detected peak/set) is saved as `hrrPerSet` via `PolarManager.hrrDeltasSnapshot()`.

### Cardiac drift

`startHrSeriesCapture()` is called by `ActiveRoutineViewModel.init` and appends every HR sample as `(elapsedMs, hr)` into an `ArrayDeque` capped at `HR_SERIES_MAX_ENTRIES = 28 800` (~8 h at 1 Hz). The cap is a safety net against lifecycle bugs that would otherwise let the series grow unbounded — real sessions are orders of magnitude shorter. Every 30 s, a linear regression on the series gives BPM/minute (positive = drift up, a proxy for dehydration / early fatigue). Requires ≥5 min and ≥60 samples before the regression runs.

### Calories & TRIMP

Accumulated on every HR sample:

- **Keytel** for calorie estimation — gender-specific formula using HR, weight, age; integrated over time since the previous sample.
- **Banister TRIMP** — `duration × HRR_fraction × exp(k × HRR_fraction)`, with `k` gender-adjusted.

Both reset to 0 on connect, saved in the session on completion.

## Configuration & persistence

- **User profile** (`SharedPreferences("user_profile")`): `age` (Int), `weightKg` (Float), `isMale` (Boolean). Must be set before Keytel / VO2max give sensible numbers. See [UserProfile.kt](../app/src/main/java/com/mygymapp/data/polar/UserProfile.kt).
- **HRV baseline** (`SharedPreferences("hrv_baseline")`): CSV of ≤14 daily LnRMSSD values; oldest trimmed first.
- **ECG files** are ephemeral — see [STORAGE.md](STORAGE.md#raw-ecg-ecgsessionidecg).

## Permissions & services

Manifest ([AndroidManifest.xml](../app/src/main/AndroidManifest.xml)):

- `BLUETOOTH_SCAN` with `neverForLocation` + `BLUETOOTH_CONNECT` — runtime permissions on API ≥ 31.
- Legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_FINE_LOCATION` with `maxSdkVersion="30"` for Android 8–11.
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` — required on API ≥ 34 for `PolarStreamingService`.

[PolarStreamingService](../app/src/main/java/com/mygymapp/ui/service/PolarStreamingService.kt) keeps a persistent notification alive while a device is connected, so the BLE connection and HR logging survive screen-off. Notification text is updated by broadcasting an intent — no Binder.

## UI integration

- **HeartRateScreen** wires ~9 PolarManager flows into a single UI state via `combine(...)` in `HeartRateViewModel`. It handles pairing, readiness display, VO2max, cardio-trend cards (from [CardioTrendLoader](../app/src/main/java/com/mygymapp/data/repository/CardioTrendLoader.kt)) and self-diagnosis.
- **HeartRateBar** is rendered at the top of every exercise screen. It auto-hides when the device isn't connected.
- **LiveEcgCard** is shown only during an active session (on HeartRateScreen only if a session is running). The waveform is a custom Canvas fed by `ecgWaveform`.

## Testing

Manual checklist: [polar/testing-checklist.md](polar/testing-checklist.md).

## Further reading

- [polar/implementation-guide.md](polar/implementation-guide.md) — research notes + formulas (~2000 lines)
- [polar/testing-checklist.md](polar/testing-checklist.md) — session checklist
