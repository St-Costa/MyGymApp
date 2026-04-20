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

Gitgraph / session comparisons don't use `totalTonnage` directly. They use `computeCommonTonnage(s1, s2)` which restricts to exercises present in *both* sessions. Otherwise adding a new exercise to a routine reads as a sudden tonnage jump. Falls back to `totalTonnage` only when there is no overlap.

## Image caches — two of them

- `gymdata/cache/images/` is [ImageCacheRepository](../app/src/main/java/com/mygymapp/data/repository/ImageCacheRepository.kt) — persistent, never evicted, used for exercise link images referenced from markdown.
- `gymdata/image_cache/` is Coil's LRU disk cache, 100 MB cap.

They are not interchangeable. See [STORAGE.md](STORAGE.md#root-layout).

## YouTube in WebView: don't

`WebView + YouTube embed` is not viable on Android — MediaCodec writes decoded frames to a driver-level Surface that bypasses all `LAYER_TYPE` settings and Compose compositing (confirmed with `adb logcat`: `setOutputSurface BAD_INDEX`, codec runs but frames are invisible). Use `CustomTabsIntent` from `androidx.browser:1.8.0` — [MediaPreview](../app/src/main/java/com/mygymapp/ui/components/MediaPreview.kt) does this.

## Kotlin getter clash

Don't name a private property `fooBar` and a function `getFooBar()` — Kotlin generates a getter `getFooBar()` for the property that clashes with the function. Use a distinct name.

## Gradle wrapper

`gradle/wrapper/gradle-wrapper.jar` was copied from `~/.gradle/caches/` — there is no global Gradle installed on this machine. If the jar ever goes missing, pull it from a cached distribution rather than running `gradle wrapper` (which requires Gradle to be installed in the first place).

## ANDROID_HOME

Android Studio reads `sdk.dir` from `local.properties`. Command-line builds need `ANDROID_HOME=~/Android/Sdk` — see [README.md](../README.md#building).
