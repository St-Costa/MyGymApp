# Conventions & gotchas

Patterns that are load-bearing but non-obvious, plus things that bit us once and should not bite again. Read this before touching ViewModels, navigation callbacks, or the Polar subsystem.

## DataChangedSignal

Singleton event bus defined in [data/DataChangedSignal.kt](../app/src/main/java/com/mygymapp/data/DataChangedSignal.kt). Two `MutableSharedFlow`s:

- `exercisesChanged` — emitted by `ExerciseEditViewModel` after save/delete.
- `routinesChanged` — emitted by `RoutineEditViewModel` after save/delete.

List ViewModels collect in `init` and reload. Without this, navigating back to a list after editing would show stale data because the edit VM's `onCleared()` save runs asynchronously.

**When to emit:** only after the repository `save()` / `delete()` has completed. Emitting before risks the collector re-reading stale cache.

## `onCleared()` save

Edit screens auto-save on back. This runs in `onCleared()`, which happens *after* `viewModelScope` has been cancelled. Two things follow:

1. Don't use `viewModelScope` — it's already dead.
2. Don't use `runBlocking` — it ANRs the main thread.

The pattern is a dedicated scope:

```kotlin
private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

override fun onCleared() {
    super.onCleared()
    if (savedExplicitly) return
    clearScope.launch {
        try {
            repository.save(model)
            dataChangedSignal.notify…Changed()
        } finally {
            clearScope.cancel()
        }
    }
}
```

The `cancel()` is inside `finally` so the save always completes before the scope is torn down. Calling `cancel()` outside the launch would race with the save. See [ExerciseEditViewModel.kt](../app/src/main/java/com/mygymapp/ui/screen/exerciseedit/ExerciseEditViewModel.kt).

## `completionSaved` pattern

Exercise screens (`StrengthExerciseScreen`, `StretchExerciseScreen`, `SupersetScreen`) must **not** call `onComplete()` directly from a button. The save is async, and `ActiveRoutineViewModel.markExerciseCompleted()` reloads the session from disk — if we navigate before the write flushes, `totalTonnage = 0`.

Pattern:

```kotlin
// ViewModel
fun completeExercise() {
    viewModelScope.launch {
        workoutRepository.save(session)
        _completionSaved.value = true
    }
}

// Screen
val completionSaved by viewModel.completionSaved.collectAsState()
LaunchedEffect(completionSaved) {
    if (completionSaved) onComplete()
}
```

Navigation happens only after disk confirms the write. `onCleared()` is reserved for the mid-exercise back case and skips re-saving when `completionSaved` is already true.

## `onBack` vs `onComplete`

The three exercise screens take two distinct navigation callbacks:

- `onBack` — plain `popBackStack()`, no completion signal. User walked away mid-exercise.
- `onComplete` — fired via the `completionSaved` flow. Sets `completedExerciseId` (or `completedSupersetIds` for supersets) on `previousBackStackEntry.savedStateHandle`. `AppNavigation` reads that and calls `markExerciseCompleted`.

Do not collapse them into one callback — the side effects are different.

## `AutoSaveTextField` flush on dispose

[AutoSaveTextField](../app/src/main/java/com/mygymapp/ui/components/AutoSaveTextField.kt) debounces writes by 500 ms. If the composable leaves composition mid-debounce (user taps back quickly), the pending save would be lost. A `DisposableEffect(Unit) { onDispose { pendingSave?.let(currentOnSave) } }` flushes it. `rememberUpdatedState(onSave)` prevents capturing a stale callback.

## Cache invalidation

When a repository caches a derived value, the cache must be invalidated wherever the underlying data changes. Two concrete cases in this codebase:

- [`ExerciseRepository.getBodyparts()`](../app/src/main/java/com/mygymapp/data/repository/ExerciseRepository.kt) caches the sorted-distinct bodypart list. `save()` and `delete()` clear `bodypartsCache` before returning. If you add a new write path (e.g. bulk import), clear it there too.
- Image caches: no invalidation. `cache/images/` is keyed by `sha256(url)` so new URLs get new files; editing an URL that already existed keeps the old cached image forever. That is intentional — content behind a URL rarely changes and the workaround (clear app storage) is acceptable for a personal project.

