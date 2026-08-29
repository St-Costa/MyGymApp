# MyGymApp — AI context

This file is the entry point for AI tooling. The details of the project live in [`docs/`](docs/); this file exists to give a fresh assistant a fast mental model and pointers to the right document.

> ## ⛔ STOP — BACK UP THE ON-DEVICE DATA FIRST
> ## ⛔ FERMATI — FAI IL BACKUP DEI DATI SUL TELEFONO PRIMA DI TUTTO
>
> **All user data lives ONLY in `/data/data/com.mygymapp/files/gymdata/` on the phone.**
> It is **NOT** in git and **NOT** on this machine. A phone-side full-store backup pipeline
> now exists (`docs/BACKUP.md`: sessions/readiness/scale/ECG + exercises/routines queue for
> upload), **but it is not a safety net you can rely on**: it only sends when a sync server
> is configured *and* reachable, the server side isn't built yet, and its config lives in
> wipeable `SharedPreferences`. **A backup you haven't verified is not a backup — take the
> tar below before any install/test op regardless.**
> Any `pm uninstall`, `pm clear`, `adb install` of a differently-signed APK, a
> `com.android.test` / baseline-profile / macrobenchmark run (those set
> `uninstall_after_test: true`), a factory-reset-ish `cmd package` call, or a wipe by the OS
> **destroys it permanently**. This has already happened once and cost the user real data.
>
> **Before ANY of the following — no exceptions, even "just a quick test":**
> building/installing a non-debug variant, running anything under `:baseline-profile` or
> `connected…AndroidTest`, uninstalling/reinstalling the app, changing its signing, or
> anything else that could touch the app's install or its `filesDir`:
>
> ```bash
> # 1. Pull the data off the device (works only while a *debuggable* build is installed):
> adb shell run-as com.mygymapp tar -C /data/data/com.mygymapp/files -cf - gymdata \
>   > gymdata-backup-$(date +%Y%m%d-%H%M%S).tar
> # If the installed build is NOT debuggable, install the debug build first
> # (adb install -r app/build/outputs/apk/debug/app-debug.apk) — install -r keeps data —
> # THEN run the line above.
>
> # 2. Verify the tar is non-empty and lists real files before proceeding:
> tar -tvf gymdata-backup-*.tar | head
>
> # 3. To restore afterwards:
> adb shell run-as com.mygymapp tar -C /data/data/com.mygymapp/files -xf - < gymdata-backup-*.tar
> ```
>
> Keep the backup tar until the user has confirmed their data is intact in the app.
> If you cannot produce a verified backup, **do not proceed** — ask the user first.
> There is a server-sync feature (`docs/SYNC.md`) but it is opt-in, may be unconfigured,
> and its client config also lives in wipeable `SharedPreferences` — it is not a substitute
> for the tar backup above.

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
| "How was old VitaFit scale history imported? Is there an official export?" | [docs/vitafit-cloud-api.md](docs/vitafit-cloud-api.md) |
| "There's an odd pattern — is this intentional?" | [docs/CONVENTIONS.md](docs/CONVENTIONS.md) |
| "What happened over time?" | [docs/CHANGELOG.md](docs/CHANGELOG.md) |
| "How does syncing sessions/readiness/scale/ECG to the self-hosted server work?" | [docs/SYNC.md](docs/SYNC.md) (implemented for sessions; readiness/scale/ECG phone-side done, server-side WIP) |
| "How does the git-style full-store backup (exercises + routines too) work?" | [docs/BACKUP.md](docs/BACKUP.md) (phone + server side implemented) + [docs/backup-server-brief.md](docs/backup-server-brief.md) (the brief the server repo was built from) |

Per-Polar deep dive: [docs/polar/implementation-guide.md](docs/polar/implementation-guide.md).

## Crucial facts you'll need often

