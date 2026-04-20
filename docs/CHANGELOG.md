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

## Future enhancements

- Export / import `gymdata/` as a zip
- R8 / ProGuard minify for release with `-keep` rules for Polar SDK + RxJava
- Consolidate `cache/images/` and `image_cache/` under Coil