## Exercise index batching

`WorkoutRepository` updates `history/_idx/{exerciseId}.idx` through an internal `ExerciseIndexBatch`: writes are collected in a `Map<exerciseId, Set<relPath>>` and flushed in one pass via `flushExerciseIndexBatch()`. Both `save()` and `migrateOldSessionFiles()` use this batch — each `.idx` is read+written at most once, even if the same exercise appears many times. If you add a new call site that mutates the index (rebuild, selective re-index, etc.), use the same pattern instead of calling `addToExerciseIndex()` per occurrence.

## Shared grouping for supersets

Both `RoutineEditViewModel.buildExerciseSegments` and `ActiveRoutineScreen.buildExerciseGroups` produce a list of "pair or single" items using the same rule: if `supersetWithNext` is true on element *i* and *i+1* exists, emit a pair; otherwise emit a single. The logic is one place now — [`ui/util/SupersetGrouping.kt`](../app/src/main/java/com/mygymapp/ui/util/SupersetGrouping.kt) — parametrized on domain-specific sealed classes. Don't re-implement this loop.

## Exercise type color

Use `ExerciseType.accentColor()` from [`theme/Color.kt`](../app/src/main/java/com/mygymapp/ui/theme/Color.kt) to resolve FORZA → `ForzaColor`, STRETCH → `StretchColor`. Don't write the `when` block inline.

## Typed ID prefixes

- Exercises: `ex-{8hex}`
- Routines: `rt-{8hex}`
- Sessions: bare `{8hex}`

Generated with `UUID.randomUUID().toString().replace("-", "").take(8)`. You can tell from a filename or a log line whether an ID refers to an exercise or a routine — that saves time when debugging the exercise index.

## Stopwatch idempotency

`StopwatchService` handles repeated `ACTION_START` by calling `handler.removeCallbacks(tickRunnable)` before `postDelayed`. Without that, a duplicate start would queue two tickers and double every vibration.

## Polar disposables

Every `startScan` / `startHrStreaming` / `startEcgStreamingInternal` begins with `xxxDisposable?.dispose()`. If you add a new Rx subscription, follow suit — skipping it leaks the previous subscription and can cause overlapping emissions when a device reconnects.

## ScrollPickerInput long-press detection

`waitForUpOrCancellation()` returns `null` for **both** gesture-cancellation (a scroll stole the touch) and genuine timeout (a long press). Distinguishing them requires a `gestureCancelled` flag set inside the `withTimeoutOrNull` block before the timeout fires. Treating both cases as long-press caused scrolling over the button to trigger the repeat loop.

## Common-exercise tonnage

Gitgraph / session comparisons don't use `totalTonnage` directly. They use `computeCommonTonnage(s1, s2)` which restricts to exercises present in *both* sessions. Otherwise adding a new exercise to a routine reads as a sudden tonnage jump. Falls back to `totalTonnage` only when there is no overlap. All tonnage readers also filter `!excludeFromTonnage` (see below).

## Fixed daily exercise container

A reserved routine — `id: rt-fixeddaily`, name `Fixed daily exercise`, `day: ""` — represents exercises performed every day. Constants live in [Routine.kt](../app/src/main/java/com/mygymapp/data/model/Routine.kt) (`FIXED_DAILY_ROUTINE_ID` / `FIXED_DAILY_ROUTINE_NAME`).

- **Seeded, not created by the user**: `RoutineRepository.ensureFixedDailyRoutine()` runs inside `ensureLoaded()` (after the directory scan, under the mutex). Idempotent — once written it's reloaded from disk.
- **Non-deletable / non-disableable**: `delete()` early-returns for the reserved id; `getAll()` pins it first via `compareByDescending { it.id == FIXED_DAILY_ROUTINE_ID }`. The list screen hides the enabled switch (shows a lock), the edit screen hides the day picker + delete and makes the name read-only. `RoutineListViewModel` has defensive guards too.
- **Container only, not startable**: it has no `day` so WeekView (the only start path) never lists it; `ActiveRoutineViewModel.init` also early-returns for the reserved id.
- **Injected into every session**: `ActiveRoutineViewModel.init` builds the session as `warmup → fixed-daily → normal`, skipping a fixed-daily exercise whose `exerciseId` already appears in the started routine (the exercise-detail flow keys by `exerciseId`, so duplicate ids in one session are unsupported).

