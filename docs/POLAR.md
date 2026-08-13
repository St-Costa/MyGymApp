# Polar H10 subsystem

MyGymApp integrates with a Polar H10 chest strap to record real-time heart rate, ECG, HRV, recovery, and training-load metrics. This document describes how the pieces fit together; the step-by-step development guide with formulas and references lives in [polar/implementation-guide.md](polar/implementation-guide.md).

## Components

```
data/polar/
├── PolarManager.kt         — BLE lifecycle + StateFlow facade for the whole subsystem
├── EcgRecorder.kt          — Writes raw ECG samples to disk
├── EcgAnalyzer.kt          — Post-session analysis of a recorded file (Pan-Tompkins + arrhythmia)
├── LiveEcgAnalyzer.kt      — Incremental analyzer that updates while streaming
├── UserProfile.kt          — Birth year / weight / sex / height (for Keytel, TRIMP, VO2max, HR zones)
├── HrZoneCalculator.kt     — Live %HRR (Karvonen) Z1-Z5 zone boundaries + max-HR estimate
├── HrZoneTracker.kt        — In-memory per-session time-in-zone accumulator
└── (CardioTrendLoader.kt lives under data/repository/)

ui/service/
└── PolarStreamingService.kt  — Foreground service (connectedDevice type)

ui/screen/heartrate/           — Heart rate screen (pairing + live metrics + trends)
ui/components/HeartRateBar.kt  — In-workout HR + TRIMP + zone chip (no calories, no recovery semaphore)
ui/components/HrZoneTraceChart.kt — Live ~90s %HRR trace over proportional zone bands
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
| `rmssd` | `Double?` | During recovery tracking | HR bar secondary line |
| `sessionCalories` | `Double` | Every HR sample (Keytel) | Session summary + session save (not shown during exercise execution) |
| `sessionTrimp` | `Double` | Every HR sample (Banister) | HR bar + session save |
| `liveHrrLast` | `Int?` | 60s after each detected peak | HR bar HRR badge |
| `readinessResult` | `ReadinessResult` | 60s measurement + baseline | HR screen readiness card |
| `vo2max` | `Double?` | End of readiness (Uth) | HR screen + session save |
| `ecgWaveform` | `IntArray` (~520 samples) | Every ECG block (~100 ms) | LiveEcgCard canvas |
| `liveEcgSnapshot` | beats/regular%/PAC/pause/irregular | Every ECG block | LiveEcgCard counters |
| `liveCardiacDrift` | `Double` (BPM / min) | Every 30s of HR history | LiveEcgCard drift line |
| `currentHrZone` | `HrZone?` | Every HR sample (once resolved) | HeartRateBar zone chip |
| `currentHrZonePercent` | `Int` | Every HR sample | HeartRateBar zone chip ("Z3 · 74%") |
| `hrZoneMinutes` | `HrZoneMinutes` | Every HR sample | HeartRateBar time-in-zone bar |
| `hrZoneTracePercents` | `List<Int>` (~90s of %HRR) | Every HR sample | HrZoneTraceChart (routine + cardio screens) |

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

The automatic on-connect measurement is gated by `PolarManager.maybeStartAutoReadinessMeasurement()`:
it only starts before **10:00 local time**, and only if no readiness event has been persisted
yet **today** (`ReadinessRepository.getLatestForDate()`) — so disconnecting and reconnecting the
strap later the same day reuses today's earlier result (loaded back into `readinessResult`/
`vo2max`/`restingHr`) instead of re-measuring. Outside the time window with no prior measurement,
`readinessResult` simply stays at its default (`MEASURING`/never-run) until the next connect that
qualifies. This gating only applies to the automatic first-connect trigger; a mid-session
reconnect never re-measures regardless of time (see `hrSeriesActive` check below).

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

Deep ECG analysis (Pan-Tompkins QRS detection, RMSSD/SDNN/pNN50/Poincaré, arrhythmia
markers) no longer runs on the phone at all — it moved server-side (see
[SYNC.md](SYNC.md#fourth-record-type-raw-ecg)). `EcgAnalyzer`/`PolarManager.analyzeSessionEcg()`
still exist in the codebase (harmless, unused) but `ActiveRoutineViewModel` no longer calls
them. The phone only computes and shows two metrics that are cheap and don't require the
recorded waveform at all: **resting HR** and **VO2max**, both derived from live HR/readiness
tracking during the session (see "HRV readiness" above), not from the `.ecg` file. TRIMP and
kcal (Banister/Keytel) also keep being computed continuously during the session and shown,
unchanged. Cardiac drift and HRR (below) are cheap HR-series computations, not deep
waveform analysis, and also keep running locally.

On session completion (or abandon), `ActiveRoutineViewModel`:

1. Calls `PolarManager.stopEcgRecording()` → `EcgRecorder.close()`.
2. Saves `restingHr` (`PolarManager.sessionRestingHr()`), `hrr60s`
   (`PolarManager.averageHrr60s()`), and `cardiacDriftBpmMin`
   (`PolarManager.cardiacDriftBpmPerMinute()`) into the session's YAML frontmatter.
   `vo2max`/`sessionCalories`/`sessionTrimp` are saved earlier, in `finalizeSession()`.
3. Raw file handling depends on whether server sync is configured (see
   [SYNC.md](SYNC.md#fourth-record-type-raw-ecg)):
   - **Sync configured + enabled**: the raw `ecg/{sessionId}.ecg` file is queued in
     `EcgSyncLedgerRepository` and uploaded (gzip-compressed) to the server, which runs the
     deep analysis described above — more CPU than a phone, potentially ML/LLM-assisted
     interpretation, and comparison against the user's full history rather than one
     isolated session. Results live server-side only (`MyGymApp_server`); the phone never
     receives them back. The local file is deleted only once `EcgSyncWorker` confirms the
     upload succeeded. If the server stays unreachable for 30 days, the pending entry
     expires and the file is deleted anyway (`EcgSyncLedgerRepository.expireStale()`) to
     bound local storage growth — that session's raw waveform is then unrecoverable, with
     no local-analysis fallback of any kind (the deep metrics no longer exist on the phone
     at all).
   - **Sync not configured/enabled**: the raw waveform is deleted immediately — with no
     local analysis and no server to send it to, nothing on the phone would ever consume
     it.

### Cardio blocks

`ExerciseType.CARDIO` (`ui/screen/cardioexercise/CardioExerciseViewModel.kt`) lets a session
mark "this part is cardio" explicitly instead of the whole session being one undifferentiated
HR stream. A cardio exercise (e.g. "Corsa leggera", "Bici" — catalog entries like any other
`Exercise`, created via the normal exercise editor) has no pre-configured sets: instead,
`CardioExerciseScreen` shows "Inizia cardio" / "Termina cardio", and each press cycle appends
one `ExerciseSet.Cardio` block (`startedAt`/`endedAt`/`avgHr`/`maxHr`) to that exercise's
`sets` — see [STORAGE.md](STORAGE.md#workout-session-historyyyyymmyyyy-mm-dd_routineid_sessionidmd)
for the YAML shape. Several blocks are supported per session (different cardio types, or the
same one resumed later after e.g. stretching in between).

- **HR tracking**: `CardioExerciseViewModel` collects `PolarManager.heartRate` while a block
  is running and accumulates avg/max locally, in the ViewModel — not in `PolarManager` itself,
  since a block is a concept of the exercise/session, not of the Polar subsystem. No new
  `PolarManager` methods were needed for this: it reads `heartRate` the same way `HeartRateBar`
  already does.
- **Configured-duration countdown, not a count-up stopwatch**: `RoutineExercise
  .timePerSetSeconds` is repurposed for CARDIO as a single total block duration (set in
  `RoutineEditScreen`'s "Durata cardio" picker, minutes), fetched by `CardioExerciseViewModel`
  from the owning routine the same way `SupersetViewModel` fetches rep ranges (including the
  fixed-daily-routine special case). `startBlock()` counts *down* from that value once
  every second and **keeps going past zero into negative/overtime** rather than
  auto-stopping — the countdown is a pacing aid, not an enforced cutoff; only an explicit
  "Termina cardio" tap ends the block (`stopBlock()`). If the routine exercise has no
  configured duration (`timePerSetSeconds == 0`), the countdown just starts at 0 and
  free-runs into overtime immediately — no separate count-up mode.
- **Duration is set per routine only**, in `RoutineEditScreen`'s "Durata cardio" picker —
  no catalog-level default on `Exercise` itself (tried and reverted; see CHANGELOG.md).
  Long-press either +/− button to step by 10 minutes at once (`ScrollPickerInput`'s
  `longPressRepeatStep`, same widget/behavior as the weight picker on strength sets).
- **Never left half-open**: an `ExerciseSet.Cardio` with a blank `endedAt` is never persisted
  against an exercise marked `completed`. `completeExercise()` force-closes a still-running
  block before saving. A block left running because the app died mid-session (not a clean
  "Termina cardio") is instead closed silently the next time the screen opens
  (`CardioExerciseViewModel.init`), same "don't leave inconsistent state lying around" spirit
  as the ghost-session guard (see [CONVENTIONS.md](CONVENTIONS.md#ghost-session-prevention)).
- **Tonnage**: `excludeFromTonnage` is always `true` for CARDIO, regardless of section
  (warmup/daily/normal) — cardio work never contributes to tonnage math.
- **No superset**: a cardio exercise can never be linked into a superset —
  `RoutineEditScreen`'s "Superset" button never shows for one (superset pairing is
  strictly FORZA/STRETCH; `SupersetViewModel`/`SupersetScreen` only know how to interleave
  reps/weight or timeSeconds/done).
- **History**: `CardioExerciseScreen` shows a small panel of past sessions for the same
  `exerciseId`, reusing `WorkoutRepository.getSessionsForExercise()` unchanged (no new
  repository) — aggregated inline (summed block duration, averaged `avgHr`, maxed `maxHr`
  across blocks), the same "repository exposes raw data, ViewModel aggregates inline" pattern
  used throughout the app.
- **ECG correlation, no binary format change**: the raw `.ecg` file stays one continuous
  stream per session, exactly as before — `EcgRecorder`/`startEcgRecording`/
  `stopEcgRecording` are untouched. The sync server instead derives cardio-block sample
  ranges after the fact, by combining the block's absolute `startedAt`/`endedAt` (from the
  synced session YAML, `/v1/sessions`) with the `.ecg` file's own `startTimestamp`+
  `sampleRate` header (from `/v1/ecg`) for the same `sessionId` — see
  [SYNC.md](SYNC.md#fourth-record-type-raw-ecg).

### HRR (heart rate recovery)

PolarManager watches a rolling window for automatic peak detection:

1. Rolling 8-sample HR window detects a rising → falling transition.
2. Peaks qualify only if at least `PEAK_MIN_RISE_BPM` (15) above the current resting HR.
3. Qualified peaks are queued as `(peakHr, timestamp)`.
4. 60 s after each peak: `delta = peakHr − currentHr` is appended to `hrrDeltas` and emitted via `_liveHrrLast`.
5. Session average is saved as `hrr60s` in the session file.

### Cardiac drift

`startHrSeriesCapture()` is called by `ActiveRoutineViewModel.init` and appends every HR sample as `(elapsedMs, hr)` into an `ArrayDeque` capped at `HR_SERIES_MAX_ENTRIES = 28 800` (~8 h at 1 Hz). The cap is a safety net against lifecycle bugs that would otherwise let the series grow unbounded — real sessions are orders of magnitude shorter. Every 30 s, a linear regression on the series gives BPM/minute (positive = drift up, a proxy for dehydration / early fatigue). Requires ≥5 min and ≥60 samples before the regression runs.

### Live HR-zone widget

Pure on-device, real-time counterpart to the sync server's retrospective
`compute_hr_zone_minutes` (see [SYNC.md](SYNC.md) and the server repo's
`app/ecg_analysis.py`/`ECG_ADVANCED_ANALYSIS.md` §6) — no server round-trip, computed
entirely from data already local: birth year, resting HR from readiness, and the
live BPM stream.

- **Zone math**: `HrZoneCalculator` — %HRR (Karvonen), same formula the server uses.
  `max_hr` = unweighted mean of Fox/Tanaka/Gulati age-based formulas, computed from
  `UserProfile.effectiveAge` — recomputed from `UserProfile.birthYear` each time (falls back
  to a fixed default age until birth year is set — there is no separate manually-entered age
  field) — never derived from the phone's own peak-HR data (see the design doc for why:
  a resistance-training peak isn't a controlled maximal test). `resting_hr` = today's
  readiness measurement if one exists, else a 7-day trailing average
  (`ReadinessRepository.getRecentAverageRestingHr()`), else `null` — with no resting HR
  at all, `HrZoneCalculator` falls back to plain %HRmax rather than blocking the widget.
- **Accumulation**: `HrZoneTracker`, one instance per `PolarManager`, reset in
  `startHrSeriesCapture()` alongside the other per-session counters. On every HR sample,
  attributes the elapsed time since the previous tick to the *previous* tick's zone (same
  coarse-but-standard approach as the server's retrospective version). In-memory only —
  not persisted; the server-side ECG-derived analysis after sync remains the durable
  historical record.
- **Resolution timing**: `resolveHrZoneCalculator()` runs on `readinessScope` (fire-and-
  forget, same scope used for readiness persistence) when a session starts, since reading
  readiness history is suspendable IO. `currentHrZone` stays `null` until it completes —
  typically sub-second.
- **UI**: `HeartRateBar` shows a small colored zone chip ("Z3 · 74%") plus a thin stacked
  bar of per-zone minutes so far, using the same zone palette as the server dashboard's
  "Time in HR zone" chart (see `hrZoneColor` in `HeartRateBar.kt`). Auto-hides along with
  the rest of the bar when Polar isn't connected.

### Calories & TRIMP

Accumulated on every HR sample:

- **Keytel** for calorie estimation — gender-specific formula using HR, weight, and `UserProfile.effectiveAge` (derived from birth year); integrated over time since the previous sample.
- **Banister TRIMP** — `duration × HRR_fraction × exp(k × HRR_fraction)`, with `k` gender-adjusted.

Both reset to 0 on connect, saved in the session on completion.

## Configuration & persistence

- **User profile** (`SharedPreferences("user_profile")`): `birthYear` (Int, absent if unset — the sole source of age via `UserProfile.effectiveAge`, no separate age field), `weightKg` (Float), `isMale` (Boolean), `heightCm` (Int). Set `birthYear` before Keytel / TRIMP / VO2max / BIA body-fat % give sensible numbers — until then `effectiveAge` falls back to a fixed default (30) so formulas never crash, they just use a placeholder. See [UserProfile.kt](../app/src/main/java/com/mygymapp/data/polar/UserProfile.kt). `weightKg` is written only by the VitaFit scale integration (`BleScaleManager.maybeSaveWeighIn()`) — no manual entry exists. `PolarManager.userProfile` reads `UserProfileRepository.get()` fresh on every access (cheap — SharedPreferences is already in-memory-cached after the first read) rather than keeping a manually-synced cached copy, so Keytel calories always reflect the most recent scale weigh-in regardless of which screen is open when it happens.
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
- **HeartRateBar** is rendered at the top of every exercise screen. It auto-hides when the device isn't connected. Also shows the live HR-zone chip + time-in-zone bar (see above).
- **LiveEcgCard** is shown only during an active session (on HeartRateScreen only if a session is running). The waveform is a custom Canvas fed by `ecgWaveform`.

## Testing

Manual checklist: [polar/testing-checklist.md](polar/testing-checklist.md).

## Further reading

- [polar/implementation-guide.md](polar/implementation-guide.md) — research notes + formulas (~2000 lines)
- [polar/testing-checklist.md](polar/testing-checklist.md) — session checklist
