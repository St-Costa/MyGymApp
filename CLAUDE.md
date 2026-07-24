# MyGymApp — AI context

This file is the entry point for AI tooling. The details of the project live in [`docs/`](docs/); this file exists to give a fresh assistant a fast mental model and pointers to the right document.

## What it is

Android gym tracking app. Kotlin + Jetpack Compose, dark-only, single activity. File-based storage (no database): everything is Markdown + YAML in `filesDir/gymdata/`. Optional Polar H10 integration for live HR, ECG, HRV, VO2max.

Build:
```bash
ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug
```

## Where to look

| Question | Doc |
|---|---|
| "How is the code laid out? Which ViewModel owns what?" | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| "Where is this stored on disk? What's the session YAML look like?" | [docs/STORAGE.md](docs/STORAGE.md) |
| "How does the heart rate / ECG / readiness stuff work?" | [docs/POLAR.md](docs/POLAR.md) |
| "There's an odd pattern — is this intentional?" | [docs/CONVENTIONS.md](docs/CONVENTIONS.md) |
| "What happened over time?" | [docs/CHANGELOG.md](docs/CHANGELOG.md) |

Per-Polar deep dive: [docs/polar/implementation-guide.md](docs/polar/implementation-guide.md).

## Crucial facts you'll need often

- **Storage root**: `context.filesDir/gymdata/`. Subdirs: `exercises/`, `routines/`, `history/YYYY/MM/`, `history/_idx/`, `ecg/`, `cache/images/`, `image_cache/`. Full detail in [STORAGE.md](docs/STORAGE.md#root-layout).
- **Session filename**: `YYYY-MM-DD_{routineId}_{sessionId}.md`. The routineId is in the filename so routine lookups don't need to parse YAML.
- **IDs**: `ex-{8hex}` for exercises, `rt-{8hex}` for routines, bare `{8hex}` for sessions.
- **Event bus**: `DataChangedSignal` — edit VMs emit, list VMs reload. See [CONVENTIONS.md](docs/CONVENTIONS.md#datachangedsignal).
- **`onCleared()` save**: dedicated `clearScope`, cancel inside `finally`. Never `runBlocking`. See [CONVENTIONS.md](docs/CONVENTIONS.md#oncleared-save).
- **`completionSaved` pattern**: exercise screens never call `onComplete()` from a button — flow through `_completionSaved` so disk writes land before navigation reads them. See [CONVENTIONS.md](docs/CONVENTIONS.md#completionsaved-pattern).
- **Polar facade**: everything flows through `@Singleton PolarManager`. Screens observe its StateFlows — they do not own Rx disposables.
- **Two image caches**: `cache/images/` (ImageCacheRepository, persistent) and `image_cache/` (Coil LRU, 100 MB). Not interchangeable.
- **Ghost-session guard**: `ActiveRoutineViewModel.onCleared()` + `WorkoutRepository.cleanupGhostSessions()`. Shell sessions (no data, no completed exercises) and their `.ecg` files get deleted both on back-out and at boot. See [CONVENTIONS.md](docs/CONVENTIONS.md#ghost-session-prevention).
- **ECG raw kept on analysis failure**: `registerRoutine` deletes `ecg/{id}.ecg` only when `ecgResult.hasAnything`. See [CONVENTIONS.md](docs/CONVENTIONS.md#ecg-analysis-keep-the-raw-file-on-failure).

## Gotchas (the short list)

See [docs/CONVENTIONS.md](docs/CONVENTIONS.md) for the complete set. The ones that cost real time:

- Don't name a private property `fooBar` and a function `getFooBar()` — Kotlin's generated getter clashes.
- YouTube in WebView is unworkable on Android. Use `CustomTabsIntent`.
- `waitForUpOrCancellation()` returns `null` for both gesture-cancel and long-press timeout. Use a flag to distinguish.
- `AutoSaveTextField` requires the `DisposableEffect.onDispose { pendingSave?.let(onSave) }` flush; without it, fast back navigation loses text.
- `StopwatchService.ACTION_START` must call `handler.removeCallbacks(tickRunnable)` before `postDelayed`.
- Gradle wrapper jar was copied from `~/.gradle/caches/` — there's no system Gradle.

## Working conventions

- Use Edit/Write over shell `sed`/`echo`. Prefer editing existing files over creating new ones.
- When you find a disalignment between code and docs, update both in the same change.
- New patterns/gotchas → add them to [CONVENTIONS.md](docs/CONVENTIONS.md).
- Completed work → one paragraph in [CHANGELOG.md](docs/CHANGELOG.md) under a new Phase heading.
