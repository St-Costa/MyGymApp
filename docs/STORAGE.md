# Storage

All user data lives under `context.filesDir/gymdata/` (app-internal storage). No database, no cloud, no external media — everything is files you can inspect with `adb pull` or a file manager with root access.

## Root layout

```
filesDir/gymdata/              ← FileManager.root
├── exercises/                 ← Exercise repository
│   └── {slug}-ex-{8hex}.md
├── routines/                  ← Routine repository
│   └── {slug}-rt-{8hex}.md
├── history/                   ← Workout sessions
│   ├── YYYY/MM/
│   │   └── YYYY-MM-DD_rt-{8hex}_{sessionId}.md
│   └── _idx/
│       ├── ex-{8hex}.idx      ← exercise → session paths index
│       └── .migrated          ← one-time migration sentinel
├── ecg/                       ← Raw ECG recordings (ephemeral)
│   └── {sessionId}.ecg
├── cache/images/              ← ImageCacheRepository (exercise link previews)
│   └── {sha256[:16]}.img
├── image_cache/               ← Coil disk cache (100 MB, LRU)
│   └── (Coil-managed)
├── readiness/                 ← One file per 60s HRV readiness measurement
│   └── {8hex}.md
├── scale/                     ← VitaFit VT701 weigh-ins (one per calendar day)
│   └── YYYY/MM/
│       └── YYYY-MM-DD.md
└── _sync/                     ← Server sync ledgers (see SYNC.md)
    ├── state.yml               (sessions)
    ├── readiness_state.yml     (readiness events)
    └── scale_state.yml         (scale weigh-ins)
```

The two image caches serve different purposes:
- `cache/images/` is populated by [ImageCacheRepository](../app/src/main/java/com/mygymapp/data/repository/ImageCacheRepository.kt) for exercise link images referenced from markdown files.
- `image_cache/` is Coil's LRU disk cache used by `SubcomposeAsyncImage` in [MediaPreview](../app/src/main/java/com/mygymapp/ui/components/MediaPreview.kt). Configured in [MyGymApp.kt](../app/src/main/java/com/mygymapp/MyGymApp.kt).

## File formats

All persistent files are Markdown with a YAML frontmatter block and an optional free-text body. Parsed by [MarkdownParser](../app/src/main/java/com/mygymapp/data/parser/MarkdownParser.kt).

### Exercise (`exercises/{slug}-{id}.md`)

```yaml
---
id: ex-a1b2c3d4
name: Bench Press
type: FORZA                   # FORZA | STRETCH | CARDIO
bodypart: chest
link: https://...             # optional image/YouTube URL
defaultRepRangeMin: 8
defaultRepRangeMax: 12
isBodyweight: true            # omitted when false — FORZA only, no external weight by design
created: 2025-03-15T10:22:14
updated: 2025-04-18T09:00:00
---

Free-text notes (form cues, tempo, etc.)
```

Filename uses `slugify(name)-{id}`. Type prefix `ex-` visually distinguishes exercise IDs from routine IDs.

### Routine (`routines/{slug}-{id}.md`)

```yaml
---
id: rt-b2c3d4e5
name: Push Day
day: monday                   # monday..sunday
enabled: true
exercises:
  - exerciseId: ex-a1b2c3d4
    sets: 4
    repRangeMin: 6
    repRangeMax: 10
    timePerSetSeconds: 60
    supersetWithNext: false
    isWarmup: true              # omitted when false
  - exerciseId: ex-b2c3d4e5
    sets: 3
    ...
created: ...
updated: ...
---

Free-text routine notes
```

`supersetWithNext: true` pairs an exercise with the following one — see [ARCHITECTURE.md](ARCHITECTURE.md) screen list for the superset UI.

