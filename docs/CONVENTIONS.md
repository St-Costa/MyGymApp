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

## Session-RPE prompt: apply after the reload, not before

`ActiveRoutineViewModel.registerRoutine()` reloads the session from disk multiple times
(once in `finalizeSession()`, again right before the final ECG-metrics save) — each reload
overwrites `currentSession` with whatever is on disk at that moment. The session-RPE value
the user picks in `SessionRpeDialog` is captured *before* any of those reloads run (the
dialog is shown by `requestRegisterRoutine()`, which fires before `registerRoutine()` even
starts), so it can't just be set on `currentSession` and expected to survive.

Fix: `sessionRpe` is threaded through as a parameter (`registerRoutine(sessionRpe: Int?)`)
and applied onto `updated` immediately before the final `workoutRepository.save(updated)`
call — after every reload has already happened, so nothing overwrites it. `sessionLoad`
(Foster method: RPE × duration in minutes) is derived from that same `updated` session's
`startedAt`/`completedAt` at the same point, for the same reason. Same shape of bug as the
`completionSaved` pattern above — a value set before an async reload gets silently dropped —
just solved by "pass it through" instead of "wait for the write."

The prompt is mandatory, not skippable: `SessionRpeDialog`'s `AlertDialog` has a no-op
`onDismissRequest` (blocks outside-tap dismiss) and `ActiveRoutineScreen`'s `BackHandler`
is disabled (`enabled = !uiState.showRpePrompt`) while it's showing — otherwise the
existing "back abandons the session" handler would be an unintended skip path. The confirm
button stays disabled until a chip is selected, so there is no way to close the dialog
without a valid 0-9 rating.

## Untouched-exercise guard (completing without changing anything)

Tapping "Complete Exercise"/"Complete Superset" without touching any pre-filled field no longer records last session's numbers as new work. Each set UI model (`StrengthSetUi`, `SupersetSetUi`) carries `repsTouched`/`weightTouched`, set `true` only by an explicit user action (`updateReps`/`updateWeight`/`confirmReps`/`confirmWeight`, or the stepper buttons that call them) — never by the prefill logic in `init`. Stretch sets have no prefill at all, so `done` itself (only ever flipped by an explicit toggle) is the touch signal.

