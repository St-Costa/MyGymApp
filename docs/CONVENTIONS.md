# Conventions & gotchas

Patterns that are load-bearing but non-obvious, plus things that bit us once and should not bite again. Read this before touching ViewModels, navigation callbacks, or the Polar subsystem.

## ⛔ Back up on-device data before ANY install/uninstall/test op

The user's entire data set is **only** in `/data/data/com.mygymapp/files/gymdata/` on the
phone — not in git, not on this machine. A `pm uninstall` / `pm clear` / differently-signed
APK install / **any `:baseline-profile` or `connected…AndroidTest` run**
(`uninstall_after_test: true`) wipes it for good. It has already cost real data once. Pull a
verified `tar` backup first — full procedure and restore command in the banner at the top of
[CLAUDE.md](../CLAUDE.md) and [STORAGE.md](STORAGE.md). No backup ⇒ don't proceed, ask first.

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
    exerciseCompleted = true                 // stops onCleared() from re-saving
    completionJob = clearScope.launch {       // NOT viewModelScope — see below
        workoutRepository.save(session)
        _completionSaved.value = true
    }
}

override fun onCleared() {
    if (exerciseCompleted) {
        clearScope.launch {                   // join the completion save, then tear down
            try { completionJob?.join() } finally { clearScope.cancel() }
        }
        return
    }
    // …mid-exercise back case: save completed = false on clearScope, then clearScope.cancel()
}

