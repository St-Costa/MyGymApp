# Storage

All user data lives under `context.filesDir/gymdata/` (app-internal storage). No database, no cloud, no external media — everything is files you can inspect with `adb pull` or a file manager with root access.

> ## ⛔ BACK THIS UP BEFORE ANY INSTALL/UNINSTALL/TEST OPERATION
>
> This directory is the **only** copy of the user's data — not in git, not on the dev
> machine, no OS backup guaranteed. `pm uninstall`, `pm clear`, installing a
> differently-signed APK, and **any `:baseline-profile` / `connected…AndroidTest` run**
> (they carry `uninstall_after_test: true`) **delete it permanently**. This has already
> destroyed real user data once.
>
> Pull it off the device first (needs a *debuggable* build installed):
> ```bash
> adb shell run-as com.mygymapp tar -C /data/data/com.mygymapp/files -cf - gymdata \
>   > gymdata-backup-$(date +%Y%m%d-%H%M%S).tar
> tar -tvf gymdata-backup-*.tar | head            # verify it's non-empty
> ```
> Restore with `... tar -C /data/data/com.mygymapp/files -xf - < gymdata-backup-*.tar`.
> See the banner at the top of [CLAUDE.md](../CLAUDE.md) for the full rule.

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
│   ├── _idx/
│   │   ├── ex-{8hex}.idx      ← exercise → session paths index
│   │   └── .migrated          ← one-time migration sentinel
│   ├── _stats/
│   │   └── ex-{8hex}.yaml     ← per-exercise derived stats (previous sets + tonnage PR)
│   └── _gitgraph.yaml         ← home 4-week gitgraph history, pre-computed
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
└── _sync/                     ← Server sync ledgers (see SYNC.md, BACKUP.md)
    ├── state.yml               (sessions)
    ├── readiness_state.yml     (readiness events)
    ├── scale_state.yml         (scale weigh-ins)
    ├── ecg_state.yml           (raw ECG uploads)
    └── repo_state.yml          (exercises + routines — full-store backup, BACKUP.md)
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
bwLoadPercent: 75             # only with isBodyweight — 25|50|75|100, % of body weight this
                              # movement loads (squat 100, plank 75, reverse sit-up 50,
                              # tibialis raise 25). Mandatory for a bodyweight exercise; legacy
                              # files with none migrate to 75. See below for how it feeds tonnage.
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
        weight: 55.0            # MATERIALIZED at exercise-completion: bwLoadPercent% of the
                                 # lifter's body weight (from the most recent scale weigh-in on
                                 # or before the session date), rounded to 0.5 kg. This makes
                                 # `reps * weight` tonnage/PR/e1RM work everywhere with no
                                 # bodyweight special-casing. 0.0 only if no weigh-in existed.
        isBodyweight: true      # omitted when false — copied from Exercise.isBodyweight
                                 # at session-build time; distinguishes "genuinely no
                                 # external weight" from "set never touched" (both are
                                 # weight=0 otherwise indistinguishable to any reader
                                 # that filters on weight > 0 — see SYNC.md)
        bwLoadPercent: 75       # audit: the Exercise.bwLoadPercent used for the number above
        bwBaseWeightKg: 73.3    # audit: the body weight the estimate was taken from
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

### Exercise stats sidecar (`history/_stats/{exerciseId}.yaml`)

A per-exercise **materialized view** over that exercise's session history, so the strength / stretch / superset exercise screens and the active-routine open don't have to parse dozens of session files each time. One file per exercise, front-matter-only YAML:

```yaml
---
exerciseId: "ex-3e4195a9"
schemaVersion: 2
contexts:
  - context: "DAILY"                              # NORMAL | WARMUP | DAILY, one block each
    previousSessionDate: "2026-08-28"             # bare YYYY-MM-DD (day key), never a datetime
    hasPriorRealTonnage: true                     # drives the "primo dato" badge
    pr:                                           # all-time best reps*weight set (0-or-1 element)
      - reps: 10
        weight: 3.0
    previousSets:                                 # sets of the most recent session with real data
      - reps: 10
        weight: 3.0
      - reps: 10
        weight: 3.0
---
```