## Warmup exercises & `excludeFromTonnage`

`RoutineExercise.isWarmup` marks the contiguous **leading prefix** of a routine's exercise list as warmup. The editor models this as a positional `warmupCount` (number of exercises above a divider line) snapped to segment boundaries so a superset pair is never split; `buildRoutine` writes `isWarmup = index < warmupCount`.

Warmup **and** fixed-daily exercises are excluded from tonnage. The exclusion is resolved when the session is built and persisted per-exercise as `WorkoutExercise.excludeFromTonnage`, so every tonnage reader — `finalizeSession`, `SessionProgressViewModel`, `MainViewModel.computeCommonTonnage`, and the active-session previous/historical comparisons — just filters `!excludeFromTonnage`. Cardio metrics are session-global (PolarManager) and intentionally still include these exercises.

## Image caches — two of them

- `gymdata/cache/images/` is [ImageCacheRepository](../app/src/main/java/com/mygymapp/data/repository/ImageCacheRepository.kt) — persistent, never evicted, used for exercise link images referenced from markdown.
- `gymdata/image_cache/` is Coil's LRU disk cache, 100 MB cap.

They are not interchangeable. See [STORAGE.md](STORAGE.md#root-layout).

## YouTube in WebView: don't

`WebView + YouTube embed` is not viable on Android — MediaCodec writes decoded frames to a driver-level Surface that bypasses all `LAYER_TYPE` settings and Compose compositing (confirmed with `adb logcat`: `setOutputSurface BAD_INDEX`, codec runs but frames are invisible). Use `CustomTabsIntent` from `androidx.browser:1.8.0` — [MediaPreview](../app/src/main/java/com/mygymapp/ui/components/MediaPreview.kt) does this.

## Kotlin getter clash

Don't name a private property `fooBar` and a function `getFooBar()` — Kotlin generates a getter `getFooBar()` for the property that clashes with the function. Use a distinct name.

## Ghost session prevention

Entering an active routine creates the `.md` file eagerly (so `StrengthExerciseViewModel` can locate the session by `sessionId`). If the user backs out before filling any data, the empty shell would be persisted — prior to Phase 16 this left 21/76 sessions as ghosts on disk.

`ActiveRoutineViewModel.onCleared()` reloads the session and deletes it if **all** hold:
- `completedAt.isBlank()`
- no exercise has `completed == true`
- every set is empty (`reps == 0 && weight == 0` for strength, `done == false` for stretch)

The same ruleset lives server-side in `WorkoutRepository.cleanupGhostSessions()`, called at boot from `MainViewModel.init`, so shells created by older builds (or by a process killed before `onCleared`) still get scrubbed. `cleanupOrphanEcgFiles()` runs right after and removes `gymdata/ecg/*.ecg` whose `sessionId` has no matching `history/**/*.md`.

**Always stop the Polar stream in `onCleared`**, not only for ghost sessions: if the user finalizes the routine but doesn't tap "Registra", the stream would otherwise keep writing to the `.ecg` file until disconnect.

## Polar foreground service + reconnection

`PolarStreamingService` is a foreground service (type `CONNECTED_DEVICE`) that keeps the BLE connection alive when the screen is off between sets — Android **requires** an FGS to show an ongoing notification, so it can't be removed while keeping a reliable background connection. The ongoing notification lives on channel `polar_hr_channel_min` at `IMPORTANCE_MIN` (no status-bar icon, collapsed, silent). A channel's importance is immutable after creation, so the id is versioned (`_min` suffix) and the legacy `polar_hr_channel` is deleted in `createNotificationChannel()`.

Disconnect handling in `PolarManager`:
- A `userInitiatedDisconnect` flag distinguishes `disconnect()`/`shutdown()` from an unexpected BLE drop. `lastConnectedDeviceId` is remembered for reconnection.
- On an **involuntary drop during an active session** (`!userInitiatedDisconnect && hrSeriesActive`): the FGS is kept alive (notification → "Reconnecting…"), a **high-importance heads-up alert with sound** fires on the separate `polar_hr_alert` channel (`notifyDisconnected`), and `scheduleReconnect()` retries `connectToDevice` every 10s until it returns or the user disconnects. Outside a session a drop is a quiet stop (no alert/retry — usually the user removing the strap).
- Session counters reset at **session start** (`startHrSeriesCapture`), NOT on `deviceConnected` — otherwise a mid-session reconnect would wipe accumulated `sessionCalories`/`sessionTrimp` (this is what produced the 14 kcal session on a yesterday's drop). The 60s readiness measurement is likewise skipped on a mid-session reconnect (`if (!hrSeriesActive)`).
- `stopHrSeriesCapture()` cancels reconnection and clears the alert; the disconnect alert is cleared on the next `deviceConnected`.

## ECG analysis: keep the raw file on failure

`ActiveRoutineViewModel.registerRoutine()` runs `analyzeSessionEcg` and then deletes the raw `.ecg`. Delete ONLY when `ecgResult != null && ecgResult.hasAnything` (i.e. ≥1 beat detected). On failure (file too short, too few peaks, too few valid RR intervals) the `.ecg` is preserved so it can be inspected offline. `EcgAnalyzer.analyze()` emits structured logs (`file too small`, `only N peaks`, `only N valid RR intervals`, or the success line `N peaks → M valid RR intervals`) to pinpoint the cause.

## YAML compaction in session frontmatter

Two rules applied in `WorkoutParser.toMarkdown` + `MarkdownParser.formatValue`:

1. **2-decimal rounding for Doubles/Floats** at serialization. Store full precision in memory, write a readable value to disk. Applies uniformly — set `weight: 14.0` stays `14.0`, `vo2max: 46.142857142857146` becomes `46.14`.
2. **Omit-zero for ECG/HRV/recovery fields** (`ecgBeats`, `ecgDurationSec`, `ecgAvgHr`, `ecgSessionRmssd`, `ecgPacCount`, `ecgPauseCount`, `ecgIrregularBeats`, `cardiacDriftBpmMin`, `restingHr`, `hrr60s`, `sdnn`, `pnn50`, `poincareSd1`, `poincareSd2`, `poincareRatio`, `afibSuspicionEpisodes`): not serialized when zero. `tonnageByBodypart` also drops zero-valued entries.

The reader (`WorkoutParser.fromMarkdown`) defaults missing numeric fields to 0 via `as? Number ?: 0.0`, so older files stay readable and newly omitted fields round-trip cleanly.

## Rep-range invariant

`repRangeMin > repRangeMax` used to slip through. Editors now symmetric-clamp:
- raising min above max pulls max up with it,
- lowering max below min pulls min down with it.

Applies in both `ExerciseEditViewModel.onRepMin/MaxChange` and `RoutineEditViewModel.updateExerciseRepMin/Max`. One-shot boot migration (`fixInvalidRepRanges` in `ExerciseRepository` and `RoutineRepository`, guarded by `.reprange_fixed` sentinel files in `exercises/` and `routines/`) repairs any pre-existing `min > max` by setting `max = min`.

## Gradle wrapper

`gradle/wrapper/gradle-wrapper.jar` was copied from `~/.gradle/caches/` — there is no global Gradle installed on this machine. If the jar ever goes missing, pull it from a cached distribution rather than running `gradle wrapper` (which requires Gradle to be installed in the first place).

## ANDROID_HOME

Android Studio reads `sdk.dir` from `local.properties`. Command-line builds need `ANDROID_HOME=~/Android/Sdk` — see [README.md](../README.md#building).
