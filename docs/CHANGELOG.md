# Changelog

Historical phase log. The most recent commits live in `git log` — this file captures the high-level milestones and the intent behind them.

## Phase 1 — Foundation
Android project bootstrap: Kotlin, Compose, Hilt, Material3 dark-only theme. Data models (`Exercise`, `Routine`, `WorkoutSession`, sealed `ExerciseSet`). Parsers (`MarkdownParser` + per-type parsers). Repositories with in-memory cache + `FileManager`. Navigation skeleton with all screens wired as placeholders.

## Phase 2 — Exercise management
`ExerciseListScreen` grouped by bodypart, FORZA/STRETCH color coding, picker mode. `ExerciseEditScreen` with auto-save, type toggle, bodypart autocomplete, default rep range (FORZA only). `BodyPartAutocomplete` and `ExerciseCard` components.

## Phase 3 — Routine management
`RoutineListScreen` with enable/disable toggle. `RoutineEditScreen` with day picker, per-exercise set/rep/time config, drag-and-drop reorder, round step buttons.

## Phase 4 — Active workout
`ActiveRoutineScreen` with completion state (strikethrough + checkmark), editable notes, progress section after all exercises complete. `StrengthExerciseScreen` with `ScrollPickerInput` and previous-session values. `StretchExerciseScreen` with stopwatch and set checkboxes. `AutoSaveTextField`. `StopwatchService` foreground with chronometer notification.

## Phase 5 — Progress & charts
`GitgraphView` (4×7 day grid) with per-cell tonnage % overlay and current-week routine names. `WeekViewScreen`. Per-session tonnage + per-bodypart tonnage stored in session frontmatter. Common-exercise comparison so routine changes don't distort gitgraph colors.

## Phase 6 — Media & polish
`StopwatchService` hardening: channel v2, `IMPORTANCE_DEFAULT` + silent sound, `FOREGROUND_SERVICE_IMMEDIATE`, dynamic-bitmap notification icon cycling through 5 colors, 30s vibration with drift-safe trigger logic. In-app `MM:SS` display with running-state dimming. `MainActivity` runtime `POST_NOTIFICATIONS` permission. `ImageCacheRepository` with SHA-256 keying and Google Drive URL rewrite.

## Phase 7 — Post-completion progress
`TonnageLineChart` pure-Canvas implementation. `ActiveRoutineScreen` progress section with filter chips (Totale + per bodypart), per-exercise tonnage change badge loaded from reloaded session. `WorkoutRepository.getSession()` + `getLastSessionForRoutine()` fixes.

## Phase 8 — Media display
`MediaPreview` component: Coil-backed images with Google Drive rewrite, YouTube thumbnail + Chrome Custom Tabs (after confirming WebView embeds are unworkable on Android). Live preview in `ExerciseEditScreen`. Permanent Coil disk cache at `filesDir/gymdata/image_cache/`.

## Phase 9 — History consistency & ID-based filenames
Typed ID prefixes (`ex-`, `rt-`). Session filename switched to `YYYY-MM-DD_{routineId}_{sessionId}.md` so routine lookups are a filename filter. `history/_idx/{exerciseId}.idx` exercise → session index maintained on every save/delete. Name sync on rename traverses the index instead of doing full scans. One-time migration rebuilds the index from scratch and renames old-format files. Auto-prune of sessions older than 3 months.

## Phase 10 — Code quality & bug fixes
ANR fix: `runBlocking` in `onCleared()` replaced with a dedicated `clearScope`. `DataChangedSignal` introduced. `slugify()` extracted to `data/util/StringUtils.kt`. Shared composables (`FullscreenLoading`, `EmptyStateBox`, `DeleteConfirmationDialog`, `RoundStepButton`) extracted. `WorkoutRepository.save()` deduplicates before writing the index. `ImageCacheRepository` streams downloads. Compose memoization added where it mattered. `DeleteConfirmationDialog` shared across edit screens.

## Phase 11 — Supersets
`RoutineExercise.supersetWithNext: Boolean`. Chain-link button in `RoutineEditScreen` pairs adjacent exercises; `SupersetPairContainer` drags as a unit via segment-based drag-drop (`ExerciseSegment` sealed class). `ActiveRoutineScreen` groups exercises into `Single` / `Superset` groups. `SupersetScreen` / `SupersetViewModel` with interleaved sets, pre-populated FORZA pickers, per-exercise tonnage badges, round-based set pairing. `material-icons-extended` dependency. Completion signal extended to `completedSupersetIds`.