// Screen
val completionSaved by viewModel.completionSaved.collectAsState()
LaunchedEffect(completionSaved) {
    if (completionSaved) onComplete()
}
```

Navigation happens only after disk confirms the write.

**The completion save runs on `clearScope`, not `viewModelScope`.** Navigation tears the VM down almost immediately after the tap; if the save were on `viewModelScope` a process death (or fast enough teardown) mid-write would cancel it half-done and lose `completed = true` — and `onCleared()` can't recover it because `exerciseCompleted` is already set. `clearScope` (`SupervisorJob`, cancelled only by `onCleared`) survives VM teardown. `onCleared()` `join()`s `completionJob` before calling `clearScope.cancel()`, so the write always finishes. Applies to all three exercise VMs (`StrengthExerciseViewModel`, `StretchExerciseViewModel`, `SupersetViewModel`). The `switchExercise*()` paths still use `viewModelScope` — a separate, pre-existing race not covered here.

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

**Touching any one value = the whole exercise is done, with every shown number saved.** Tap "Complete Exercise"/"Complete Superset" after touching at least one reps/weight picker (or, for stretch, toggling at least one set `done`), and the exercise closes as `completed = true` with **all** its sets persisted as shown — the touched ones *and* the still-grey pre-filled ones. This is the intended behaviour for fixed-load warmup/daily exercises: the lifter has no reason to re-enter "8 × 30" every session, so tapping any single value confirms "yes, I did this at the numbers on screen".

**Touching nothing = completed-empty (skipped), still closed out.** Tap Complete without touching anything and the exercise is saved as `completed = true, completedEmpty = true`. Its `sets` still carry the grey pre-fill that was on screen (not `emptyList()`) so the next session's prefill walk-back finds real numbers instead of restarting at 0×0 — the fixed-load warmup/daily regression that Phase 74 introduced by conflating "empty flag" with "empty sets list", fixed properly in Phase 81. The active-routine row renders it with the neutral **skipped** styling — grey border (`SkippedColor`) + `Close` icon — instead of a tonnage change, and all tonnage / history / PR math skips it. For supersets this is per-member: `buildUpdatedSession(completed = true)` closes every chain member (2–3 of them), flagging any untouched member `completedEmpty = true` independently, always persisting that member's shown sets.

The **"Complete Exercise" button label and colour reflect the outcome**: with any value touched it reads "Completa esercizio" on the primary colour; with nothing touched it reads "Segna come non eseguito" on `surfaceVariant`. So the lifter knows before tapping whether this will record work or a skip. (Superset: keyed on whether *any* member was touched.)

Touch tracking: each set UI model (`StrengthSetUi`, `SupersetSetUi`) carries `repsTouched`/`weightTouched`, set `true` only by an explicit user action (`updateReps`/`updateWeight`/`confirmReps`/`confirmWeight`, or the stepper buttons that call them) — never by the prefill logic in `init`. Stretch sets have no prefill, so `done` itself (only ever flipped by an explicit toggle) is the touch signal.

`ActiveRoutineViewModel.markExerciseCompleted()` reloads the session from disk and propagates the reloaded exercise's `completedEmpty` flag to the UI row — a completed-empty exercise ticks off the active list like any other (it *is* `completed`), it just shows the skipped styling and contributes no tonnage badge. `allCompleted` (which gates session finalization) counts it as done. See `StrengthExerciseViewModel.completeExercise()`, `SupersetViewModel.buildUpdatedSession(completed = true)`, `StretchExerciseViewModel.completeExercise()`, `ActiveRoutineViewModel.markExerciseCompleted()`.

### `isUntouched()` means "not performed work" — `completed = false` OR `completedEmpty = true`

`completedEmpty` always implies `completed` (the lifter did tap Complete). But a completed-empty exercise carries no performed work, and its `sets` hold only the retained pre-fill — so "empty sets" is not a usable proxy either way. Therefore:

- **`WorkoutExercise.isUntouched()` is `!completed || completedEmpty`.** `computeCommonTonnage()` (`GitgraphHistoryCalculator`) — the session-vs-session comparison behind the gitgraph's day colour and `%` change — excludes `isUntouched()` exercises from the common-exercise-ID intersection in *either* session, alongside `excludeFromTonnage` (warmup/daily). Without this a skipped exercise's pre-fill would count as "0-vs-something" work and drag that day's average around.
- **Ghost-session detection is `completedAt.isBlank() && exercises.none { it.completed }`** — in both `ActiveRoutineViewModel.onCleared()` and `WorkoutRepository.isGhostSession()`. A completed-empty exercise *does* keep a session alive (the lifter deliberately tapped Complete on it), same as it did before Phase 74. Merely *opening* a pre-filling daily and backing out still doesn't.
- `hasNoRecordedSets()` / `isSwitchEligible()` are unchanged — still per-set-value checks — and `isSwitchEligible()`'s `!completed` clause correctly locks a completed-empty slot too.

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

Both `RoutineEditViewModel.buildExerciseSegments` and `ActiveRoutineScreen.buildExerciseGroups` produce a list of "chain or single" items using the same rule: walk left-to-right, folding a maximal run of consecutive elements where `supersetWithNext` is true on every one but the last into a single group; everything else is a single. The logic is one place — [`ui/util/SupersetGrouping.kt`](../app/src/main/java/com/mygymapp/ui/util/SupersetGrouping.kt) — parametrized on domain-specific sealed classes (`ExerciseSegment.Superset(indices)`, `ExerciseGroup.Superset(exercises)`). Don't re-implement this loop.

**The cap is on the grouping, not the flag.** A superset chain holds at most `MAX_SUPERSET_SIZE` (= 3) exercises. `groupSupersets` stops extending a run at 3 even if more `true` flags follow, so a hand-edited YAML with 4+ consecutive `supersetWithNext: true` is split (`[A,B,C] [D]`) and D's flag is simply ignored. The routine editor never *creates* an over-long chain: `RoutineEditViewModel.toggleSuperset` computes the size of the merged segment before linking and no-ops if it would exceed 3, and `RoutineEditScreen` hides the link button in the same case. `SupersetViewModel`/`SupersetScreen` are arity-generic — they take a `List<String>` of member ids (nav arg `exerciseIds`, comma-separated) and a `members: List<SupersetMemberUi>`, interleaving sets round-by-round across all members.

**Removing an exercise from a section must break the links around it, not re-link them.** `ActiveRoutineViewModel.init` drops slots from a section before grouping — a fixed-daily exercise that's also in the routine is filtered out of the daily section, and a slot whose exercise was since deleted is dropped from any section. A naive `filter` there leaves the *predecessor's* `supersetWithNext = true` pointing at whatever now follows, so `groupSupersets` fabricates a superset in the live session that the routine never contained (Phase 85 bug: the two exercises that surrounded a removed daily member got paired). Use [`filterBreakingSupersetLinks`](../app/src/main/java/com/mygymapp/ui/util/SupersetGrouping.kt) (same file as `groupSupersets`): it drops the failing elements *and* clears the forward link of any survivor whose original successor was dropped. The pre-existing per-section `clearTailLink` (clears the last element's link so no pair spans a section boundary) still runs after it.

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
- **Injected into every session**: `ActiveRoutineViewModel.init` builds the session as `fixed-daily → normal → warmup`, skipping a fixed-daily exercise whose `exerciseId` already appears in the started routine (the exercise-detail flow keys by `exerciseId`, so duplicate ids in one session are unsupported). Warmup was moved to the bottom (was: first) so the session screen leads with the exercises that actually count.

## Warmup exercises & `excludeFromTonnage`

`RoutineExercise.isWarmup` marks the contiguous **leading prefix** of a routine's exercise list as warmup. The editor models this as a positional `warmupCount` (number of exercises above a divider line) snapped to segment boundaries so a superset chain is never split; `buildRoutine` writes `isWarmup = index < warmupCount`.

Warmup **and** fixed-daily exercises are excluded from tonnage. The exclusion is resolved when the session is built and persisted per-exercise as `WorkoutExercise.excludeFromTonnage`, so every tonnage reader — `finalizeSession`, `SessionProgressViewModel`, `MainViewModel.computeCommonTonnage`, and the active-session previous/historical comparisons — just filters `!excludeFromTonnage`. Cardio metrics are session-global (PolarManager) and intentionally still include these exercises.

## Bodyweight load: materialize `weight` at completion, never at read time

A bodyweight `Exercise` (`isBodyweight = true`) carries a mandatory `bwLoadPercent` ∈
{25, 50, 75, 100} — how much of the lifter's body weight the movement loads (squat 100,
plank 75, reverse sit-up 50, tibialis raise 25). Legacy exercise files with `isBodyweight`
but no percent migrate to **75** in `ExerciseParser` (the editor forces one of the four via
`FilterChip`s and never persists 0 for a bodyweight exercise).

When an exercise screen writes its sets on completion (or on mid-exercise back-out),
`StrengthExerciseViewModel.buildStrengthSets` / `SupersetViewModel.buildUpdatedSession`
**materialize** each bodyweight set's `weight` = `materializeBodyweightWeight(bwLoadPercent,
bodyWeightKg)` — `bwLoadPercent%` of the lifter's body weight, rounded to 0.5 kg, where the
body weight is `ScaleHistoryRepository.getLatestWeightOnOrBefore(sessionDate)` (most recent
scale weigh-in on or before the session date). The raw inputs are kept on the set as
`bwLoadPercent` + `bwBaseWeightKg` for audit only.

The point: **every downstream `reps * weight` reader** — `finalizeSession`, the historical
`sessionTonnage`/`sessionBestE1RM` charts, `TonnagePr`, `bestEstimated1RM`, and the server —
keeps working unchanged, because `weight` is a real positive number by the time anything
reads it. Do **not** add "if bodyweight, look up the weigh-in and multiply" at any read
site: historical sessions freeze the body weight that was current then, and a read-time
lookup would be both wrong (today's weight) and slow. If no weigh-in was on file at
completion, `weight` stays `0.0` and `isBodyweight` still guards the set from being read as
"untouched" (see [SYNC.md](SYNC.md)). The per-set weight picker and the "Kg" column are
hidden entirely for bodyweight exercises in both the strength and superset screens.

`materializeBodyweightWeight` lives in `TonnageMath.kt` (pure, no Context/IO) and is
unit-tested in `TonnageMathTest`.

## Previous-set preview: match type, skip zeros, inherit last set

When a strength exercise screen opens ([StrengthExerciseViewModel](../app/src/main/java/com/mygymapp/ui/screen/strengthexercise/StrengthExerciseViewModel.kt), [SupersetViewModel](../app/src/main/java/com/mygymapp/ui/screen/superset/SupersetViewModel.kt)), the grey "previous" defaults must be picked **like-with-like** along these rules:

- **Match the slot context.** An exercise can be performed as one of three mutually exclusive kinds in a session, exposed as `WorkoutExercise.slotContext` (`SlotContext.NORMAL` / `WARMUP` / `DAILY`): **daily** (`isDaily`), **warmup** (`excludeFromTonnage && !isDaily`), or **normal** (neither). Note `isDaily` wins — a fixed-daily entry also carries `excludeFromTonnage = true` but is `DAILY`, not `WARMUP`. The preview is sourced only from prior sessions where this exercise had the **same** `slotContext`. Comparing only `isDaily` is wrong — it conflates warmup with normal; filtering on `!excludeFromTonnage` is wrong — it conflates daily with warmup.
- **The all-time PR badge uses the same `slotContext` filter.** The "PR: reps × weight" line is the highest-tonnage set *ever* for this exercise, but only within the current slot's `slotContext`. Before Phase 77 it filtered `!excludeFromTonnage`, so a fixed-daily slot's PR was pulled from the exercise's unrelated *routine* appearances (and vice versa) — a daily run at 13×16 for weeks could show "PR: 11 × 16", below its own pre-filled numbers.
- **Bodyweight exercises show the PR against body weight, not the materialized load.** For an `isBodyweight` exercise the PR badge reads `reps × bwBaseWeightKg` — the lifter's body weight when that set was logged — not `reps × weight` (which for bodyweight is only `bwLoadPercent%` of that number, e.g. `75% × 80 = 60`). PR *selection* is still by materialized `reps × weight`, so the chosen set doesn't change; only its display does. The body weight rides along in `PreviousSet.bwBaseWeightKg` / `TonnagePr.bwBaseWeightKg` (sidecar schema v2). If it's unknown (legacy set, no weigh-in on file) the strength screen shows `PR: reps` only and the superset badge is hidden.
- **Two PR badges: the best-e1RM set on top of the best-tonnage set.** As of Phase 92 the screens show two centered lines — `RM`ᴾᴿ`: reps × weight` stacked 2dp above `T`ᴾᴿ`: reps × weight` (the `PR` is a small subscript; shared `PrBadge` composable in `CommonComposables.kt`). The `RM` line is the single set with the highest Epley estimate (`weight × (1 + reps/30)`) ever recorded in this `slotContext` — a *separate* record from the tonnage PR (`ContextStats.rmPr` vs `.pr`, sidecar schema v3), since a heavy low-rep single can win one and not the other. **Both badges show the set itself (`reps × weight`), not the computed 1RM** — the user wanted to see which set produced the record. The e1RM comparison is done in `ExerciseStatsCalculator` (`estimate1RM` from `TonnageMath.kt`); the screen never computes it. For a bodyweight exercise the `RM` badge is hidden unless `rmPr.bwBaseWeightKg` is known (the stored `weight` is materialized load otherwise). `RmPr` / `SupersetMemberUi.prE1rm{Reps,Weight}` carry the raw set to the screen.
- **Both come from the stats sidecar now, not a history scan.** As of Phase 79 the ViewModels read `workoutRepository.getExerciseStats(id).forContext(slotContext)` — a single small `history/_stats/{id}.yaml` read — instead of parsing every session file that contains the exercise (`getSessionsForExercise` ×2, once for previous and once for PR; ×N for a superset). The sidecar already stores `previousSets` (most recent session with real data), `previousSessionDate`, `pr`, `rmPr` (best estimated 1RM), and `hasPriorRealTonnage`, all pre-split by context. See [STORAGE.md](STORAGE.md#exercise-stats-sidecar-history_statsexerciseidyaml). The rules above are the sidecar's derivation rules — implemented once in `ExerciseStatsCalculator`, so nothing about *what* "previous"/"PR" mean changed, only where it's computed. `ActiveRoutineViewModel.exercisesWithPriorTonnage` uses the same sidecar's `hasPriorRealTonnage`.
- **"Previous" is also split by weighting approach (bodyweight vs manual load), not just by slot context.** Sidecar schema v4: `previousSets` now holds the most recent real *manual-load* session, `previousSetsBodyweight` the most recent real *bodyweight* one (materialized `weight`). Every consumer that compares today's work against history — the strength/superset grey pre-fill and `ActiveRoutineViewModel`'s change badge — picks via `ContextStats.previousSetsFor(exercise.isBodyweight)`. Reason: an exercise reconfigured from manual load to bodyweight (Copenhagen adduction went from a `weight: 1.0` placeholder to `bwLoadPercent 75` of ~78 kg) was comparing ~1947 kg against ~32 kg and rendering a **+5005%** change badge, which then line-wrapped the fixed-width badge column into an unreadable smear. When the matching-approach slot is empty the exercise reads as "primo dato" (and `TonnageAndRmChange` also clamps any `|pct| ≥ 1000` to `>999%` as a backstop). `pr` / `rmPr` / `hasPriorRealTonnage` deliberately stay all-time across both approaches.
- **Inherit the last set for extra sets.** Sets are matched positionally (`previousSets.getOrNull(i)`). If today has more sets than the previous session recorded, the extra indices fall back to the **last non-zero previous set** (`lastMeaningfulPrev`) rather than showing 0-0. (This stays in the ViewModel — the sidecar stores the raw previous sets, not the positional fill.)
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

The same ruleset lives server-side in `WorkoutRepository.runMaintenance()`, called at boot from `MainViewModel.init`, so shells created by older builds (or by a process killed before `onCleared`) still get scrubbed. `runMaintenance()` also prunes sessions older than 3 months and removes `gymdata/ecg/*.ecg` whose `sessionId` has no matching `history/**/*.md` — all three checks happen in one walk over `history/` that parses each session file only once (previously three separate walks/parses; merged in Phase 68 for app-start speed), and the whole pass is throttled to at most once per 12h via an mtime sentinel (`history/_idx/.last_maintenance`).

**Always stop the Polar stream in `onCleared`**, not only for ghost sessions: if the user finalizes the routine but doesn't tap "Registra", the stream would otherwise keep writing to the `.ecg` file until disconnect.

**Deleting a ghost session must not touch the stats sidecars.** `WorkoutRepository.delete()` / `runMaintenance()` rebuild an exercise's `_stats/` sidecar only when the removed session was **completed** (`completedAt.isNotBlank()`) — a ghost/incomplete session never fed a sidecar (`save()` only merges completed sessions), so rebuilding on ghost cleanup is pure waste. It's also actively harmful: ghost cleanup runs on the `clearScope` after every back-out, and a full per-exercise history rebuild there was holding the repo mutex long enough to stall the *next* routine open's `save()` by up to ~2.4 s (Phase 79). Same reasoning as the [`completionSaved` mutex discipline](#completionsaved-pattern) — background work on the shared lock is on the critical path of the next screen.

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
`SupersetViewModel.switchExerciseAt(memberIndex, id)` (a superset's members are independent
slots, one can be switched while another already has sets) — each of which does its own
`workoutRepository.save(...)` right after. `ActiveRoutineViewModel` does **not** call it — see
"Two ViewModels, one session file" below for why that matters.

**Re-navigation, not in-place mutation**: after a switch is saved, the exercise screen
re-navigates to the same route with the new `exerciseId` in the path (`popUpTo` the old route,
`inclusive = true`) instead of mutating its own state — these VMs load everything (history,
rep range, name, previous-session preview) one-shot in `init{}` from `SavedStateHandle`, so a
fresh VM instance for the new id is simpler and safer than replaying that logic mid-flight. The
picker result flows back through the standard `savedStateHandle.set("pickedExerciseId", id)` /
`LaunchedEffect` pattern (same as `completedExerciseId`); Superset uses one key per member,
`pickedExerciseIdSide{N}` (N = 1-based member position), chosen via
`ExercisePicker.createRoute(..., resultKeySide = N)`, so a switch on one member can never
cross-apply to another.

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
- **Drop diagnostics.** `deviceDisconnected` logs `rssi=` (the strap's last-known value from `PolarDeviceInfo`, usually 0/stale on Android — logged anyway) and `hrGap=` (ms since the last HR sample landed). The gap is the only "why" Android's BLE stack actually gives us: a gap that grew for seconds before the link died ⇒ range/signal fade; a fresh sample right up to the drop ⇒ 2.4 GHz interference or a lost electrode contact. Each involuntary mid-session drop is also pushed onto `sessionDrops` and exposed via `disconnectStats: StateFlow<DisconnectStats>` — a `List<SessionDrop>` (`atElapsedSec`, `rssi`, `hrGapSec`, plus a per-drop `cause`: gap ≥4s ⇒ `RANGE_OR_FADE`, else `INTERFERENCE`) plus `everyDropAutoRecovered`. Reset at session start alongside the counters; **not** cleared by `stopHrSeriesCapture()`, so the end-of-routine screen (`SessionProgressViewModel`, only when `justCompleted`) can read it live off the `@Singleton` before the next session wipes it. Rendered as `PolarConnectionBox` right under the server-sync box — one row per drop (`m:ss · RSSI · HR-gap · cause`).
- The Polar SDK has its own automatic-reconnection layer beneath `PolarManager`'s (`PolarBleApi.setAutomaticReconnection`), independent of the `userInitiatedDisconnect` flag. `api.disconnectFromDevice()` alone does **not** stop the SDK from silently reconnecting to a still-powered, still-nearby H10 moments later — which fires `deviceConnected()` again and resurrects `PolarStreamingService`'s notification even after `onTaskRemoved`/`disconnect()` already tore it down. `disconnect()` calls `api.setAutomaticReconnection(false)` right before `disconnectFromDevice()`; `connectToDevice()` sets it back to `true` so a genuine mid-workout drop still auto-recovers.
- **"Connected" ≠ "sending data" — UI shows `linkStatus`, not `connectionState`.** When the strap loses power the app must not keep showing a live green "connesso" with a frozen HR. `PolarManager.linkStatus: StateFlow<PolarLinkStatus>` (`CONNECTED` / `NO_SIGNAL` / `CONNECTING` / `DISCONNECTED`) is a pure view (`linkStatusOf()`) over three inputs and never changes the real connection:
  - `receivingData` — `true` on each HR sample, `false` by the data watchdog after `DATA_STALE_MS` (5s). `CONNECTED && !receivingData ⇒ NO_SIGNAL`. Watchdog tick is 1s (`WATCHDOG_TICK_MS`) just so this flips promptly; the 10-15s stream-restart thresholds it also enforces are unchanged.
  - `noSignalGrace` — set for `NO_SIGNAL_GRACE_MS` (15s) on an **involuntary out-of-session** drop (`!userInitiatedDisconnect && !hrSeriesActive`), so `linkStatus` reports `NO_SIGNAL` for that window instead of jumping to grey. This phone's H10 drops the BLE link almost instantly on power-off — there is no 20-30s supervision-timeout window to lean on. Cleared on real reconnect / `disconnect()` / `shutdown()` / BLE-off, else expires → `DISCONNECTED`.
  - Shared `PolarLinkStatusIcon` (`ui/components/`) is the only place the four icons/colours live. `NO_SIGNAL` keeps `HeartRateBar` visible (BPM + zone chip → ⚠️, TRIMP holds its last value) and hides `LiveEcgCard`.
  - **Accepted limitation:** during the grace window the SDK's `setAutomaticReconnection(true)` (kept on for mid-workout recovery) re-grabs the browning-out strap a few times, each grab flashing green briefly. Not worth fixing while **in-session** behaviour is intact — `hrSeriesActive` gates all of the above, and a mid-session drop still runs the 5-min reconnect loop + audible alert + `PolarConnectionBox`.

## "Polar won't connect" is usually a dead CR2025, not a code bug

Before touching reconnect logic, rule out the battery — this failure mode looks exactly like a software bug and has burned real debugging time. A H10 whose cell is spent **stops advertising entirely**: it is invisible to every BLE scan, so `api.searchForDevice()` emits nothing, `deviceConnecting` never fires, and the UI shows no state change at all (not even the "searching" indicator). The reconnect loop then logs attempts forever against a device that isn't there.

The trap is that the cell still measures ~3.0V on a multimeter. A multimeter draws microamps; what kills the strap is internal resistance (~15Ω new, hundreds of ohms spent) dropping the rail below brownout during the ~10 mA transmit peak. Open-circuit voltage stays nominal to the very end. For the same reason the reported percentage is useless below ~70% — see `BATTERY_WARNING_THRESHOLD` in `PolarManager`.

Diagnosing it, in order:
1. `adb logcat | grep -i "BluetoothLeScanner\|bt_shim_scanner"` — if `Start Scan with callback` and `Scan: in shim layer started` appear, the app is doing its job and the silence is the device's.
2. `adb shell dumpsys bluetooth_manager | grep -A30 "Bonded devices"` — the H10 line shows `[ACL BR/EDR:N LE:N]` when not connected, plus a last-seen timestamp. A timestamp days old with the strap supposedly in range means it isn't transmitting.
3. Wet the electrodes and wear it. The H10 has no power switch — it wakes on conductivity between the electrodes and is genuinely off when dry, so a bench test with the strap on a table proves nothing.

Corollary for battery life: the ~400 h Polar quotes is for plain HR streaming. This app also records ECG at 130 Hz, which is a far heavier radio duty cycle, so expect substantially less — roughly 3 months at ~10 h/week of ECG-recorded sessions.

### Active-hours tracker (`BatteryLifeRepository`)

Because the percentage is a near-flat voltage proxy with no "hours remaining" in it, the connection screen shows the current cell's **active hours** measured against **our own historical average cell lifespan** — `42 / 68 h` — to the right of the `%`. Before any cell has been swapped out there is no average yet, so it falls back to `42 h attive · 11 gg`. State lives in `gymdata/_sync/battery_life.yml` (local-only, never synced), exposed as `PolarManager.batteryLife: StateFlow<BatteryLifeState?>`, updated on every `batteryLevelReceived`.

- **Install/swap detection is automatic — there is no "I changed the battery" button.** `BatteryLifeRepository.reduce` compares each reported level against the previous reading; a rise of **more than 5 percentage points** (`JUMP_RESET_THRESHOLD_PCT`) can only be a fresh cell (a coin cell under load never recovers >5% on its own), so it resets `installedAtDate = today` and `activeSeconds = 0`. Covers the "70% one day → 100% the next" case and any other >5% jump. A rise of exactly 5 or less is treated as measurement noise.
- **On a swap, the outgoing cell's `activeSeconds` is appended to `pastLifeSeconds`** — but only if it clears `MIN_CREDIBLE_LIFE_SEC` (2 h), so a pull-and-reinsert or a double swap doesn't drag the mean toward zero. `BatteryLifeState.avgLifeHours` is the mean of that list (null while empty); `measuredCellCount` is its size.
- **Active time** is accumulated as the wall-clock gap between consecutive battery callbacks, *unless* that gap exceeds `MAX_SESSION_GAP_SEC` (30 min) — a longer gap means the strap was off between readings, so that span is skipped and the anchor moves forward. This under-counts by at most one inter-callback interval per session; it never over-counts. No connect/disconnect bookkeeping is involved — the periodic battery callback is the only hook.
- All the logic is the pure `reduce(prev, level, nowEpochSec, today)` function; file I/O is a thin wrapper. Unit-tested in `BatteryLifeReducerTest`.

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

## YAML string escaping in frontmatter

Every frontmatter string field (`name`, `bodypart`, `link`, `exerciseName`, `routineName`, …)
is free text the user typed into a text field — not a fixed enum. `MarkdownParser`'s
`formatValue`/`serializeYaml` escape `"` → `\"`, `\` → `\\`, and flatten `\n`/`\r` to a space
before wrapping a string in the double-quoted YAML scalar (`key: "value"`) it writes.