- **Storage root**: `context.filesDir/gymdata/`. Subdirs: `exercises/`, `routines/`, `history/YYYY/MM/`, `history/_idx/`, `ecg/`, `cache/images/`, `image_cache/`. Full detail in [STORAGE.md](docs/STORAGE.md#root-layout).
- **Session filename**: `YYYY-MM-DD_{routineId}_{sessionId}.md`. The routineId is in the filename so routine lookups don't need to parse YAML.
- **IDs**: `ex-{8hex}` for exercises, `rt-{8hex}` for routines, bare `{8hex}` for sessions.
- **Event bus**: `DataChangedSignal` — edit VMs emit, list VMs reload. See [CONVENTIONS.md](docs/CONVENTIONS.md#datachangedsignal).
- **Derived-data sidecars**: `history/_stats/{exerciseId}.yaml` (per-exercise "previous"/PR/has-prior-tonnage, split by slot context) and `history/_gitgraph.yaml` (home's 4 pre-week history rows). Caches over the `.md` files; `.md` stays source of truth. Pure `…Calculator` + round-tripping `…Parser` + `WorkoutRepository` lifecycle; schema mismatch ⇒ lazy rebuild, no migration code. Same shape for any future one. See [CONVENTIONS.md](docs/CONVENTIONS.md#derived-data-sidecars) and [STORAGE.md](docs/STORAGE.md#exercise-stats-sidecar-history_statsexerciseidyaml).
- **`ExerciseSessionViewModel`**: base class for the Strength/Stretch/Superset exercise VMs — owns `clearScope`, `markCompletionAndSave`, `markSwitched`, the `onCleared` template. Cardio VM does not extend it. See [CONVENTIONS.md](docs/CONVENTIONS.md#per-exercise-viewmodels-exercisesessionviewmodel).
- **`onCleared()` save**: dedicated `clearScope`, cancel inside `finally`. Never `runBlocking`. See [CONVENTIONS.md](docs/CONVENTIONS.md#oncleared-save).
- **`completionSaved` pattern**: exercise screens never call `onComplete()` from a button — flow through `_completionSaved` so disk writes land before navigation reads them. See [CONVENTIONS.md](docs/CONVENTIONS.md#completionsaved-pattern).
- **Polar facade**: everything flows through `@Singleton PolarManager`. Screens observe its StateFlows — they do not own Rx disposables.
- **Two image caches**: `cache/images/` (ImageCacheRepository, persistent) and `image_cache/` (Coil LRU, 100 MB). Not interchangeable.
- **Ghost-session guard**: `ActiveRoutineViewModel.onCleared()` + `WorkoutRepository.runMaintenance()`. Shell sessions (no data, no completed exercises) and their `.ecg` files get deleted both on back-out and at boot (throttled to once/12h). See [CONVENTIONS.md](docs/CONVENTIONS.md#ghost-session-prevention).
- **ECG raw file deletion**: `registerRoutine` no longer runs local ECG analysis at all (moved server-side) — if sync isn't configured, the raw `ecg/{id}.ecg` is deleted immediately (nothing local would ever consume it). If sync **is** configured, deletion is deferred to `EcgSyncWorker` — only after a confirmed server upload, or after 30 days pending. See [CONVENTIONS.md](docs/CONVENTIONS.md#ecg-raw-file-send-then-delete-no-local-analysis-fallback) and [SYNC.md](docs/SYNC.md#fourth-record-type-raw-ecg).

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
- **Write tests alongside non-trivial logic changes.** When a change adds or modifies pure logic
  (a parser, a calculator, a model extension function, a bugfix like [hrr60s monotonicity](docs/CHANGELOG.md))
  in a class that doesn't touch `Context`/BLE/file I/O directly, add or update a JUnit test under
  `app/src/test/` in the same change — don't wait to be asked. Skip it only for pure UI/Compose
  layout changes or Android-framework-coupled code with no test harness yet (see
  [CONVENTIONS.md#local-test-gate](docs/CONVENTIONS.md#local-test-gate) for what's testable today
  and the existing suite's shape). Run `ANDROID_HOME=~/Android/Sdk ./gradlew test` before
  considering the change done — a local git hook also enforces this at commit time, see
  [CONVENTIONS.md#local-test-gate](docs/CONVENTIONS.md#local-test-gate).