## Phase 12 — Polar H10 integration
BLE subsystem under `data/polar/`: `PolarManager` facade, `EcgRecorder`, `EcgAnalyzer` (Pan-Tompkins + arrhythmia), `LiveEcgAnalyzer` (incremental), `UserProfile` repository. `PolarStreamingService` foreground service (`connectedDevice` type). `HeartRateScreen` with 60s HRV readiness (z-score vs 14-day baseline, `DELOAD/LIGHT/NORMAL/GOOD/PEAK`), VO2max (Uth), cardio-trend self-diagnosis card (`CardioTrendLoader`). `HeartRateBar` live HR + recovery semaphore in every exercise screen. `LiveEcgCard` live waveform + beat counter + PAC/pause/irregular/AFib flags. Automatic HRR detection, cardiac-drift regression, Keytel calories, Banister TRIMP. Session frontmatter extended with 14 cardio/ECG fields. `SessionProgressScreen` reachable from the gitgraph.

## Phase 13 — Data safety hardening
`AutoSaveTextField` flushes pending save in `DisposableEffect.onDispose`. `clearScope.cancel()` moved to `finally` inside `launch` in edit ViewModels. `StopwatchService.ACTION_START` made idempotent. `ImageCacheRepository` uses `HttpURLConnection` with 10s/15s timeouts. Polar `Disposable` lifecycle already disposed before reassignment (verified).

## Phase 14 — Performance pass
- `StopwatchService`: reuse a single `Bitmap`/`Paint`/`Canvas` across every notification tick instead of allocating 96×96 ARGB + Paint objects each second (~37 KB/s GC pressure eliminated during stretch workouts).
- `WorkoutRepository`: batch exercise-index writes via an in-memory `Map<exerciseId, Set<relPath>>` that is flushed once per save and once per migration pass. With N distinct exercises per session, drops 2N syscalls to 2 for the duration of the batch.
- `slugify()` and `ImageCacheRepository.convertToDirectUrl()`: top-level `private val` regex instances instead of constructing per call.
- `CardioTrendLoader`: single `partition { LocalDate.parse(...) }` replaces the two-pass filter, halving `LocalDate.parse` calls.
- `PolarManager.hrSeries`: `ArrayDeque` capped at 28 800 entries (~8 h @ 1 Hz) to bound memory under lifecycle bugs.
- `ExerciseRepository.getBodyparts()`: memoized result invalidated on `save()` / `delete()`.
- `ui/util/SupersetGrouping.kt`: shared `groupSupersets` helper consumed by `RoutineEditViewModel.buildExerciseSegments` and `ActiveRoutineScreen.buildExerciseGroups`.
- `ExerciseType.accentColor()` extension in `theme/Color.kt` replaces two inline `when` blocks in `ExerciseCard` and `ActiveRoutineScreen`.

## Phase 15 — VO2max HRrest baseline
- `PolarManager.finishReadinessMeasurement`: VO2max now divides by the minimum HRrest of the last 7 readings (stored as `hrrest_values` in `SharedPreferences("hrv_baseline")`, rolling window) instead of the single session's minimum HR. Falls back to today's value when the baseline has fewer readings. Removes most of the day-to-day noise (caffeine, sleep, stress) so the Uth output stabilises at ~±1–2 ml/kg/min session-over-session. HRmax still from Tanaka.

## Phase 16 — Session data hygiene
Audit of on-device `gymdata/` revealed 21/76 sessions were "ghost" shells (opened but never filled — `completedAt: ""`, all sets at 0), plus ~500 KB of orphan `.ecg` raw files from the same shells, plus YAML noise from full-precision floats and always-written zero ECG/HRV fields.

- **Ghost prevention** (`ActiveRoutineViewModel.onCleared`): when the user leaves the active routine without any completed exercise or any set with real data (`reps>0 ∨ weight>0 ∨ done=true`), the shell session file + its `.ecg` are deleted and the Polar stream is stopped. Uses the standard `clearScope` pattern.
- **Boot-time cleanup** (`WorkoutRepository.cleanupGhostSessions()` + `cleanupOrphanEcgFiles()`, wired in `MainViewModel.init`): one-shot scrub of pre-existing shells and of `.ecg` files whose `sessionId` has no matching `history/.../*.md`.
- **ECG-analysis preservation** (`ActiveRoutineViewModel.registerRoutine`): the raw `.ecg` is now deleted only when `ecgResult.hasAnything == true`. Failed analyses (file too short, <2 peaks, <5 valid RR) keep the file so it can be re-inspected. Added `PolarManager.ecgFileSize()` + structured logs in both `EcgAnalyzer` and the VM for post-mortem diagnosis.
- **Lifecycle fix**: `onCleared` always calls `stopEcgRecording()` + `stopHrSeriesCapture()` even for finalized-but-unregistered sessions, so the Polar stream no longer keeps writing indefinitely when the user backs out between `finalizeSession` and `registerRoutine`.
- **YAML compaction** (`WorkoutParser.toMarkdown`, `MarkdownParser.formatValue`): Double/Float values rounded to 2 decimals at serialization (`vo2max: 46.14` instead of `46.142857142857146`); ECG/HRV/recovery fields (`ecgBeats`, `sdnn`, `pnn50`, `poincareSd{1,2,Ratio}`, `afib*`, `restingHr`, `hrr60s`, `cardiacDriftBpmMin`, …) omitted from frontmatter when zero; `tonnageByBodypart` filtered to non-zero entries only.
- **Rep-range invariant** (`ExerciseEditViewModel` + `RoutineEditViewModel`): symmetric clamp — raising min above max pulls max up, lowering max below min pulls min down — so `min > max` can no longer be persisted. One-shot migration (`ExerciseRepository.fixInvalidRepRanges()` + `RoutineRepository.fixInvalidRepRanges()`, guarded by `.reprange_fixed` sentinel) repairs any existing `min > max` by setting `max = min`.