This matters because the failure mode without it is silent: a literal `"` in, say, an exercise
named `Push-up "diamond" variant` would terminate the quoted scalar early and corrupt the rest
of that line. Every repository's `ensureLoaded()`/read path already wraps per-file parsing in
`catch (_: Exception) { /* Skip malformed */ }` (so one bad file can't crash a directory scan)
— which means the corrupted record doesn't error, it just vanishes from the list on next load,
with no toast, log line, or crash to point at why. See `MarkdownParserTest` for the escaping
round-trip coverage.

`MarkdownParser.parse()` doesn't need a matching unescape step — snakeyaml-engine's `Load`
already understands standard YAML double-quoted-scalar escaping, so `\"`/`\\` decode back
correctly on their own.

## Rep-range invariant

`repRangeMin > repRangeMax` used to slip through. Editors now symmetric-clamp:
- raising min above max pulls max up with it,
- lowering max below min pulls min down with it.

Applies in both `ExerciseEditViewModel.onRepMin/MaxChange` and `RoutineEditViewModel.updateExerciseRepMin/Max`. One-shot boot migration (`fixInvalidRepRanges` in `ExerciseRepository` and `RoutineRepository`, guarded by `.reprange_fixed` sentinel files in `exercises/` and `routines/`) repairs any pre-existing `min > max` by setting `max = min`.

## Home load: `HomeStateLoader`, two-phase but only on first load

The home screen's state is built by the `@Singleton` `HomeStateLoader`, not `MainViewModel`. `MyGymApp.onCreate` calls `homeStateLoader.refresh()` immediately so the work overlaps Activity/Compose creation; `MainViewModel` just exposes `homeStateLoader.state` and calls `refresh()` again on resume / `routinesChanged`. Concurrent `refresh()` calls coalesce (`force = false` joins the in-flight job; `force = true` cancels + restarts).

`loadHomeState()` emits **twice on a cold start**: phase 1 = the 4 gitgraph history rows (from the `_gitgraph.yaml` cache, fast) + a schedule-row *scaffold* (routine names only) with `isLoading = false`, so the screen paints in ~200 ms instead of waiting ~500 ms for the current-week session files to parse; phase 2 = the schedule row's session outcomes + `today*` fields, a beat later.

**The phase-1 emit is guarded by `everCompleted`** — a `@Volatile` flag set once a phase-2 (fully-populated) state has been emitted. It fires while nothing complete has ever been shown; after that, every load (a normal `refresh()`, a routine change, *and a `force` refresh that cancels a first load mid-flight*) holds the last complete state on screen and swaps in one go at phase 2. Emitting the scaffold then would blank the schedule row's outcomes for ~100 ms — a visible flicker. Don't key this off `_state.value.isLoading` (the old bug: a `force` refresh landing between phase 1 and phase 2 of the first load saw `isLoading == false` and skipped phase 1, leaving the scaffold on screen until its own phase 2).

`GitgraphHistoryCalculator.dayCell` is the single per-day status/%/cardio rule — used for both the cached history rows and the live current-week row, so they can't diverge. Parsing helpers (`getSessionsInRange`, `getLastSessionForRoutine`) parse their in-range `.md` files concurrently; `getLastSessionForRoutine` walks newest-filename-first and stops at the first completed session (plus same-day siblings) instead of parsing a routine's whole history.

## Derived-data sidecars

Two pre-computed caches now live under `history/` — the exercise-stats sidecars (`_stats/{id}.yaml`) and the home gitgraph cache (`_gitgraph.yaml`). Both follow the same shape, and a third should too:

1. A `data class` model with a `SCHEMA_VERSION` companion constant and a doc comment stating exactly what each stored field means under the current version.
2. A **pure** `…Calculator` object that derives the model from parsed `WorkoutSession`s — no `Context`, no I/O — with a JUnit test. Where there's both a full-rebuild and an incremental path (stats), a test pins that they agree.
3. A `…Parser` object that round-trips the model to/from front-matter-only YAML via `MarkdownParser`, with a round-trip test.
4. `WorkoutRepository` owns the lifecycle: read → *check schema (and any identity field) before serving* → on miss, recompute **outside** the write mutex → take the mutex only to persist, re-checking for a concurrent writer first. Invalidate on the events that can change the derived value; a schema bump needs no migration code (mismatch ⇒ lazy rebuild). The one-time index migration wipes all sidecars.

Traversing `history/` for these: use `WorkoutRepository.historyMonthDirs()` / `historySessionFiles()`, not an ad-hoc `listFiles()` walk — they're the one place the `_idx`/`_stats` reserved-name skip lives.

## HRV baseline is derived from the `.md` files, never stored on the side

The rolling HRV-readiness baseline (14 LnRMSSD samples for the z-score, 7 resting-HR
samples for the VO2max window) is **recomputed on every measurement from the persisted
`readiness/*.md` files** (`ReadinessRepository.getLnRmssdHistory()` /
`getRestingHrHistory()` → `HrvBaselineCalculator`). It used to be mirrored into
`SharedPreferences("hrv_baseline")` as CSV — that store was the *only* real user data
outside `gymdata/`, so it wasn't in the backup tar or the sync pipeline, and a partial
restore / `pm clear` / differently-signed reinstall wiped it. The next measurement then
re-seeded from one sample and reported `NO_BASELINE` ("1/7 days") despite a full history on
disk (this happened on 2026-08-31 — Phase 94).

Rule: **anything that behaves like accumulated user state must be a file under
`gymdata/`**, not `SharedPreferences`. SharedPreferences is for config that a fresh install
is expected to re-ask for (server URL, profile) — and even those are flagged as
"wipeable, not a backup" in the docs. If you add another running aggregate, derive it from
its source records the way `HrvBaselineCalculator` does; don't cache it in prefs.

## Per-exercise ViewModels: `ExerciseSessionViewModel`

`StrengthExerciseViewModel`, `StretchExerciseViewModel` and `SupersetViewModel` extend `ui/screen/exercise/ExerciseSessionViewModel`, which owns the shared completion plumbing that was previously copy-pasted (and drifting) across all three: the `clearScope` `SupervisorJob`, `markCompletionAndSave { … }` (runs the completion write on `clearScope`, flips `completionSaved` after), `markSwitched()` (mark done without a write, for "Switch exercise"), and the `onCleared()` template (join the completion job then cancel the scope if finished; otherwise call the subclass's `saveProgressOnExit()`). Subclasses that also run a timer override `onCleared()` to cancel it, then call `super.onCleared()`. `CardioExerciseViewModel` does **not** extend it — its completion runs on `viewModelScope` and its exit path resumes a running block rather than saving-as-incomplete.

## Baseline Profile

`:baseline-profile` is a `com.android.test` module (`androidx.baselineprofile` plugin) that
drives the app on a connected device to record the hot classes/methods of the cold-start
critical journey. The generated `app/src/release/generated/baselineProfiles/`
(`baseline-prof.txt` + `startup-prof.txt`) is **committed** and the plugin merges it into
`release` `assemble`/`bundle` (as `assets/dexopt/baseline.prof{,m}`);
`androidx.profileinstaller` then has ART compile those paths AOT at install time.
`BaselineProfileGenerator` records; `StartupBenchmark` measures the delta
(`CompilationMode.None` vs `Partial(Require)`).

**Scope is deliberately the cold-start path, not every screen.** The journey covers the
home + the shared infra reached from it (`WorkoutParser` / `MarkdownParser` / snakeyaml,
the repositories, base ViewModels, Compose + Hilt + Navigation runtime) plus the routine /
exercise lists and `SessionProgressScreen`. It does **not** start a live workout: reaching
`ActiveRoutineScreen` needs a routine scheduled for the generation-day weekday, and driving
a real session would either write to real data or need a debug seed hook — not worth it for
paths that aren't on the launch-to-home critical path (they pay a one-time JIT cost on first
open, which ART's own `speed-profile` then absorbs). If those screens ever need AOT
coverage, add a *separate* non-startup `BaselineProfileRule`, don't bloat the startup
journey.

Regenerate after a large refactor of the startup path or a dependency bump:

```bash
# ⛔ FIRST back up the on-device data — generateBaselineProfile UNINSTALLS the app
#    (uninstall_after_test: true), which destroys /data/data/com.mygymapp/files/gymdata/.
#    See the banner at the top of CLAUDE.md. Do not skip this.
adb shell run-as com.mygymapp tar -C /data/data/com.mygymapp/files -cf - gymdata \
  > gymdata-backup-$(date +%Y%m%d-%H%M%S).tar   # needs a debuggable build installed
tar -tvf gymdata-backup-*.tar | head            # verify non-empty BEFORE continuing

adb shell pm uninstall com.mygymapp             # start from a clean install
./gradlew :app:generateBaselineProfile          # ~20–30 min on the phone

# afterwards, reinstall the user's normal build and restore:
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell run-as com.mygymapp tar -C /data/data/com.mygymapp/files -xf - < gymdata-backup-*.tar
```

Gotchas that cost time here:

- **Generation device**: needs a physical phone or an AOSP image where `ProfileInstaller`
  can actually install the profile — a standard Google-Play emulator image silently won't.
  A Play-Services *phone* is fine (the caveat is emulator-image-only).
- **`benchmark-macro` ≥ 1.4.0** is required: the 1.3.x `pm dump-profiles` parser chokes on
  the "Waiting for app processes to flush profiles…" line Android 14+/16 prints, and the
  generate step fails with an `IllegalStateException` about unexpected stdout.
- **The journey must not touch a system permission dialog.** A fresh `nonMinifiedRelease`
  install has no runtime permissions, so Home's `LaunchedEffect` fires BLE / notification /
  Health-Connect requests that then sit on top of the app and the whole run hangs
  (`am_instrument_timeout` is a year). `BaselineProfileGenerator.grantRuntimePermissions()`
  pre-grants all of them via `pm grant` first — including `android.permission.health.READ_STEPS`,
  which *is* adb-grantable on API 34+.
- **`testTagsAsResourceId`** is set on the NavHost (`AppNavigation.kt`) so UiAutomator can
  wait on `GitgraphView`'s `testTag("gitgraph")` — Compose exposes it as the **bare** tag
  string, so match `By.res("gitgraph")`, not `By.res("com.mygymapp:id/gitgraph")`.
- Don't run the generator in the normal `./gradlew test` / instrumented job — it's slow and
  device-specific. It has no CI wiring on purpose.
- If R8/minify is ever turned on for `release` (it isn't today), regenerate the profile
  afterwards — a profile recorded pre-shrink references stale method signatures.

## Gradle wrapper

`gradle/wrapper/gradle-wrapper.jar` was copied from `~/.gradle/caches/` — there is no global Gradle installed on this machine. If the jar ever goes missing, pull it from a cached distribution rather than running `gradle wrapper` (which requires Gradle to be installed in the first place).

## ANDROID_HOME

Android Studio reads `sdk.dir` from `local.properties`. Command-line builds need `ANDROID_HOME=~/Android/Sdk` — see [README.md](../README.md#building).

## Local test gate

There is no CI in this repo (no `.github/workflows`). The only automated safety net is a local
git hook: `scripts/git-hooks/pre-commit` runs `./gradlew test` before every commit and blocks
the commit on failure. It's tracked in the repo but git only reads `.git/hooks/`, which isn't
tracked, so it must be installed once per clone:

```bash
ln -sf ../../scripts/git-hooks/pre-commit .git/hooks/pre-commit
```

Skip it for one commit with `git commit --no-verify` (e.g. a WIP commit on a feature branch).

Unit tests live under `app/src/test/` (pure JVM, no Android framework — no Robolectric/instrumentation
set up yet). Favor testing pure logic classes here: parsers (`data/parser/`), calculators
(`HrZoneCalculator`, `BodyCompositionCalculator`, `TonnageMath`), and model extension functions
(e.g. `WorkoutSession.withExerciseSwitched`) — anything that doesn't touch `Context`, BLE, or
file I/O directly. See the existing suite for the shape: `HrRecoveryMonotonicityTest`,
`WorkoutParserRoundTripTest`, `WorkoutSessionSwitchExerciseTest`, `HrZoneCalculatorTest`,
`TonnageMathTest`, `BodyCompositionCalculatorTest`.