In `completeExercise()`/`completeSuperset()`, if no set was touched (per exercise side, for supersets), the exercise is saved as `completed = false, sets = emptyList()` — the identical on-disk shape as an exercise the user never opened. This means every downstream consumer (tonnage sums, the per-exercise/session `%` change calculations, ghost-session detection, and the previous-session prefill's "skip all-zero sessions" walk-back) already handles it correctly with no extra code, since they all key off `completed`/non-empty `sets`.

`ActiveRoutineViewModel.markExerciseCompleted()` reloads the session from disk and now checks the reloaded exercise's actual `completed` flag before ticking off the UI row — if the screen decided it was untouched, the row stays open and `allCompleted` (which gates session finalization) stays false. This is safe: the exercise screen's own `completionSaved` → `onComplete()` effect pops the back stack unconditionally and independently, so the user still returns to `ActiveRoutineScreen` normally; only the row's completed/dimmed state differs. See `StrengthExerciseViewModel.completeExercise()`, `SupersetViewModel.buildUpdatedSession(respectTouch = true)`, `StretchExerciseViewModel.completeExercise()`, `ActiveRoutineViewModel.markExerciseCompleted()`.

`WorkoutExercise.isUntouched()` (`!completed && sets.isEmpty()`) is the canonical check for this state. `MainViewModel.computeCommonTonnage()` — the session-vs-session comparison behind the gitgraph's day color and `%` change — excludes untouched exercises from the common-exercise-ID intersection in *either* session being compared, same as it already excludes `excludeFromTonnage` (warmup/daily) exercises. Without this, an untouched exercise would count as "0 tonnage" toward that day's average and silently drag it down, even though nothing was actually skipped-badly — it just wasn't done. The per-exercise badge (`ActiveRoutineViewModel.markExerciseCompleted`) never computes a `%` for an untouched exercise at all, since it returns early before that exercise's row is touched.

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

## Previous-set preview: match type, skip zeros, inherit last set

When a strength exercise screen opens ([StrengthExerciseViewModel](../app/src/main/java/com/mygymapp/ui/screen/strengthexercise/StrengthExerciseViewModel.kt), [SupersetViewModel](../app/src/main/java/com/mygymapp/ui/screen/superset/SupersetViewModel.kt)), the grey "previous" defaults must be picked **like-with-like** along three rules:

- **Match the exercise type.** An exercise can be performed as one of three mutually exclusive types in a session, identified by the `(isDaily, excludeFromTonnage)` pair on `WorkoutExercise`: **daily** (`isDaily`), **warmup** (`excludeFromTonnage && !isDaily`), or **normal** (neither). The preview is sourced only from prior sessions where this exercise had the **same** type. Comparing only `isDaily` is wrong — it conflates warmup with normal.
- **Skip empty sessions.** Walk back through `getSessionsForExercise(id, 30)` (already sorted newest-first) and take the first matching session that has **at least one non-zero set**, so an aborted/skipped 0-0 session doesn't blank out the preview.
- **Inherit the last set for extra sets.** Sets are matched positionally (`previousSets.getOrNull(i)`). If today has more sets than the previous session recorded, the extra indices fall back to the **last non-zero previous set** (`lastMeaningfulPrev`) rather than showing 0-0.
- **Check "has current data" per set, not per exercise.** `StrengthExerciseViewModel` used to gate the whole exercise on one `hasProgress` flag (`currentSets.any { reps>0 || weight>0 }`): filling in set 1's reps flipped that flag, and every *other*, still-untouched set switched from showing its previous-session value to showing 0-0 on the next recompose/reopen — looking exactly like "the preview stopped working." Each set must decide independently, the way `SupersetViewModel` already did it: `curr = currentSet.takeIf { it.reps > 0 || it.weight > 0.0 }`, falling back to `prev` only for that one set.

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
- every set is empty (`reps == 0 && weight == 0` for strength, `done == false` for stretch, `startedAt.isBlank()` for cardio — a started-but-not-yet-finished cardio block still counts as real data)

The same ruleset lives server-side in `WorkoutRepository.cleanupGhostSessions()`, called at boot from `MainViewModel.init`, so shells created by older builds (or by a process killed before `onCleared`) still get scrubbed. `cleanupOrphanEcgFiles()` runs right after and removes `gymdata/ecg/*.ecg` whose `sessionId` has no matching `history/**/*.md`.

**Always stop the Polar stream in `onCleared`**, not only for ghost sessions: if the user finalizes the routine but doesn't tap "Registra", the stream would otherwise keep writing to the `.ecg` file until disconnect.

## Switch exercise

Lets the lifter swap a slot's exercise mid-session (machine occupied, no motivation for that
lift today) without polluting the original exercise's history with reps/weights that never
happened on it. Purely a per-session record — the routine on disk is **never** touched, so the
next session from the same routine proposes the original exercise again by default.

**Eligibility** (`WorkoutExercise.isSwitchEligible()`): a slot can be switched only while it has
zero recorded sets (`hasNoRecordedSets()` — the same per-set-type check the ghost-session guard
above uses, factored into one place) and hasn't already been switched (`substitutedFor == null`).
Once switched — or once a real set exists — the slot is locked for the rest of the session: no
re-switch, not even back to the original. Offered only for plain NORMAL slots (FORZA/STRETCH):
never for warmup, fixed-daily, or CARDIO exercises.

**Candidates**: same `bodypart` **and** same `ExerciseType` as the slot's current exercise
(`ExerciseRepository.getSwitchCandidates`), excluding every exercise already occupying a slot
in the current session (`excludeIds` — otherwise a switch could create a duplicate slot for the
same exercise). The filtered `ExercisePicker` route
(`exercises/pick?bodypart=..&type=..&excludeIds=..`) reuses the existing picker screen with
these as optional query params — all empty/no-op for the original RoutineEdit picker.