## Phase 17 — Fixed daily exercises + warmup distinction

Two related additions to routine/session modelling, both excluded from tonnage but fully counted by the (session-global) cardio metrics.

- **Fixed daily exercise container**: a reserved, non-deletable / non-disableable routine (`id: rt-fixeddaily`, `day: ""`) auto-seeded by `RoutineRepository.ensureFixedDailyRoutine()` inside `ensureLoaded()`. The user edits its exercise list like any routine; those exercises are injected at the start of every session. It's pinned to the top of the routine list, hidden from the week view (no day) and guarded against being started. See [CONVENTIONS.md](CONVENTIONS.md#fixed-daily-exercise-container).
- **Warmup vs normal per routine**: new `RoutineExercise.isWarmup` flag, persisted as the contiguous leading prefix of the exercise list. The routine editor renders a positional divider line (↑/↓ controls moving it by whole segments, snapped to boundaries so a superset never splits) above which exercises are warmup. The container's editor shows no line.
- **Tonnage exclusion**: new `WorkoutExercise.excludeFromTonnage`, resolved at session-build time (warmup + fixed-daily) and persisted per exercise. `ActiveRoutineViewModel` builds the session in `warmup → fixed-daily → normal` order (clearing superset links at each section boundary, and skipping a fixed-daily exercise already present in the routine since the detail flow keys by `exerciseId`). Every tonnage reader — `finalizeSession`, `SessionProgressViewModel`, `MainViewModel.computeCommonTonnage`, plus the per-exercise/historical comparisons — filters `!excludeFromTonnage`. New boolean fields are omitted from YAML when false, so existing files migrate as all-normal/all-counted.
- **Session UI**: `ActiveExerciseUi.category` (WARMUP/DAILY/NORMAL) drives section headers in `ActiveRoutineScreen` (`SessionSectionHeader`) — shown only when the session mixes categories. Excluded sections are muted, the WORKOUT header is highlighted; exercise cards are otherwise unchanged so the headers don't clash with the type-color borders or the superset frame.

## Phase 18 — Polar reconnection + notification UX

Diagnosed from a real session (2026-06-01, LEG): real tonnage but `sessionCalories: 14.88` — the H10 dropped early and never came back, freezing the cardio metrics. Root cause: `deviceDisconnected` tore everything down and stopped the foreground service without ever attempting to reconnect, and stopping the FGS removed the OS's permission to keep BLE alive in the background.

- **Auto-reconnect** (`PolarManager`): a `userInitiatedDisconnect` flag + `lastConnectedDeviceId` distinguish a manual disconnect from an unexpected drop. On an involuntary drop *during a session* the FGS is kept alive, a disconnect alert fires, and `scheduleReconnect()` retries `connectToDevice` every 10s until it returns. Outside a session the drop is a quiet stop. Reconnection is cancelled when the session ends.
- **Calorie/TRIMP reset moved to session start** (`startHrSeriesCapture`) instead of `deviceConnected`, so a mid-session reconnect no longer wipes the accumulated counters. Readiness measurement is skipped on a mid-session reconnect.
- **Notification UX** (`PolarStreamingService`): the ongoing FGS notification is downgraded to `IMPORTANCE_MIN` (no status-bar icon, collapsed, silent) on a new channel id `polar_hr_channel_min` (importance is immutable once a channel exists; the legacy channel is deleted). A separate high-importance `polar_hr_alert` channel fires a heads-up **pop-up with sound** only when the sensor disconnects mid-session, cleared on reconnect. Removing the FGS entirely was rejected — it's what keeps the connection alive when the screen locks between sets.

## Future enhancements

- Export / import `gymdata/` as a zip
- R8 / ProGuard minify for release with `-keep` rules for Polar SDK + RxJava
- Consolidate `cache/images/` and `image_cache/` under Coil