`isWarmup: true` marks an exercise as warmup (the contiguous leading prefix of the list). Warmup exercises are excluded from tonnage. In the routine editor a positional divider line separates warmup (above) from normal (below). See [CONVENTIONS.md](CONVENTIONS.md#fixed-daily-exercise-container).

**Fixed daily exercise container**: a reserved routine `id: rt-fixeddaily` (name `Fixed daily exercise`, `day: ""`) is auto-seeded on first load and is non-deletable / non-disableable. Its exercises are injected at the start of every session (excluded from tonnage). See [CONVENTIONS.md](CONVENTIONS.md#fixed-daily-exercise-container).

### Workout session (`history/YYYY/MM/YYYY-MM-DD_{routineId}_{sessionId}.md`)

```yaml
---
id: 3c4d5e6f                  # 8-hex session UUID
date: 2025-04-15
routineId: rt-b2c3d4e5
routineName: Push Day         # denormalized for historical display
startedAt: 2025-04-15T09:15:00
completedAt: 2025-04-15T10:05:42   # empty string if abandoned
totalTonnage: 12450.0              # pre-computed (sum of reps×weight)
tonnageByBodypart:
  chest: 8200.0
  shoulders: 4250.0
# Polar-derived metrics (all optional, zero if not recorded)
sessionCalories: 412.3
sessionTrimp: 87.5
vo2max: 48.2
ecgBeats: 5240
ecgDurationSec: 2950
ecgSessionRmssd: 38.2
ecgPacCount: 2
ecgPauseCount: 0
ecgIrregularBeats: 4
afibSuspicionEpisodes: 0
sdnn: 55.0
pnn50: 12.4
poincareSd1: 27.1
poincareSd2: 85.6
poincareRatio: 0.317
cardiacDriftBpmMin: 0.42
restingHr: 58
hrr60s: 28
# Session-RPE (Foster method): subjective 0-9 effort rating, asked right after the session
# ends via a mandatory (non-skippable) prompt — registration is blocked until answered.
# Omitted only for sessions saved before this field existed, or abandoned sessions that
# never reach registration. sessionLoad = sessionRpe × duration in minutes
# (startedAt→completedAt), computed whenever a valid duration exists.
sessionRpe: 7
sessionLoad: 350.0
exercises:
  - exerciseId: ex-a1b2c3d4
    exerciseName: Bench Press   # denormalized
    type: FORZA
    bodypart: chest
    excludeFromTonnage: true    # omitted when false; set for warmup + fixed-daily exercises
    isDaily: true               # omitted when false; set only for fixed-daily exercises
    substitutedFor: ex-f6e5d4c3 # omitted unless this slot was swapped mid-session via "Switch
                                 # exercise" (see docs/CONVENTIONS.md#switch-exercise) — holds
                                 # the originally-planned exerciseId; the slot above already
                                 # carries the NEW exercise's id/name/bodypart/type/sets. The
                                 # routine on disk is untouched, so the next session from it
                                 # proposes ex-f6e5d4c3 again by default.
    sets:
      - reps: 8
        weight: 80.0
      - reps: 6
        weight: 85.0
      ...
  - exerciseId: ex-c3d4e5f6
    exerciseName: Plank
    type: FORZA
    bodypart: core
    sets:
      - reps: 45
        weight: 0.0
        isBodyweight: true      # omitted when false — copied from Exercise.isBodyweight
                                 # at session-build time; distinguishes "genuinely no
                                 # external weight" from "set never touched" (both are
                                 # weight=0 otherwise indistinguishable to any reader
                                 # that filters on weight > 0 — see SYNC.md)
  - exerciseId: ex-c9d8e7f6
    exerciseName: Corsa leggera
    type: CARDIO
    bodypart: cardio
    excludeFromTonnage: true    # always true for CARDIO, regardless of section
    sets:                       # one entry per "Inizia cardio"/"Termina cardio" block —
                                 # several are possible in one session (e.g. 10min bike +
                                 # 20min run), see docs/POLAR.md#cardio-blocks
      - startedAt: "2026-08-12T10:15:03"
        endedAt: "2026-08-12T10:45:10"
        avgHr: 138               # computed on-device from the live HR stream, not user-entered
        maxHr: 156
      - startedAt: "2026-08-12T10:50:00"
        endedAt: ""              # blank = block still running, or abandoned without an
                                 # explicit "Termina cardio" (never left blank on a
                                 # completed exercise — see CardioExerciseViewModel)
        avgHr: 0
        maxHr: 0
---

Session notes
```

`excludeFromTonnage: true` is resolved when the session is built (warmup and fixed-daily exercises) and persisted per-exercise. Every tonnage reader filters `!excludeFromTonnage`; cardio metrics (`sessionCalories`, `sessionTrimp`, `vo2max`, ECG/HRV) are session-global and unaffected. `ExerciseType.CARDIO` exercises are always excluded from tonnage too, independent of section.

`isDaily: true` marks an exercise performed as a fixed-daily exercise in this session. Exercise screens use it so daily progress (grey "previous" values) is compared only against prior sessions where the same exercise was *also* daily, and normal progress only against prior normal sessions — the same exercise can swing between the two roles across days without contaminating either history.

**`ExerciseSet.Cardio`** (`type: CARDIO` only): `startedAt`/`endedAt` are absolute ISO
`LocalDateTime` strings, not offsets — this is what lets the sync server correlate a block
against the raw `.ecg` file's own `startTimestamp`+`sampleRate` header (see
[POLAR.md](POLAR.md#cardio-blocks) and [SYNC.md](SYNC.md#fourth-record-type-raw-ecg)) to
slice out the matching waveform segment, with zero change to the binary ECG format. `avgHr`/
`maxHr` are computed on-device from `PolarManager.heartRate` while the block runs — never
user-entered.

### Readiness event (`readiness/{id}.md`)

```yaml
---
id: a1b2c3d4
measuredAt: "2026-08-05T07:04:10.123"
readiness: "GOOD"
lnRmssd: 4.30
restingHr: 65
vo2max: 45.2
recommendation: "HRV above baseline. Good day to push intensity."
---
```

Written by [ReadinessRepository](../app/src/main/java/com/mygymapp/data/polar/ReadinessRepository.kt) at the end of [PolarManager.finishReadinessMeasurement()](../app/src/main/java/com/mygymapp/data/polar/PolarManager.kt) — the 60s HRV measurement that already ran on every HR connect, but was previously only held in an in-memory StateFlow for the UI and never persisted. Only successful measurements are saved (`cleanRR.size >= 20`); a failed "not enough clean data" attempt is discarded, not written. See [POLAR.md](POLAR.md) for the readiness algorithm and [SYNC.md](SYNC.md) for how these get synced to the server immediately, independent of session sync.

### Scale weigh-in (`scale/YYYY/MM/{date}.md`)

```yaml
---
id: "2026-08-05"
date: "2026-08-05"
recordedAt: "2026-08-05T07:12:34"
weightKg: 78.4
bmi: 24.1
bodyFatPercent: 16.8
leanMassPercent: 81.2
---
```

Written by [ScaleHistoryRepository](../app/src/main/java/com/mygymapp/data/repository/ScaleHistoryRepository.kt) from [BleScaleManager.maybeSaveWeighIn()](../app/src/main/java/com/mygymapp/data/scale/BleScaleManager.kt) — parses the VitaFit VT701's weight + bioimpedance notify packets, computes BMI/body-fat/lean-mass via [BodyCompositionCalculator](../app/src/main/java/com/mygymapp/data/scale/BodyCompositionCalculator.kt), and also updates `UserProfile.weightKg` (the scale is the sole source of body weight — no manual entry). **`id` is an ISO date string, not an `{8hex}` UUID** — one weigh-in per calendar day; re-weighing the same day overwrites the existing file rather than creating a second entry. See [SYNC.md](SYNC.md) for how these get synced to the server immediately.

### Raw ECG (`ecg/{sessionId}.ecg`)

Binary, written by [EcgRecorder](../app/src/main/java/com/mygymapp/data/polar/EcgRecorder.kt).

| Offset | Size | Content |
|---|---|---|
| 0 | 8 B | Magic `MYGMECG1` |
| 8 | 4 B | Sample rate (Int32, typically 130) |
| 12 | 8 B | Start timestamp (Int64, ns since epoch) |
| 20 | N×2 B | Sample stream (Int16, µV) |

Flushed every 260 samples (~2s). [EcgAnalyzer](../app/src/main/java/com/mygymapp/data/polar/EcgAnalyzer.kt) exists but is no longer called — deep ECG analysis (the 12 arrhythmia/HRV metrics it used to compute) moved server-side; the session frontmatter's ECG-derived fields (`ecgBeats`, `sdnn`, `poincareSd1`, etc., still declared in `WorkoutSession` for backward-compat with sessions saved before this change) are no longer populated and stay at their zero defaults going forward. Only `restingHr`, `hrr60s`, and `cardiacDriftBpmMin` — cheap HR-series computations, not deep waveform analysis — are still computed and saved locally, alongside `vo2max`/`sessionCalories`/`sessionTrimp`. The raw `.ecg` file itself: deleted after successful upload to the sync server (`EcgSyncWorker`, gzip-compressed, `POST /v1/ecg`) if sync is configured/enabled — capped at 30 days pending, after which it's deleted unrecovered even without a confirmed upload (`EcgSyncLedgerRepository.expireStale()`); if sync isn't configured, deleted immediately (nothing local would ever analyze or send it). See [SYNC.md](SYNC.md#fourth-record-type-raw-ecg) and [POLAR.md](POLAR.md#post-session-analysis).

## IDs

| Entity | Format | Generated in |
|---|---|---|
| Exercise | `ex-{8hex}` | `ExerciseRepository.save()` |
| Routine | `rt-{8hex}` | `RoutineRepository.save()` |
| Session | `{8hex}` | `WorkoutRepository.save()` |

`8hex` = first 8 chars of a `UUID.randomUUID().toString().replace("-", "")`. The typed prefix is the pragmatic equivalent of namespaces: you can see whether a file refers to an exercise or a routine without opening it.

## Workout history and exercise index

Session files sit in `history/YYYY/MM/` so date-range scans only touch the relevant months. The file *name* already encodes the date and routine ID — no YAML parse required for routine-based lookups.

For exercise-based lookups, `history/_idx/{exerciseId}.idx` maps each exercise to the list of session paths (relative to `history/`) that contain it, one per line:

```
2025/03/2025-03-15_rt-b2c3d4e5_1a2b3c4d.md
2025/04/2025-04-08_rt-b2c3d4e5_5e6f7a8b.md
```

Maintained transactionally by [WorkoutRepository](../app/src/main/java/com/mygymapp/data/repository/WorkoutRepository.kt) on every `save()` / `delete()`. Updates are accumulated in an in-memory `Map<exerciseId, Set<relPath>>` and flushed in a single pass, so each `.idx` file is read and written at most once per save or migration — regardless of how many times the same exercise appears. Stale entries (pointing at deleted files) are silently filtered out at read time, then cleaned up on the next prune.

### Migration

One-time migration for early adopters:
- Old format: `YYYY-MM-DD_{slug}-{id}.md` (2 `_`-segments)
- New format: `YYYY-MM-DD_{routineId}_{sessionId}.md` (3 segments)

Runs on app startup in [MainViewModel](../app/src/main/java/com/mygymapp/ui/screen/main/MainViewModel.kt), guarded by `history/_idx/.migrated`. Rebuilds the exercise index from scratch. Idempotent.

### Auto-prune

On startup, sessions older than 3 months are deleted. Exercise index entries for removed files are cleaned up as part of the same pass.

## Name sync on rename

Session files denormalize `exerciseName` and `routineName`. When an exercise or routine is renamed:

- `ExerciseRepository.save()` → `WorkoutRepository.updateExerciseNameInHistory()` — walks the exercise index, rewrites only the files that actually reference this exercise.
- `RoutineRepository.save()` → `WorkoutRepository.updateRoutineNameInHistory()` — walks `getRoutineSessionFiles(routineId)` by filename filter.

No file rename is ever needed — filenames embed IDs, not names.

## SharedPreferences

Two keys, both `MODE_PRIVATE`:

| Prefs file | Key | Type | Owner | Purpose |
|---|---|---|---|---|
| `user_profile` | `birthYear` | Int, absent if unset | [UserProfileRepository](../app/src/main/java/com/mygymapp/data/polar/UserProfile.kt) | Sole source of age (`UserProfile.effectiveAge`, recomputed from the current year) for every age-dependent formula: calorie (Keytel), Tanaka HRmax, TRIMP, VO2max, BIA body-fat %. Falls back to a fixed default age until set — no separate "age" field exists |
| `user_profile` | `weightKg` | Float | " | " |
| `user_profile` | `isMale` | Boolean | " | " |
| `user_profile` | `heightCm` | Int | " | Used by BIA body-fat % (scale integration) |
| `hrv_baseline` | `lnrmssd_values` | String (CSV, ≤14 doubles) | [PolarManager](../app/src/main/java/com/mygymapp/data/polar/PolarManager.kt) | Rolling 14-day LnRMSSD baseline for HRV readiness z-score |
| `sync_config` | `serverUrl` | String | [SyncConfigRepository](../app/src/main/java/com/mygymapp/data/sync/SyncConfigRepository.kt) | Tailscale Serve hostname for the self-hosted sync server, e.g. `https://gym-server.tailnet.ts.net` |
| `sync_config` | `bearerToken` | String | " | Shared secret sent as `Authorization: Bearer` on every sync POST |
| `sync_config` | `enabled` | Boolean | " | Sync stays dormant until explicitly turned on in Options, even with URL+token set |

Nothing else is persisted outside `gymdata/`.

## Server sync ledger (`_sync/state.yml`)

Tracks delivery status per session ID for the self-hosted server sync feature — see
[SYNC.md](SYNC.md). Not session content; purely "has this session's current content been
handed to the server yet."

```yaml
sessions:
  3c4d5e6f:
    relPath: "2026/08/2026-08-05_rt-b2c3d4e5_3c4d5e6f.md"
    status: SENT              # PENDING | SENT | FAILED
    attempts: 1
    lastAttemptAt: "2026-08-05T10:05:42"
    lastError: ""
    contentHash: "sha256:9f8e7d6c..."
```

Owned by [SyncLedgerRepository](../app/src/main/java/com/mygymapp/data/sync/SyncLedgerRepository.kt),
read/written with the same hand-rolled snakeyaml `Load` + manual-write approach as
[MarkdownParser](../app/src/main/java/com/mygymapp/data/parser/MarkdownParser.kt), since
this is a flat map rather than a frontmatter+body document.

## Backup / export

`android:allowBackup="true"` is set in the manifest — Android's auto-backup will upload `filesDir` on supported devices. For manual export, `adb pull /data/data/com.mygymapp/files/gymdata`. Restoring is just a matter of putting the directory back; the migration sentinel ensures old-format files are re-indexed on first launch.