**Mutation**: `WorkoutSession.withExerciseSwitched(oldExerciseId, newExercise)` (extension
function next to `WorkoutExercise` in `WorkoutSession.kt`) re-verifies eligibility itself and
returns `this` unchanged if not eligible, so callers can check `result === session` for
"nothing happened" instead of duplicating the guard. The slot keeps its position and its
warmup/daily/tonnage flags — only `exerciseId/exerciseName/bodypart/type/sets` change, plus
`substitutedFor = oldExerciseId` recording the original for history/sync (goes to the server
as-is inside the raw session file — no `SyncApi`/`SyncWorker` change needed). This function is
the **only** place that ever writes the switch to disk. It's called from the three exercise
ViewModels — `StrengthExerciseViewModel.switchExercise`, `StretchExerciseViewModel.switchExercise`,
`SupersetViewModel.switchExercise1/2` (a superset's two sides are independent slots, one can be
switched while the other already has sets) — each of which does its own
`workoutRepository.save(...)` right after. `ActiveRoutineViewModel` does **not** call it — see
"Two ViewModels, one session file" below for why that matters.

**Re-navigation, not in-place mutation**: after a switch is saved, the exercise screen
re-navigates to the same route with the new `exerciseId` in the path (`popUpTo` the old route,
`inclusive = true`) instead of mutating its own state — these VMs load everything (history,
rep range, name, previous-session preview) one-shot in `init{}` from `SavedStateHandle`, so a
fresh VM instance for the new id is simpler and safer than replaying that logic mid-flight. The
picker result flows back through the standard `savedStateHandle.set("pickedExerciseId", id)` /
`LaunchedEffect` pattern (same as `completedExerciseId`); Superset uses two separate keys,
`pickedExerciseIdSide{1,2}`, chosen via `ExercisePicker.createRoute(..., resultKeySide = 1|2)`,
so a switch on one side can never cross-apply to the other.