Everything is split by [SlotContext] (a fixed-daily execution's history is unrelated to the same exercise's routine history — see [CONVENTIONS.md](CONVENTIONS.md#all-time-tonnage-pr--previous-preview-slot-context-match)). Bodyweight sets are stored with their materialized `weight` already applied, so `reps * weight` works with no special-casing.

**Schema v2** — a set entry (`pr` or a `previousSets` item) may also carry `bwBaseWeightKg`, the lifter's body weight at the time a **bodyweight** set was logged (copied from `ExerciseSet.Strength.bwBaseWeightKg`). It is omitted for non-bodyweight sets and for bodyweight sets logged before any scale weigh-in existed. The bodyweight exercise screens (strength + superset) show the PR as `reps × bwBaseWeightKg` ("peso corpo in quel momento") instead of the materialized `weight` (which for bodyweight is only `bwLoadPercent%` of that). PR *selection* is unchanged — still the highest materialized `reps × weight`.

**Maintenance** — [WorkoutRepository](../app/src/main/java/com/mygymapp/data/repository/WorkoutRepository.kt):
- **`save()` of a completed session**: each of its exercises' sidecars is updated by an *incremental merge* (`ExerciseStatsCalculator.merge`) — PR compare-and-set, `previousSets` replaced only if the new session has real data. No history scan; cheap on the save path. An in-progress save (autosave / back-out, blank `completedAt`) touches nothing.
- **`delete()` / `runMaintenance()` prune**: the incremental view can't "un-merge" a removed session, so each affected sidecar is *rebuilt* from a full scan of that one exercise's history (bounded by its `.idx`, not the whole tree). Deletes are rare.
- **Missing / wrong-schema / wrong-exercise sidecar**: `getExerciseStats` only serves a parsed sidecar whose `schemaVersion == ExerciseStats.SCHEMA_VERSION` **and** whose `exerciseId` matches the one asked for; anything else is discarded and rebuilt on first read, then persisted so the next read is fast. Bumping the constant is the whole "migration" — every sidecar regenerates lazily.
- The one-time index migration also wipes `_stats/` (lazy regen afterwards).

`previousSessionDate` is stored as a **bare `YYYY-MM-DD` day key** (`ExerciseStatsCalculator.dayKey`), not a datetime — both `merge` and `rebuild` derive and compare it the same way, so a re-save on the same day (`>=`) still replaces "previous" and a stored date-only value never mis-compares against an incoming ISO datetime.

The `.md` files remain the source of truth; the sidecar is a cache. The derivation rules (what "previous" is, what the PR is) live in one pure, unit-tested place — `ExerciseStatsCalculator` — used by both the incremental and full-rebuild paths, with a test pinning that a chain of `merge`s equals a single `rebuild`.

### Home gitgraph cache (`history/_gitgraph.yaml`)

The home screen's 4 history rows (28 day squares for the 4 weeks **before** the current week) were computed on every home open by parsing ~3 months of session files (`getSessionsInRange` over the visible window + a 2-month lookback for the oldest days' comparison). Now a single file holds them pre-computed: front-matter-only YAML with `windowStartMonday`, `schemaVersion`, and 28 `days`, each `{date, status, tonnageChangePct?, cardioMinutes?, routineName?, sessionId?}`.

A day square is derived from that day's registered session and the previous session of the same routine — both immutable once registered — so a square for a day *before the current week* never changes. The **current week's** row (the schedule row + "today" cell) is *not* cached; `HomeStateLoader` builds it from a small current-week query each time.

**Maintenance** — [WorkoutRepository](../app/src/main/java/com/mygymapp/data/repository/WorkoutRepository.kt):
- **Read** (`getGitgraphHistory`): serves the cache when its `windowStartMonday` matches what today implies, `schemaVersion` matches, and all 28 days are present. Otherwise recomputes the full window (once per week as it slides forward; also after a drop) and persists — recompute happens outside the write lock, with a re-check under it.
- **`save()` / `delete()` of a completed session** whose `date` falls in the cache's window **or its 2-month lookback** drops the cache (`invalidateGitgraphCacheIfInWindow`) — a back-dated edit in the lookback still shifts the oldest visible days' "previous" comparison. Normally a no-op: sessions are registered *today* = current week = outside both.
- **`runMaintenance()`** drops the cache whenever it actually pruned anything (a >3-month-old session can be in the 2-month lookback).
- **Routine rename** (`updateRoutineNameInHistory`) drops it — the cache denormalizes `routineName`.
- The one-time index migration wipes it too.

Derivation is one pure object, `GitgraphHistoryCalculator`, shared: `HomeStateLoader` calls the same `dayCell` for the current-week row so history rows and the live row can't diverge. Round-trip + calculator tests in `GitgraphHistoryParserTest` / `GitgraphHistoryCalculatorTest`.

### Migration

One-time migration for early adopters:
- Old format: `YYYY-MM-DD_{slug}-{id}.md` (2 `_`-segments)
- New format: `YYYY-MM-DD_{routineId}_{sessionId}.md` (3 segments)

Runs on app startup in [MainViewModel](../app/src/main/java/com/mygymapp/ui/screen/main/MainViewModel.kt), guarded by `history/_idx/.migrated`. Rebuilds the exercise index from scratch and wipes `_stats/` + `_gitgraph.yaml`. Idempotent.

### Auto-prune

On startup, sessions older than 3 months are deleted. Exercise index entries for removed files are cleaned up as part of the same pass, the affected exercises' stats sidecars are rebuilt, and — if anything was pruned — the gitgraph cache is dropped.

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

The readiness (`readiness_state.yml`), scale (`scale_state.yml`) and raw-ECG
(`ecg_state.yml`) ledgers mirror this shape with their own dedicated repositories.

### Repo-file ledger (`_sync/repo_state.yml`)

The fifth pipeline — full-store backup of every human-authored `exercises/*.md` and
`routines/*.md` (see [BACKUP.md](BACKUP.md)). Owned by
[RepoLedgerRepository](../app/src/main/java/com/mygymapp/data/sync/RepoLedgerRepository.kt).
Two structural differences from the four above:

- **Keyed by relative path**, not an id — the slug embedded in the filename changes on
  rename, so the path is the stable-per-version key.
- **Syncs deletions.** An exercise/routine removed on the phone (or a stale path a rename
  left behind) becomes a `DELETED_PENDING` entry with `op: delete`; the worker POSTs a
  tombstone and it moves to `DELETED_SENT`. The entry is kept, not dropped, so a later
  full re-scan can't resurrect the file server-side.

```yaml
files:
  "exercises/bench-press-ex-a1b2c3d4.md":
    op: upsert                 # upsert | delete
    status: SENT               # PENDING | SENT | FAILED | DELETED_PENDING | DELETED_SENT
    attempts: 1
    lastAttemptAt: "2026-08-28T20:11:03"
    lastError: ""
    contentHash: "sha256:9f8e7d6c..."
  "exercises/old-typo-ex-deadbeef.md":
    op: delete
    status: DELETED_PENDING
    attempts: 0
    lastAttemptAt: ""
    lastError: ""
    contentHash: "sha256:..."   # hash of the last-known content, for the tombstone
```

Since exercises and routines are now backed up incrementally to the server, a wipe of
`filesDir/gymdata/` is recoverable via **Options → "Ripristina dal server"**
(`GET /v1/manifest` + `GET /v1/file`, pull-only) — see [BACKUP.md](BACKUP.md) §3.6.

## Backup / export

`android:allowBackup="true"` is set in the manifest — Android's auto-backup will upload `filesDir` on supported devices. For manual export, `adb pull /data/data/com.mygymapp/files/gymdata`. Restoring is just a matter of putting the directory back; the migration sentinel ensures old-format files are re-indexed on first launch.