**Two ViewModels, one session file — the bug this section exists to prevent regressing.** The
active-routine list (`ActiveRoutineScreen` / `ActiveRoutineViewModel`) and the exercise screen
(`StrengthExerciseScreen` / `...ViewModel`, etc.) are two **separate** ViewModel instances, each
holding its own in-memory copy of "what's in this session." `ActiveRoutineViewModel` builds its
`_uiState.exercises` list exactly once, in `init`, from the routine at session start — it has no
mechanism to notice that a *different* ViewModel later rewrote the session file out from under
it. The first working version of this feature saved the switch correctly (the file on disk was
right) but never told `ActiveRoutineViewModel` about it — so completing the switched exercise
and returning to the list still showed the **original, pre-switch exercise** as "to do," because
`markExerciseCompleted`/the row renderer were reading a `_uiState.exercises` entry that still
had the old `exerciseId`. Reproduced and fixed on-device (not caught by the build — there's no
automated test coverage here, see "Piano di verifica" in the feature's design doc).

The fix: the exercise screen, right before re-navigating, also writes
`previousBackStackEntry?.savedStateHandle?.set("switchedExerciseIds", "$oldId,$newId")` — same
`savedStateHandle`-result pattern `completedExerciseId` already uses — onto the `ActiveRoutine`
back-stack entry specifically (not its own). `AppNavigation`'s `ActiveRoutine` composable
observes that key with a `LaunchedEffect` and calls
`ActiveRoutineViewModel.applyExerciseSwitch(oldId, newId)`, which does **not** touch disk (the
exercise screen already did) — it only re-reads the already-saved session file to catch
`currentSession` up, then patches the matching `_uiState.exercises` entry in place (same
`ActiveExerciseUi` construction the old single-VM version used) and backfills the on-demand
progression baseline described below. **Any new "in-session" mutation that can originate from a
screen other than `ActiveRoutineScreen` needs this same two-step wiring** (screen saves to disk
→ notifies `ActiveRoutineViewModel` via a `savedStateHandle` result key observed on its
back-stack entry) — it will silently show stale data in the list otherwise, exactly like this
bug did, and nothing will fail loudly because the disk state is genuinely correct the whole time.

**Progression stays per-exercise**: `StrengthExerciseViewModel`/`SupersetViewModel` already
look up progression history via `WorkoutRepository.getSessionsForExercise` (global, cross-routine
— keyed off the exercise index, not the routine), so a switched-in exercise gets a correct
previous-set preview automatically once the screen re-navigates with its id. The one place that
needed extending is `ActiveRoutineViewModel`: its `previousTonnageByExercise`/
`previousBestE1RMByExercise` maps are precomputed once at session start from
`getLastSessionForRoutine` — i.e. only cover the routine's *original* exercises.
`applyExerciseSwitch` populates an on-demand entry for the new exerciseId (mirroring how
`exercisesWithPriorTonnage` is built) so `markExerciseCompleted` still finds a baseline for the
switched slot later. The precomputed fast-path for non-switched exercises is untouched.

## Cardio blocks & "no superset" guard

`ExerciseType.CARDIO` (see [POLAR.md](POLAR.md#cardio-blocks)) is a third exercise type
alongside FORZA/STRETCH, but structurally different: no pre-configured set count, no rep
range — `CardioExerciseScreen`'s "Inizia cardio"/"Termina cardio" appends one
`ExerciseSet.Cardio` block at a time. Two things fall out of that:

- **`RoutineEditScreen`'s "Sets"/rep-range fields are hidden for CARDIO, replaced by a
  single "Durata cardio" (minutes) picker** — `ActiveRoutineViewModel` builds an empty set
  list for a CARDIO exercise regardless of `RoutineExercise.sets`, since that field is
  meaningless for it. `RoutineExercise.timePerSetSeconds` is repurposed to hold the total
  configured block duration (not "per set" — CARDIO has no sets) that
  `CardioExerciseViewModel`'s countdown reads at session time (see
  [POLAR.md](POLAR.md#cardio-blocks)).
- **Cardio exercises can never be superset members.** `SupersetViewModel`/`SupersetScreen`
  only know how to interleave FORZA (reps/weight) or STRETCH (timeSeconds/done) sets — their
  exhaustive `when(exerciseType)` branches hit an explicit `error("Cardio exercises cannot be
  superset members")` for CARDIO rather than silently mishandling it. This is a defense-in-
  depth guard: the actual prevention is upstream, in `RoutineEditScreen`, where the
  "Superset" link button never appears next to a CARDIO exercise in the first place.
- **No `bodypart`.** The `BodyPartAutocomplete` field is hidden in `ExerciseEditScreen` for
  CARDIO — `bodypart` is forced to `""` on save regardless of stale UI state
  (`ExerciseEditViewModel.saveNow()`/`onCleared()`). Grouping by bodypart wouldn't mean
  anything for a cardio exercise anyway.
- **Own fixed list section, not grouped by bodypart.** `ExerciseListViewModel.applyFilter()`
  partitions CARDIO exercises out of the bodypart `groupBy` entirely, into their own
  `cardioExercises` list (sorted by name), which `ExerciseListScreen` always renders as a
  "Cardio" section pinned at the very top — ahead of the (arbitrarily-ordered, by
  `groupBy` iteration order) bodypart groups. Without this, a CARDIO exercise's blank/
  placeholder bodypart would land it in a stray group mixed in wherever `groupBy` happened
  to place it, making it easy to miss when browsing (as opposed to searching by exact name).
  Applies to both the plain exercise list and the routine-editor exercise picker, since both
  share `ExerciseListScreen`/`ViewModel` (`pickerMode` flag).

When any `when` on `ExerciseType`/`ExerciseSet` stops compiling after touching this area,
that's the compiler doing its job — every exhaustive branch is a place that genuinely needs a
decision for the new case, not a mechanical fixup. (`ExerciseCard`'s type-label `Text` was a
near-miss here: an `if/else` on `type == FORZA` rather than a `when`, so CARDIO silently
displayed "Stretch" — since it isn't an exhaustive `when`, the compiler had nothing to flag.
Prefer exhaustive `when` over `if/else` for anything branching on `ExerciseType`, even a
two-way UI choice, specifically so a third case can't silently fall into the wrong branch.)

## Polar foreground service + reconnection

`PolarStreamingService` is a foreground service (type `CONNECTED_DEVICE`) that keeps the BLE connection alive when the screen is off between sets — Android **requires** an FGS to show an ongoing notification, so it can't be removed while keeping a reliable background connection. The ongoing notification lives on channel `polar_hr_channel_min` at `IMPORTANCE_MIN` (no status-bar icon, collapsed, silent). A channel's importance is immutable after creation, so the id is versioned (`_min` suffix) and the legacy `polar_hr_channel` is deleted in `createNotificationChannel()`.

Disconnect handling in `PolarManager`:
- A `userInitiatedDisconnect` flag distinguishes `disconnect()`/`shutdown()` from an unexpected BLE drop. `lastConnectedDeviceId` is remembered for reconnection.
- On an **involuntary drop during an active session** (`!userInitiatedDisconnect && hrSeriesActive`): the FGS is kept alive (notification → "Reconnecting…"), a **high-importance heads-up alert with sound** fires on the separate `polar_hr_alert` channel (`notifyDisconnected`), and `scheduleReconnect()` retries `connectToDevice` every 10s until it returns or the user disconnects. Outside a session a drop is a quiet stop (no alert/retry — usually the user removing the strap).
- Session counters reset at **session start** (`startHrSeriesCapture`), NOT on `deviceConnected` — otherwise a mid-session reconnect would wipe accumulated `sessionCalories`/`sessionTrimp` (this is what produced the 14 kcal session on a yesterday's drop). The 60s readiness measurement is likewise skipped on a mid-session reconnect (`if (!hrSeriesActive)`).
- `stopHrSeriesCapture()` cancels reconnection and clears the alert; the disconnect alert is cleared on the next `deviceConnected`.
- The Polar SDK has its own automatic-reconnection layer beneath `PolarManager`'s (`PolarBleApi.setAutomaticReconnection`), independent of the `userInitiatedDisconnect` flag. `api.disconnectFromDevice()` alone does **not** stop the SDK from silently reconnecting to a still-powered, still-nearby H10 moments later — which fires `deviceConnected()` again and resurrects `PolarStreamingService`'s notification even after `onTaskRemoved`/`disconnect()` already tore it down. `disconnect()` calls `api.setAutomaticReconnection(false)` right before `disconnectFromDevice()`; `connectToDevice()` sets it back to `true` so a genuine mid-workout drop still auto-recovers.

## "Polar won't connect" is usually a dead CR2025, not a code bug

Before touching reconnect logic, rule out the battery — this failure mode looks exactly like a software bug and has burned real debugging time. A H10 whose cell is spent **stops advertising entirely**: it is invisible to every BLE scan, so `api.searchForDevice()` emits nothing, `deviceConnecting` never fires, and the UI shows no state change at all (not even the "searching" indicator). The reconnect loop then logs attempts forever against a device that isn't there.

The trap is that the cell still measures ~3.0V on a multimeter. A multimeter draws microamps; what kills the strap is internal resistance (~15Ω new, hundreds of ohms spent) dropping the rail below brownout during the ~10 mA transmit peak. Open-circuit voltage stays nominal to the very end. For the same reason the reported percentage is useless below ~70% — see `BATTERY_WARNING_THRESHOLD` in `PolarManager`.

Diagnosing it, in order:
1. `adb logcat | grep -i "BluetoothLeScanner\|bt_shim_scanner"` — if `Start Scan with callback` and `Scan: in shim layer started` appear, the app is doing its job and the silence is the device's.
2. `adb shell dumpsys bluetooth_manager | grep -A30 "Bonded devices"` — the H10 line shows `[ACL BR/EDR:N LE:N]` when not connected, plus a last-seen timestamp. A timestamp days old with the strap supposedly in range means it isn't transmitting.
3. Wet the electrodes and wear it. The H10 has no power switch — it wakes on conductivity between the electrodes and is genuinely off when dry, so a bench test with the strap on a table proves nothing.

Corollary for battery life: the ~400 h Polar quotes is for plain HR streaming. This app also records ECG at 130 Hz, which is a far heavier radio duty cycle, so expect substantially less — roughly 3 months at ~10 h/week of ECG-recorded sessions.

## ECG raw file: send-then-delete, no local analysis fallback

`ActiveRoutineViewModel.registerRoutine()` no longer calls `analyzeSessionEcg` at all — deep ECG analysis (Pan-Tompkins, RMSSD/SDNN/pNN50/Poincaré, arrhythmia markers) moved server-side entirely (see [SYNC.md](SYNC.md#fourth-record-type-raw-ecg), [POLAR.md](POLAR.md#post-session-analysis)). `EcgAnalyzer`/`PolarManager.analyzeSessionEcg()` still exist in the codebase, unused — left in place rather than deleted, since the raw file format they parse is unchanged and they cost nothing while dormant.

Raw `.ecg` file handling is now a simple send-then-delete, with no "keep for offline inspection" fallback (that fallback existed only because local analysis could fail and you'd want to retry/inspect it — with no local analysis at all, there's nothing to retry):

- **Sync configured + enabled**: enqueued in `EcgSyncLedgerRepository` unconditionally (no analysis result to gate on). `EcgSyncWorker` deletes the file only after a confirmed server upload (`SENT`), or after 30 days pending (`expireStale()`).
- **Sync not configured**: the file is deleted immediately — with no local analysis and no server to send it to, nothing would ever consume it.

`EcgAnalyzer.analyze()`'s structured logs (`file too small`, `only N peaks`, etc.) are dead code paths now — harmless, but don't expect to see them in `app.log` anymore since nothing calls `analyze()`.

Gzip temp-file cleanup: `EcgSyncWorker` compresses in-memory (`ByteArrayOutputStream` + `GZIPOutputStream`), not via a temp file on disk, specifically to avoid needing a `try/finally` cleanup step — simpler than the alternative and there's no leaked file to worry about if the worker is killed mid-run.

## R-peak threshold must be local, not global-max

`EcgAnalyzer.panTompkinsDetect` used to derive its adaptive threshold from `integrated.max()` over the **whole** recording (`threshold = maxVal * 0.3`, floor `maxVal * 0.1`). A gym session almost always contains a single motion artifact (strap shift, a heavy rep, adjusting the strap) whose integrated value is an order of magnitude above any real QRS complex — confirmed on real `.ecg` files pulled off-device (`adb shell run-as com.mygymapp cat files/gymdata/ecg/{id}.ecg`) where the ratio of global-max to the p99.99 percentile was ~18x on a failing recording vs ~2.7x on a working one. Once that one artifact set the global max, the threshold was pinned far above any real beat for the rest of the multi-hour session, so `analyzeSessionEcg` returned `null` (peaks < 2) on essentially every registered session — every `.ecg` file on the test device back to May had been kept as a failure.

Fix: threshold is now `trailingMax(integrated, windowSize = sampleRate * 5) * 0.5` — a rolling max over the trailing 5s, computed in O(n) via a monotonic deque (`trailingMax`). One artifact only poisons detection for ~5s around itself instead of the whole file. Validated against real recordings (Python replica of the exact algorithm) landing near the plausible HR range (~90-95 bpm during a lifting session) instead of 1 peak total.

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
