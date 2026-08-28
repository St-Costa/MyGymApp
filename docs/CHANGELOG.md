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

## Phase 19 — Polar notification + ECG reliability fixes

Two bugs found from a real session (2026-06-06):

- **Notification linger after H10 power-off** (`PolarManager`): when Bluetooth was disabled at the OS level, `blePowerStateChanged(false)` previously only logged the event. The foreground service notification remained visible until the BLE stack fully terminated. Fixed: `blePowerStateChanged(false)` now performs an immediate full teardown — stops the reconnect loop, disposes all streams, stops the foreground service, and resets connection state — mirroring a user-initiated disconnect. Additionally, `scheduleReconnect` now tracks `reconnectStartAtMs` and gives up after 5 minutes, calling `PolarStreamingService.stop()` and clearing `hrSeriesActive`. This bounds the scenario where the user leaves the app mid-session with the H10 off.

- **ECG watchdog blindspot** (`PolarManager`): the watchdog that restarts ECG streaming had a hidden condition `lastEcgSampleAtMs > 0` — it only fired once at least one ECG sample had been received. If `startEcgStreaming` was called but the H10 silently sent no data (SDK hang, PMD service not fully settled), the watchdog never triggered and ECG was stuck with a 20-byte header for the whole session. Fixed: added `ecgStreamStartedAt` timestamp, set at every `startEcgStreamingInternal` call. Watchdog now also triggers when `ecgStreamStartedAt > 0 && no sample in 15s`, logging a distinct "no first sample" message. `stopEcgRecording` resets both timestamps.

## Phase 20 — Persistent event log

`AppLogger` (`data/util/AppLogger.kt`), Hilt `@Singleton`, scrive in `filesDir/gymdata/logs/app.log` in aggiunta a logcat (non lo sostituisce). Formato: `2026-06-06 09:31:22 I Tag: messaggio`. All'avvio, pota le righe con data < oggi-10gg, poi appende.

Punti loggati:
- **PolarManager**: BLE power off, device connected/disconnected (con `involuntary` + `midSession`), ONLINE_STREAMING feature ready, ECG start request + streaming started + error + completed unexpectedly, ECG restart eseguito/saltato (con motivo), ECG watchdog trigger (con tipo: "no first sample" o "sample timeout"), HR streaming error, auto-reconnect attempt (con elapsed), reconnect timeout, readiness result (label + lnRMSSD + restingHr + vo2max + rrSamples).
- **ActiveRoutineViewModel**: sessione creata (id + routine + exercise count), ECG+HR capture started (con polar device id), ECG file size, analisi ECG (hasAnything + beats + durationSec + rmssd + pacs + pauses), sessione registrata (tonnage + kcal + trimp), sessione abbandonata, ghost session eliminata da `onCleared`, `onCleared` senza registrazione.
- **MainViewModel**: boot cleanup (ghosts + orphan ECG eliminati).

Lettura: `adb shell run-as com.mygymapp cat files/gymdata/logs/app.log`

## Phase 21 — Polar teardown on app swipe-away

`PolarStreamingService` ora è `@AndroidEntryPoint` e inietta `PolarManager`. Override di `onTaskRemoved`: quando l'utente rimuove l'app dai recenti, il foreground service sopravvivrebbe lasciando la notifica HR appesa e il Polar connesso. Ora forza `polarManager.disconnect()` (chiude gli stream, sgancia il link BLE, rimuove la notifica) e poi `stopForeground` + `stopSelf`. `android:stopWithTask` resta al default `false`, così `onTaskRemoved` viene effettivamente consegnato.

## Phase 22 — Schermata Opzioni + settimane powerlifting

Il FAB ingranaggio della Home non apre più direttamente il dialog "seed debug data": ora naviga a una nuova **`OptionsScreen`** (`ui/screen/options/`, route `Screen.Options`). La schermata ha due sezioni:

- **Dati di debugging**: testo esplicativo + pulsante che apre l'avviso di cancellazione e lancia `MainViewModel.seedDebugData()`. La logica seed resta in `MainViewModel` (condiviso via `hiltViewModel()`); il dialog è stato spostato qui da `MainScreen`.
- **Settimana powerlifting**: calendario mensile (frecce avanti/indietro, apre sul mese corrente evidenziando oggi) dove si seleziona una settimana — l'intera riga si illumina con il primary brand. Sotto, selettore "ogni X settimane".

Persistenza in `data/PowerliftingScheduleRepository.kt` (`@Singleton`, SharedPreferences `powerlifting_schedule`): salva il lunedì-ancora + intervallo. `isPowerliftingWeek(date)` calcola via `floorMod(weeksBetween(anchor, monday), interval)`. L'ancora è essa stessa powerlifting; intervallo 4 = 3 settimane normali + 1 powerlifting.

Effetti:
- **ActiveRoutineViewModel/Screen**: all'apertura di una sessione in una settimana powerlifting compare un overlay modale "SETTIMANA POWERLIFTING" sopra tutto (Box wrapper attorno allo Scaffold), con pulsante "Ho capito". Riappare a ogni apertura sessione.
- **GitgraphView**: nuovo parametro `powerliftingWeeks: List<Boolean>` (4 valori, uno per riga-settimana). Le settimane powerlifting hanno un bordo viola brand attorno all'intera riga (poco padding, non i singoli giorni). `MainViewModel` calcola i flag per le 4 righe del gitgraph.

## Phase 23 — Fix overlay powerlifting + progresso esercizi daily

- **Overlay powerlifting**: l'overlay "SETTIMANA POWERLIFTING" ora compare solo a sessione caricata (`!uiState.isLoading`). Prima `isPowerliftingWeek` veniva impostato all'inizio della init mentre `isLoading` era ancora true, così l'overlay lampeggiava e si chiudeva da solo appena la sessione finiva di caricare, prima che l'utente lo vedesse.
- **Esercizi daily — rep range e progresso**: gli esercizi fixed-daily non mostravano rep range né valori "precedenti". Nuovo campo `WorkoutExercise.isDaily` (serializzato in YAML, omesso se false). `ActiveRoutineViewModel` lo imposta quando `category == DAILY`. `StrengthExerciseViewModel` e `SupersetViewModel` ora: (1) leggono il rep range dalla routine `FIXED_DAILY_ROUTINE_ID` quando l'esercizio è daily, invece che dalla routine della sessione; (2) confrontano il progresso solo con sessioni passate in cui lo stesso esercizio aveva lo *stesso* stato daily — un es. daily contro precedenti sessioni daily, un es. normale contro precedenti sessioni normali, così lo stesso esercizio può cambiare ruolo tra i giorni senza contaminare le due storie. Il grafico di fine sessione era già escluso (filtra `!excludeFromTonnage`).

## Phase 24 — Preview "valori precedenti" più robusta

- **Preview per tipo, salta gli zeri, eredita l'ultimo set**: in `StrengthExerciseViewModel` e `SupersetViewModel` la scelta dei valori grigi "precedenti" aveva tre difetti. (1) Il confronto usava solo `isDaily`, quindi un warmup precedente veniva trattato come set normale; ora il tipo è la coppia `(isDaily, excludeFromTonnage)` → daily / warmup / normale, e la preview viene presa solo da sessioni dello stesso tipo. (2) Si fermava alla prima sessione dello stesso tipo anche se era tutta a 0-0 (es. un daily appena introdotto): ora cammina indietro tra le sessioni completate e prende la prima con almeno un set non-zero. (3) I set in più rispetto alla sessione precedente partivano da 0-0; ora ereditano l'ultimo set non-zero della preview (`lastMeaningfulPrev`). Vedi [CONVENTIONS.md](CONVENTIONS.md#previous-set-preview-match-type-skip-zeros-inherit-last-set).

## Phase 25 — Fix ECG analysis always failing + per-set preview inheritance

Diagnosed on the connected test phone with real data instead of guessing: `adb shell run-as com.mygymapp cat files/gymdata/logs/app.log` showed **every** registered session logging "ECG analysis: no result (file too short or exception)" going back to May, despite each `.ecg` file holding 1-2 hours of real, non-clamped signal (pulled via `adb shell run-as com.mygymapp cat files/gymdata/ecg/{id}.ecg` and replayed through the exact detector logic in Python).

- **Root cause**: `EcgAnalyzer.panTompkinsDetect`'s adaptive threshold was seeded from `integrated.max()` over the *entire* recording. A gym session reliably contains one motion artifact whose integrated value dwarfs any real QRS complex (measured ~18x the p99.99 percentile on a failing file vs ~2.7x on session that had succeeded); once that single sample set the global max, the threshold sat far above every real beat for the rest of the session, so `peaks.size < 2` and analysis returned `null` — every time. Fixed by switching to a threshold derived from a **trailing 5s local max** (`trailingMax`, O(n) monotonic deque) instead of the global one, so one artifact only blinds detection for a few seconds around itself. See [CONVENTIONS.md](CONVENTIONS.md#r-peak-threshold-must-be-local-not-global-max).
- **Root cause (unrelated second report)**: "previous set" preview stopping mid-exercise. `StrengthExerciseViewModel` gated the grey preview on one `hasProgress` flag computed over *all* sets of the exercise; filling in set 1 flipped it, and every other still-untouched set then showed 0-0 on next recompose instead of inheriting the previous session's value (reproduced on disk: a real "Copenhagen adduction" set dropped from 25kg to 1kg between two consecutive sessions with no such change actually made). `SupersetViewModel` already checked this per-set and never had the bug. `StrengthExerciseViewModel` now does the same. See [CONVENTIONS.md](CONVENTIONS.md#previous-set-preview-match-type-skip-zeros-inherit-last-set).

## Phase 26 — Untouched-exercise guard

Tapping "Complete Exercise"/"Complete Superset" without changing anything used to silently re-record last session's pre-filled numbers as this session's work, inflating tonnage and the per-exercise `%` change with a phantom identical set. Added real touch tracking (`repsTouched`/`weightTouched` on `StrengthSetUi`/`SupersetSetUi`, set only by explicit user actions — never by the prefill in `init`; stretch sets use their existing `done` toggle as the touch signal, since stretch has no prefill). If an exercise (or, for supersets, one side of it) is completed with nothing touched, it's saved as `completed = false, sets = emptyList()` — the same on-disk shape as an exercise the user never opened — so tonnage, `%` change, ghost-session detection, and next-session prefill all handle it correctly with no extra logic. `ActiveRoutineViewModel.markExerciseCompleted()` now checks the reloaded exercise's actual `completed` flag before ticking off the UI row, so an untouched exercise stays open and blocks session finalization like any other incomplete exercise. Added `WorkoutExercise.isUntouched()` and used it in `MainViewModel.computeCommonTonnage()` to exclude untouched exercises from the session-vs-session tonnage comparison behind the gitgraph's day color/`%` change — otherwise an untouched exercise counted as "0 tonnage" and silently dragged that day's average down. See [CONVENTIONS.md](CONVENTIONS.md#untouched-exercise-guard-completing-without-changing-anything).

## Phase 27 — Home button on session progress screen

`SessionProgressScreen` (the tonnage/kcal/TRIMP/ECG charts screen reached by tapping a gitgraph cell) only had the top-bar back arrow, which relies on the back stack popping to `Main`. Added an explicit Home icon button in the `TopAppBar` actions, wired in `AppNavigation` to `navController.navigate(Screen.Main.route) { popUpTo(Screen.Main.route) { inclusive = true } }` so it always lands on the home screen regardless of back-stack state.

## Phase 28 — PR badge on exercise screens

`SupersetScreen` shows a "PR NxM" badge (reps × heaviest weight ever logged for that exercise, same daily/warmup type, over the last 30 matching sessions) above each FORZA exercise's first set. Derived from the same per-session set history `SupersetViewModel` already fetches for the grey "previous value" prefill, reduced with `maxWithOrNull(compareBy(weight, reps))` instead of taking the most recent session.

`StrengthExerciseScreen` already had an all-time PR badge (`tonnagePr`, the single set with the highest reps×weight ever recorded, across full history, warmup excluded) shown above the media preview — kept that instead of adding the 30-session variant, and moved it down to sit between the "kg"/"rep" header row and the first set (matching where the phone-side change placed its own badge), so the two screens don't show two near-duplicate PR numbers in different definitions.

## Phase 29 — Simplify session progress screen, replace ECG text dump with charts

`SessionProgressScreen` was cluttered: a wall of ECG stat text, plus a tonnage chart with a filter-chip row (Totale / bodyparts / kcal / TRIMP / VO2max) that made it unclear what was being plotted. Reworked per user feedback:

- **Tonnage**: dropped the filter chips entirely — the chart now always shows total tonnage across recent sessions of this routine, no selection needed.
- **ECG**: replaced the paragraph of numbers with small per-metric trend line charts (reusing `TonnageLineChart`) — HR medio, HRR, VO2max, HRV (RMSSD + SDNN), Poincare ratio, HR a riposo, deriva cardiaca — each only rendered when it has recorded data, one point per past completed session (cross-routine, since these are physiological, not routine-specific). PAC/Pause/Irregular and AFib-screening counts stay as a single minimal text line, shown only when this session actually has anomalies.
- `SessionProgressViewModel` gained a small `ChartSeries(data, labels)` holder and a `cardioSeries()` reducer that drops zero/unset points so a metric a device didn't record doesn't render as a flat line at 0. No change to what gets saved to disk — this is display-only; `WorkoutSession` still persists every field it did before.
- The screen's root `Column` is now `verticalScroll`-able (it wasn't before), since the cardio section can add several chart cards.

## Phase 30 — Server sync (phone-side transport)

Added `data/sync/` — pushes raw session `.md` files to a self-hosted server over Tailscale
at the end of every session, for weekly analysis. Design lives in `docs/SYNC.md`; the core
decision it's built around is sending the session file's exact bytes unmodified, with all
interpretation on the server side, so the sync code never breaks when the on-disk YAML
schema changes (which happens most phases in this project). `SyncLedgerRepository`
maintains a local durable ledger (`gymdata/_sync/state.yml`, PENDING/SENT/FAILED per
session ID + content hash) so delivery survives app kills, offline phones, and a
temporarily-down server without losing anything — retried via `SyncWorker`
(`@HiltWorker`/`CoroutineWorker`, WorkManager) both as an expedited one-off right after
each session and a 4-hourly periodic durability net, both with exponential backoff.
`ActiveRoutineViewModel.registerRoutine()` enqueues right after the session's final save;
`WorkoutRepository`'s rename-sync paths requeue an already-SENT session if a later edit
changes its content hash. `OptionsScreen` gained a sync section: server URL + bearer token
fields, an enabled switch (off by default until both are filled in), a connection test
button, a pending-count/last-sync status line, and a "resync all" backfill action. New
deps: OkHttp (multipart POST), WorkManager + Hilt-Work (`MyGymApp` is now a
`Configuration.Provider`; WorkManager's default `androidx.startup` initializer is disabled
in the manifest so Hilt can construct the worker). The server side is a separate
repository (`MyGymApp_server`, spec at `docs/sync-ingestion/SPEC.md` there) — not part of
this codebase; not yet tested end-to-end against a live server from a phone.

## Phase 31 — `Exercise.isBodyweight`

Server-side analysis work (implementing the tonnage/PR/e1RM logic from `ANALYSIS_SPEC.md`
against synced session data) surfaced a real gap: `weight: 0.0` on a set is ambiguous
between "never touched" and "genuinely bodyweight work" (plank, push-ups — zero external
load by design), and any filter on `weight > 0` silently drops all bodyweight tonnage.
Added `isBodyweight: Boolean` to `Exercise` (toggled once per exercise in
`ExerciseEditScreen`, next to the rep-range picker, FORZA only), propagated onto each
`ExerciseSet.Strength` when a session's sets are built or re-saved
(`ActiveRoutineViewModel`, `StrengthExerciseViewModel`, `SupersetViewModel`) and persisted
per-set (`WorkoutParser`, omitted when false). Also fixed a related pre-existing bug in
`StrengthExerciseViewModel.updateSet()`: `allSetsFilled` required `weight > 0`
unconditionally, so it could never become true for a bodyweight exercise — now accepts
`reps > 0` alone when the exercise is marked bodyweight. `bestEstimated1RM`'s `weight > 0`
filter was deliberately left as-is: an Epley 1RM estimate is conceptually inapplicable
without external load, and evaluates to 0 for a bodyweight set regardless. See
`docs/SYNC.md`'s note on this change for why no sync-layer code needed touching — the
raw-file design absorbed the new field for free.

## Phase 32 — Session-RPE (Foster method)

Added subjective session-RPE collection at end-of-workout, driven by a server-side request
(the self-hosted server's tonnage-based ACWR monitoring needed a matching internal-load
signal to validate/enrich against — see `docs/SYNC.md`). `WorkoutSession` gained
`sessionRpe: Int?` (0-9, null only transiently before the prompt is answered),
`sessionLoad: Float?` (`sessionRpe × duration_minutes`, Foster's session-load method), and
`startedAt: String` (previously only documented in STORAGE.md, not actually on the Kotlin
model — now implemented and set at session creation, since `sessionLoad` needed a real
duration). `ActiveRoutineScreen`'s "Registra routine" button now opens a mandatory
`SessionRpeDialog` (two rows of 0-9 chips, no skip option, confirm disabled until a chip is
picked, outside-tap dismiss and the screen's back handler both disabled while it's open)
before the existing register flow runs; `ActiveRoutineViewModel.registerRoutine()` takes
the rating as a parameter rather than stashing it on `currentSession`, because the function
reloads the session from disk multiple times before its final save and an early write
would get silently overwritten — see CONVENTIONS.md's "Session-RPE prompt: apply after the
reload, not before" entry. Both fields are still nullable/omitted from the YAML when
absent (covers abandoned sessions and pre-existing history), following the same
omit-when-absent convention as every other optional session field, and sync automatically
via the existing raw-file transport with no sync-layer changes needed (`docs/SYNC.md`'s
second worked example of that design paying off). ACWR/readiness interpretation of this
data stays server-side, out of scope for this repo.

Note: the original server-side request explicitly asked for this to be optional/skippable;
made mandatory instead per direct follow-up instruction from the user, overriding that.

## Phase 33 — Polar battery warning at 70%

Added an early low-battery warning for the H10, triggered at 70% rather than the usual
20%. Prompted by a strap that stopped advertising entirely — invisible to every BLE scan,
so the app logged endless failed reconnects — while its CR2025 still measured 3.0V on a
multimeter and the last reported level had been above 50%. The cause is that a lithium
coin cell holds near-nominal open-circuit voltage until it is almost spent; what actually
kills it is internal resistance rising to the point where the ~10 mA transmit peak browns
out the radio mid-advertisement. The H10 derives its percentage from voltage alone (BLE
Battery Service 0x180F is a plain 0-100 integer, and there is no coulomb counter on the
device), so the reported number stays high and then falls off a cliff — it cannot express
the failure mode that matters. Hence `BATTERY_WARNING_THRESHOLD = 70`: below that the
number carries no predictive value and should be read as "replace it soon".
`PolarManager` gained a `batteryLow: StateFlow<Boolean>` alongside the existing
`batteryLevel`, cleared on disconnect and on BLE power-off; `batteryLevelReceived` now
also writes every reading to `app.log`, so the decay curve is recoverable across battery
cycles instead of only the instantaneous value being visible. `HeartRateScreen` tints the
existing battery row red and swaps in `BatteryAlert` below the threshold, plus an explicit
warning line. `HeartRateViewModel` collects the new flow separately rather than extending
its `combine`, which was already at the 9-flow overload.

Note: no reconnect-logic changes were kept from this investigation. An earlier attempt had
added a resident watchdog and an indefinite two-phase reconnect backoff on the theory that
`api.connectToDevice()` silently no-ops on a cold SDK cache; `adb` tracing showed the scan
was in fact reaching the BLE stack correctly and simply finding nothing, because the device
was not transmitting. Those changes were discarded — they also had the resident watchdog
sharing `reconnectHandler` with `scheduleReconnect()`, whose `removeCallbacksAndMessages`
cancelled the watchdog permanently after its first tick.

## Phase 34 — Raw ECG sync (fourth record type)

Added `EcgSyncLedgerRepository`/`EcgSyncApi`/`EcgSyncWorker` under `data/sync/`, following
the exact dedicated-classes pattern established for readiness and scale weigh-ins (see
Phase 30). Moves the goal of ECG analysis from "phone computes everything, raw waveform
discarded" toward "phone computes a lightweight local summary, server gets the raw
waveform for a heavier/more accurate analysis" — motivated by wanting more CPU budget and
potential ML/LLM-assisted interpretation than a phone can offer, informed by the user's
full history rather than one isolated session.

Structurally different from the other three sync pipelines because the source file
(`ecg/{sessionId}.ecg`) is ephemeral by design, not a permanent local record:
- The raw file is gzip-compressed before upload (`application/gzip`, longer OkHttp
  timeouts than the other three APIs) and `contentHash` is computed over the compressed
  bytes, verified against what the server actually receives.
- Deletion moved from `ActiveRoutineViewModel.registerRoutine()` (previously: immediate,
  once local analysis succeeded) into `EcgSyncWorker` (now: only after a confirmed `SENT`
  upload) — but only when sync is configured/enabled; otherwise the original immediate-
  delete-on-success behavior is unchanged, so phones without a server configured don't
  accumulate `.ecg` files with nothing to drain them.
- A new 30-day age cap (`EcgSyncLedgerRepository.expireStale()`) marks stale pending
  entries `EXPIRED` and deletes their file regardless of upload status — a deliberate
  departure from the other three pipelines' "retry forever" philosophy, needed because an
  ephemeral source file can't be allowed to accumulate unboundedly during an extended
  server outage (observed multi-day in practice — see Phase 30). `SyncStatus` gained this
  `EXPIRED` value; the other three ledgers never assign it.

`PolarManager.ecgFileFor()` added as a thin wrapper (mirrors `ecgFileSize`/
`deleteEcgFile`/`analyzeSessionEcg`). `OptionsViewModel`'s pending-count status line and
"Invia tutti i dati in coda" now cover all four pipelines — for ECG specifically, resync
only picks up files still present in `gymdata/ecg/`, since a file already uploaded and
deleted has nothing left on the phone to resend. `MyGymApp.onCreate()` schedules
`EcgSyncWorker.Scheduler.ensurePeriodic()` alongside the other three.

Server side (`MyGymApp_server`) not yet implemented — spec written
(`docs/sync-ingestion/ECG_SPEC.md`) covering `POST /v1/ecg`, the binary `.ecg` format, and
a new `ecg_recordings` SQLite table. See [SYNC.md](SYNC.md#fourth-record-type-raw-ecg) for
the full design, including why the server's raw store becomes the *only* copy of the
waveform once the phone deletes its local file (unlike sessions/readiness/scale, which all
keep the phone as a permanent secondary copy).

## Phase 35 — Drop local deep ECG analysis, keep only resting HR / VO2max

Following Phase 34's raw-ECG-upload feature, removed local execution of deep ECG analysis
entirely: `ActiveRoutineViewModel.registerRoutine()` no longer calls
`PolarManager.analyzeSessionEcg()`, so Pan-Tompkins QRS detection, RMSSD/SDNN/pNN50/
Poincaré, and arrhythmia markers (PAC/pause/irregular/AFib suspicion) never run on the
phone anymore. That analysis is now exclusively a server-side concern, run against the raw
waveform uploaded by Phase 34's sync pipeline. `EcgAnalyzer`/`PolarManager.analyzeSessionEcg()`
are left in the codebase unused rather than deleted — the raw file format they parse is
unchanged, and they cost nothing while dormant.

The phone keeps computing and showing only what doesn't require deep waveform analysis:
resting HR and VO2max (both derived from live HR/readiness tracking, shown at session end
and on the heart-rate/cardiovascular screen), plus TRIMP and kcal (Banister/Keytel,
computed continuously during the session, unchanged) and cardiac drift / HRR60s (cheap
HR-series computations, not deep waveform analysis — also unchanged).

Raw `.ecg` file handling simplified to send-then-delete with no local-analysis fallback:
if sync is configured, the file is unconditionally enqueued for upload (previously gated
on `ecgResult.hasAnything`) and deleted only after a confirmed server `SENT`; if sync isn't
configured, the file is now deleted immediately rather than conditionally kept for offline
inspection — there's no local analysis left that would ever consume it.

`WorkoutSession`'s 12 ECG-derived fields (`ecgBeats`, `ecgDurationSec`, `ecgAvgHr`,
`ecgSessionRmssd`, `ecgPacCount`, `ecgPauseCount`, `ecgIrregularBeats`, `sdnn`, `pnn50`,
`poincareSd1`, `poincareSd2`, `poincareRatio`, `afibSuspicionEpisodes`) are left declared
at their zero defaults rather than removed, for backward compatibility with sessions saved
before this change (the YAML parser and history views for old sessions keep working
unchanged). `SessionProgressScreen`/`SessionProgressViewModel` dropped the charts/text that
read those 12 fields (avg-HR-from-ECG, HRV RMSSD/SDNN, Poincaré ratio, PAC/pause/irregular
counts, AFib suspicion callout), keeping only HRR/VO2max/resting-HR/cardiac-drift charts.

Also deleted `CardioTrendLoader.kt`/`CardioTrendSection.kt` — a 4-week rolling cardio trend
view built on the same 12 ECG fields, discovered to be dead code (never called from any
screen, confirmed via repo-wide search) while auditing what needed to change. Removed
rather than updated, since nothing rendered it.

## Phase 36 — ECG debug send button

Added a manual "Registra e invia ECG di debug" button to Options' debug section, for
exercising the `POST /v1/ecg` round-trip (docs/SYNC.md "Fourth record type: raw ECG")
without running a full workout session: connect the Polar strap, open Options, press the
button. `OptionsViewModel.sendDebugEcg()` calls `PolarManager.startEcgRecording()` with a
synthetic id (`debug-{epoch millis}`, not a real 8-hex session id), waits 10 seconds,
stops, then enqueues the recorded file through the exact same
`EcgSyncLedgerRepository`/`EcgSyncWorker` pipeline a real session uses — no
debug-specific transport code. Requires a connected Polar device and a configured sync
server (same gating as "Invia tutti i dati in coda"); the button is disabled and an
inline result message explains why otherwise. `PolarManager.startEcgRecording()`/
`stopEcgRecording()` needed no changes — they were already generic (keyed only on the
connected device, not tied to an active `ActiveRoutineViewModel` session).

The `debug-` id prefix is a signal for the server team to tell debug uploads apart from
genuine session recordings if that ever matters (e.g. excluding them from real analysis
runs) — see the updated `ECG_SPEC.md` handoff note.

## Phase 37 — Options screen crash fix + redesign

Fixed a real crash: double-tapping "Invia tutti i dati in coda" fired
`OptionsViewModel.resyncAll()` twice concurrently; the second run's ECG file loop had no
`file.exists()` guard (unlike the other three record types' loops, which all had one) —
`EcgSyncWorker` deleting the file mid-flight after the first run's confirmed send crashed
the app with `FileNotFoundException` on `readBytes()`. Fixed with both a `file.exists()`
check (matching the other three loops) and an entry guard on `resyncAll()` itself so a
second tap while one run is in flight is a no-op instead of a second concurrent run.

Redesigned the Options screen debug/sync sections based on direct feedback that the
layout was cluttered:
- Removed "Inserisci dati di debugging" (seed data) entirely from the screen — the
  underlying `MainViewModel.seedDebugData()` is left in place, unused, in case it's
  wanted again later.
- Split the old single "Dati di debugging" card (seed button + Scale BLE Debug + ECG
  debug all mixed together) into two focused cards: "Debug bilancia" (just the Scale BLE
  Debug button) and "Debug ECG" (the record+send button from Phase 36).
- Reordered top-to-bottom: Powerlifting → Debug bilancia → Debug ECG → Sincronizzazione
  server — powerlifting first since it's the setting used most routinely, debug cards
  grouped together, sync last since it bundles the most controls.
- Pending-sync count changed from a single summed number to a per-type bullet list
  (Sessioni / Pesate / ECG) — `OptionsUiState` now exposes `syncSessionsPending`/
  `syncScalePending`/`syncEcgPending` alongside the existing summed `syncPendingCount`,
  all populated by the same `refreshSyncStatus()`.
- Added a 5s poll (`pollSyncStatusWhileScreenOpen()`) alongside the existing WorkManager-
  completion observer as a safety net — the observer only fires when a *specific* unique
  work name transitions to finished, which can miss a still-`ENQUEUED` job waiting on
  network constraints or the periodic durability net firing in the background. Reported as
  "doesn't seem to update well"; the poll is cheap (each ledger read is a small local YAML
  file) and guarantees the pending list is never silently stale for more than a few seconds.
- Unified button style: one filled `Button` per card for the primary/most-common action
  ("Invia dati in coda", the seed button previously), everything else (Verifica
  connessione, Test sincronizzazione, Scale BLE Debug, Registra e invia ECG, Disattiva
  avviso powerlifting) as `OutlinedButton` — previously inconsistent (e.g. "Test
  sincronizzazione" was filled, "Invia tutti i dati in coda" was outlined, with no
  discernible reason for the difference).
- Trimmed every description text to one short line; several were multi-sentence
  paragraphs restating what the button below already said.

## Phase 38 — ECG debug countdown + resync progress bar

Two follow-ups to Phase 37's redesign, from direct usage feedback:

- Moved the "Debug ECG" button from its own card into the sync card, directly below
  "Verifica connessione" — grouped with the other server-reachability checks instead of
  living in a separate card.
- The button now shows a live countdown while recording (`"Registrazione ECG… 7s"`,
  ticking down to 0) instead of a bare spinner — `OptionsViewModel.sendDebugEcg()` ticks
  `ecgDebugSecondsLeft` down once per second via a loop instead of a single 10s `delay()`.
- "Invia dati in coda" now shows a real determinate `LinearProgressIndicator` plus a
  percentage in the button label, instead of an indeterminate spinner. `resyncAll()`
  snapshots the total item count right after enqueueing (the denominator), then
  `trackResyncProgress()` polls all four ledgers' pending counts once a second and derives
  `syncResyncProgress` (0..1) from how much of that batch has drained, up to a 60s timeout
  (workers keep retrying via their own backoff after that regardless — the timeout only
  stops the UI from polling forever, it doesn't cancel the actual sync).

Also clarified, on request, the difference between "Verifica connessione" and "Test
sincronizzazione" (no code change, just for the record): the former is a bare `GET
/health` reachability check; the latter (`SyncDiagnostics.run()`) actually POSTs a real or
synthetic session and re-sends it to confirm the server's idempotency path answers
`duplicate` — a deeper functional test, not a duplicate control.

## Phase 39 — ECG analysis handoff doc for the server

No phone-side code changes. Wrote `docs/sync-ingestion/ECG_ANALYSIS_HANDOFF.md` in the
`MyGymApp_server` repo — a complete, formula-exact specification of the ECG analysis the
phone used to run (`EcgAnalyzer.kt`: Pan-Tompkins R-peak detection, RMSSD/SDNN/pNN50/
Poincaré, PAC/pause/irregular-beat/AFib-suspicion screening) before Phase 35 removed it
from the phone. Prompted by discovering, while inspecting the 39 real raw ECG files the
raw-sync backfill had sent to the server, that `ANALYSIS_SPEC.md` (server repo) still said
these 12 fields "arrive pre-computed... do not recompute them" — no longer true since
Phase 35, and left uncorrected until now, which would have misled anyone implementing
server-side analysis into thinking the fields already existed in synced data.

Corrected `ANALYSIS_SPEC.md`'s §"Already computed phone-side" and §6 accordingly (moved
the 12 ECG fields out of the "already computed" list, reframed §6 from "optional/skip
this" to "required — see handoff doc"). The handoff doc also explicitly separates what
was actually shipped and working (§1–§5, transcribed verbatim from source) from what the
original research doc (`docs/polar/implementation-guide.md`) sketched but never built
(PVC/QRS-morphology detection, RSA-via-FFT, DFA alpha1, per-exercise HRV baselines) — so
the server team doesn't assume a richer feature set exists than what's actually in
`EcgAnalyzer.kt`.

## Phase 40 — Gate automatic on-connect readiness measurement

The 60s resting-HR/HRV readiness measurement that auto-starts on first Polar connect now only
fires before 10:00 local time, and only if no readiness event has been persisted yet today
(`ReadinessRepository.getLatestForDate()`). Reconnecting the strap later the same day (e.g.
after an accidental disconnect) reuses today's already-saved result instead of re-measuring —
loaded back into `readinessResult`/`vo2max`/`restingHr` via `PolarManager.maybeStartAutoReadinessMeasurement()`.

## Phase 49 — Daily step average, piggybacked on readiness

Added a passive daily step count, read from the phone's own hardware `TYPE_STEP_COUNTER`
sensor (not the Polar strap) at the same moment the morning readiness test runs, since
that's the app's one guaranteed daily touchpoint and didn't justify a separate
trigger/service. New `data/steps/` package: `StepCounterReader` does a one-shot sensor
read (returns `null` on missing sensor/permission/timeout, never throws), and
`StepLedgerRepository` diffs it against a local-only checkpoint
(`gymdata/_sync/step_checkpoint.yml`, the counter is cumulative since last boot, not
"steps today") to produce an average-per-day figure that correctly spreads the total
across however many days were skipped since the last test, rather than reporting a
skipped multi-day total as if it were one day's steps. `ReadinessEvent` gained two
nullable fields, `stepsAvgPerDay`/`stepsDaysSpanned`, synced to the server through the
existing readiness pipeline (`ReadinessSyncApi`/`ReadinessSyncWorker`) untouched
otherwise — both fields ride the same envelope and file as the rest of the readiness
event. `ACTIVITY_RECOGNITION` (Android 10+ runtime permission) is requested
fire-and-forget when `HeartRateScreen` opens, alongside the existing BLE/scale permission
requests. Full field semantics and required server-side schema change:
[SYNC.md § Daily step average](SYNC.md#daily-step-average).

## Phase 50 — Steps: migrate from raw sensor to Health Connect, add debug button

Phase 41's `TYPE_STEP_COUNTER`-based implementation turned out not to work on real
hardware. Added an Options screen debug button ("Debug contapassi") to check the pipeline
without waiting for the next morning's readiness test, and it immediately surfaced the
problem: on this project's Samsung/One UI test device, `dumpsys sensorservice` showed the
sensor delivering only 1 event in 4 days despite `ACTIVITY_RECOGNITION` being granted.
Registering a persistent app-lifetime listener (`StepCounterManager`, started from
`MyGymApp.onCreate()`) didn't fix it either — `dumpsys` kept reporting `has sensor access:
false` for the app specifically, with every other app on the device reading `true`. Traced
to a separate OS-level "Health, fitness and wellness" permission, gating the same sensor,
that has **no manual toggle reachable from Settings** — Settings → Permission manager
showed it, but tapping into it offered no way to grant it. The only way to request that
permission is Health Connect's own flow.

Replaced the whole step-reading path with `HealthConnectStepsReader`
(`androidx.health.connect:connect-client`, new dependency — bumped `agp` from 8.7.3 to
8.9.3 in `libs.versions.toml` since connect-client 1.1.0 requires AGP 8.9.1+) and deleted
`StepCounterReader`/`StepCounterManager` entirely. `StepLedgerRepository` got simpler as a
result: Health Connect aggregates over an explicit time range itself
(`HealthConnectClient.aggregate`), so the checkpoint is now just "when was this last read"
(an `Instant`) instead of a raw counter value needing manual diff-and-handle-reboot logic.
The manifest permission changed from `android.permission.ACTIVITY_RECOGNITION` to
`android.permission.health.READ_STEPS`; `HeartRateScreen`'s fire-and-forget permission
request now uses `PermissionController.createRequestPermissionResultContract()` instead of
`ActivityResultContracts.RequestPermission()`. `ReadinessEvent`'s two nullable fields
(`stepsAvgPerDay`/`stepsDaysSpanned`) and the sync wire format are unchanged — this was a
read-path swap, not a schema change. Full details:
[SYNC.md § Daily step average](SYNC.md#daily-step-average).

The debug button stays in Options going forward: it's what actually caught this, well
before it would have otherwise surfaced (silently, as "steps always null") days later at
the next readiness test.

## Phase 51 — Centralize all runtime permission requests on the Home screen

BLE (Polar/scale) and Health Connect steps permissions were each requested from the
specific screen that needed them (`HeartRateScreen`). Moved to a single `LaunchedEffect` in
`MainScreen` (`RequestAllRuntimePermissions`, backed by a new small `PermissionsViewModel`)
that fires every time Home appears — so a permission the user revokes later, or one that
was never granted because they simply never navigated to the screen that asks for it, gets
re-prompted from the one screen every session always passes through, not silently left
missing. Both requests stay fire-and-forget with no-op callbacks: BLE scanning and the
Health Connect steps read both already check their own permission state lazily wherever
they're actually used (`HeartRateScreen`'s scan buttons, `PolarManager`'s readiness flow),
so Home's job is only to prompt, never to gate an action on the result. `POST_NOTIFICATIONS`
stays where it was (`MainActivity.onCreate()`, before Compose even starts) — moving it
wouldn't have changed behavior, only where it lives.

## Phase 52 — Fix Health Connect permission dialog not appearing at all

Phase 42/43's Health Connect integration compiled and ran, but the permission dialog
itself never showed up: `PermissionsActivity` (Health Connect's own) opened and
self-closed within ~30ms, with no dialog, no error, and no logcat trace explaining why —
confirmed via `dumpsys activity activities` showing the activity transition completing and
immediately reversing. Root cause: Health Connect requires the requesting app to declare a
"permissions rationale" activity — its explanation of what the app does with health data —
and refuses to show the permission dialog at all if that declaration is missing or
incomplete, rather than failing loudly. Two separate manifest declarations are required,
one per Android version range (confirmed against Android's own Health Connect
documentation): an `<activity>` with an `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`
intent-filter for Android 13 and below, AND an `<activity-alias name="ViewPermissionUsageActivity">`
(with `android:permission="android.permission.health.START_VIEW_PERMISSION_USAGE"` and a
`VIEW_PERMISSION_USAGE`/`HEALTH_PERMISSIONS` intent-filter) for Android 14+ — the test
device is Android 16, so only the alias was actually exercised, but both are needed for
real device coverage. Phase 42 had added only a same-intent-filter-on-MainActivity
half-measure, which doesn't satisfy either requirement.

Added `PermissionsRationaleActivity` — a real Compose screen explaining what steps data is
read, when, and where it goes (self-hosted sync only, opt-in) — and wired both manifest
declarations to it. Confirmed fixed on hardware: `dumpsys activity activities` now shows
Health Connect's `PermissionsActivity` staying resumed (dialog visible) instead of
self-closing, and after granting, `dumpsys package` shows
`android.permission.health.READ_STEPS: granted=true`. The Options debug button (Phase 41)
confirmed the full path end to end afterward.

## Phase 53 — Steps: show a real number on the very first read, not just from day two

After Phase 44 fixed the permission dialog, granting it still showed nothing — expected
given the checkpoint-diff design (first-ever read has no "previous" checkpoint to diff
against), but a needlessly bad first impression: Health Connect already has historical step
data from before the app ever had permission to read it, so there was no real reason to
wait a full day for the first number. `StepLedgerRepository.recordReadingAndComputeAverage()`
and `.peek()` both now fall back to querying the last 24h directly (a real Health Connect
time-range query, not a diff) when no checkpoint exists yet, reporting `daysSpanned=1` for
that reading same as any single-day reading. Every subsequent read goes back to normal
checkpoint-diffing. No schema/wire-format change.

## Phase 54 — Show step count on the readiness card

The synced `stepsAvgPerDay`/`stepsDaysSpanned` had no on-screen representation anywhere —
the only way to see them was the Options debug button or reading the raw `.md` file. Added
them to `PolarManager.ReadinessResult` (previously only carried HRV/resting-HR/VO2max) and
to the readiness card in `HeartRateScreen`, next to VO2max. Since the Health Connect steps
query is async and slightly slower than the rest of readiness (see
`finishReadinessMeasurement()`), the steps fields patch onto the already-published
`_readinessResult` a moment later rather than holding up the HRV UI update for them — guarded
by a `readiness` value match so a slow steps read can't clobber a newer measurement if the
user re-tests within the same session. Absent (not `0`) until that patch lands, and stays
absent if there's no prior checkpoint. Lets the user confirm on their own device, right on
the readiness screen, that a real number shows up the morning after granting the Health
Connect permission — the thing Phase 44/45 fixed.

## Phase 55 — Show steps walked during the session, display-only

Session-end summary (`SessionProgressScreen`, the kcal/TRIMP/VO2max card) now also shows
steps walked during that specific session's own `startedAt..completedAt` window — queried
fresh from Health Connect (`HealthConnectStepsReader.totalSteps`) each time the screen
loads. Deliberately **not** saved onto `WorkoutSession` or synced anywhere: the figure that
does get persisted/synced is `stepsAvgPerDay` on the readiness event, a whole-day average,
a different number measuring a different thing. This is purely informational for the user
in the moment, nothing server-side needs or expects it. Null (not shown) when Health
Connect is unavailable or the session predates `startedAt` being recorded.

## Phase 41 — Live HR-zone widget

Added a live, on-device %HRR (Karvonen) Z1-Z5 zone widget to `HeartRateBar`, following the
spec drafted server-side (see [SYNC.md](SYNC.md)) alongside the sync server's retrospective
`compute_hr_zone_minutes`. `HrZoneCalculator` mirrors the server's zone math exactly: `max_hr`
is the unweighted mean of the Fox/Tanaka/Gulati age formulas (from `UserProfile.birthYear`,
newly added to the profile — falls back to the existing `age` field for users who haven't set
one), `resting_hr` comes from today's readiness measurement or a 7-day trailing average
(`ReadinessRepository.getRecentAverageRestingHr()`, new), falling back to plain %HRmax if
neither is available. `HrZoneTracker` accumulates per-zone minutes in memory for the
in-progress session only (reset on `startHrSeriesCapture()`) — not persisted; the server-side
ECG-derived analysis after sync remains the durable historical record. `HeartRateBar` gained a
zone chip ("Z3 · 74%") and a thin time-in-zone stacked bar, using the same zone color palette
as the server dashboard for visual consistency. Entirely local-first: no server reachability
required at workout time.

## Phase 42 — Cardio blocks: a third exercise type with explicit start/stop

Added `ExerciseType.CARDIO` alongside FORZA/STRETCH, so a session can mark exactly when a
cardio phase begins and ends instead of only having a session-wide, undifferentiated HR
stream. Cardio exercises (e.g. "Corsa leggera", "Bici") are catalog entries like any other,
created via the normal exercise editor (new third `FilterChip`). In a routine, they have no
pre-configured set count — `RoutineEditScreen` hides the Sets/rep-range/time-per-set fields
for CARDIO and, since a cardio exercise's data doesn't fit the reps/weight-or-timeSeconds/
done shape `SupersetViewModel`/`SupersetScreen` know how to interleave, the "Superset" link
button never shows next to one either (guarded with an explicit `error()` in both files'
exhaustive `when` branches as defense-in-depth, on top of the upstream UI prevention).

New `CardioExerciseScreen`/`CardioExerciseViewModel` (mirroring
`StretchExerciseScreen`/`ViewModel`'s completionSaved/onCleared pattern) show "Inizia
cardio"/"Termina cardio": each press cycle appends one `ExerciseSet.Cardio` block
(`startedAt`/`endedAt`/`avgHr`/`maxHr`) to the exercise's `sets` — several blocks are
supported per session (e.g. 10 min bike then 20 min run, or cardio resumed later after
stretching in between). `avgHr`/`maxHr` are computed on-device from `PolarManager.heartRate`
while a block runs, accumulated in the ViewModel (not `PolarManager` — a block is a session/
exercise concept, not a Polar-subsystem one). A block still running when the app dies
mid-session (not a clean "Termina cardio") is closed silently next time the screen opens,
same spirit as the existing ghost-session guard, which itself was extended to treat a
started-but-unfinished cardio block as real data (same treatment as a touched strength/
stretch set). `excludeFromTonnage` is always `true` for CARDIO. The screen also shows a
small history panel for the same `exerciseId` across past sessions, reusing
`WorkoutRepository.getSessionsForExercise()` unchanged and aggregating inline (summed
duration, averaged `avgHr`, maxed `maxHr`) — no new repository.

**No change to the raw ECG pipeline.** `EcgRecorder`/`startEcgRecording`/`stopEcgRecording`
and the binary `.ecg` format are untouched — still one continuous stream per session. Instead,
the sync server (documentation only here; implementation is `MyGymApp_server`'s job) derives
per-block sample ranges after the fact by combining each block's absolute `startedAt`/
`endedAt` (from the synced session YAML) with the `.ecg` file's own `startTimestamp`+
`sampleRate` header (from the separately-synced raw ECG) for the same `sessionId` — see
SYNC.md's new "Cardio blocks" section. Absolute timestamps were chosen over a phone-computed
sample offset specifically to avoid compounding sample-rate drift over a long recording.

## Phase 43 — Cardio UX fixes: dedicated section, configured-duration countdown, no bodypart

Three fixes to Phase 42's cardio exercises, from real on-device usage feedback:

1. **Dedicated "Cardio" list section instead of bodypart grouping.** Cardio exercises have
   no meaningful bodypart, so they used to land in a stray/blank `groupBy` bucket mixed in
   wherever iteration order happened to place it — easy to miss unless searching by exact
   name. `ExerciseListViewModel` now partitions CARDIO exercises into their own
   `cardioExercises` list, always rendered by `ExerciseListScreen` as a fixed "Cardio"
   section pinned above the bodypart groups (applies to both the plain list and the
   routine-editor picker, since they share the same screen/VM). The `BodyPartAutocomplete`
   field is now hidden entirely in `ExerciseEditScreen` for CARDIO, and `bodypart` is forced
   to `""` on save (`ExerciseEditViewModel`) rather than left showing an unused field.
2. **Configured-duration countdown, not a count-up stopwatch.** `RoutineEditScreen` gained a
   "Durata cardio" (minutes) picker for CARDIO exercises, repurposing
   `RoutineExercise.timePerSetSeconds` as a single total block duration instead of hiding it
   entirely. `CardioExerciseViewModel` fetches this from the owning routine at session time
   (same lookup pattern `SupersetViewModel` already used for rep ranges, including the
   fixed-daily-routine case) and `startBlock()` now counts *down* from it instead of up from
   zero — continuing into negative/overtime past zero rather than auto-stopping, since the
   countdown is a pacing aid, not an enforced cutoff. Only an explicit "Termina cardio" tap
   ends a block, exactly as before.
3. **Fixed a real type-label bug in `ExerciseCard`**, found while implementing the above:
   the exercise-type badge used `if (type == FORZA) "Strength" else "Stretch"` — a CARDIO
   exercise silently showed "Stretch" as its label, since the check was a binary `if/else`
   rather than an exhaustive `when` the compiler could have flagged. Fixed to a `when` over
   all three types; see the new CONVENTIONS.md note about preferring exhaustive `when` over
   `if/else` for any `ExerciseType` branch specifically to catch this class of bug at
   compile time going forward.

## Phase 44 — Cardio duration: routine-only, long-press ×10

Reverted Phase 43's `Exercise.defaultDurationSeconds` (catalog-level default duration,
`ExerciseEditScreen`'s "Durata cardio di default" picker) — the duration is set per routine
only now, in `RoutineEditScreen`'s existing "Durata cardio" picker, no catalog-level field.
That picker also switched from plain `RoundStepButton` +/− to `ScrollPickerInput` with
`longPressRepeatStep = 10.0`, so holding either button jumps 10 minutes at a time — same
widget and behavior already used for the weight picker on strength sets (`SupersetScreen`).

## Phase 45 — Birth year replaces the plain Age field

Removed `UserProfile.age` entirely — birth year (`UserProfile.birthYear`) is now the sole
source of age for every age-dependent formula (Keytel calories, Tanaka HRmax, Banister
TRIMP, VO2max, BIA body-fat %), via the previously-unused `UserProfile.effectiveAge`
(`birthYear`-derived, recomputed from the current year each time; falls back to a fixed
default of 30 until `birthYear` is set — same practical default the old `age` field always
shipped with). This also fixes a real, previously-undetected bug: `effectiveAge` existed
since birth-year support was added but was never actually called anywhere — Keytel, TRIMP
(via `UserProfile.hrMax`), VO2max (via `hrMax`), and BIA body-fat % all read the plain `age`
field directly, silently ignoring `birthYear` even when set. Only the live HR-zone widget
(`HrZoneCalculator.estimatedMaxHr`) already preferred `birthYear` correctly. Now all five
consumers go through `effectiveAge`/`hrMax`, so setting birth year actually affects every
formula, not just one.

`ProfileSection` (`HeartRateScreen.kt`) lost its "Age" picker; "Birth year" is now the
primary, always-shown field (no longer labeled "optional") alongside Height. The HRmax
caption below distinguishes an unset birth year ("Imposta l'anno di nascita per calcoli
accurati") from a real one, rather than silently showing a Tanaka estimate as if it were
authoritative either way. `HrZoneCalculator.estimatedMaxHr` was simplified to take an
already-resolved `age: Int` instead of duplicating the birthYear-vs-fallback resolution
logic itself — `UserProfile.effectiveAge` is now the only place that resolution happens.

## Phase 46 — PolarManager reads UserProfile fresh, no more stale weight

Found while double-checking Phase 45's age fix for a similar bug with weight:
`PolarManager.userProfile` was a manually-synced **cached copy** (`private var`, read once
at construction), kept in sync only when `HeartRateViewModel` observed a scale weigh-in
finishing (`ScaleConnectionState.CONNECTED → DISCONNECTED`) and explicitly called
`polarManager.updateUserProfile(refreshedProfile)`. If an HR session was already running in
the background (`PolarStreamingService`) when the user weighed in — or any time
`HeartRateViewModel` simply wasn't alive/collecting at that moment — Keytel calorie
calculations kept using the previous, stale weight instead of the just-recorded one, with no
way for the user to notice.

Fixed the same way as `effectiveAge`: `PolarManager.userProfile` is now a computed property
that reads `UserProfileRepository.get()` fresh on every access instead of caching. This is
cheap even at the ~1Hz HR-sample rate `accumulateCaloriesAndTrimp()` runs at, since
SharedPreferences is already in-memory-cached by Android after the first read — no new IO
cost, just removes the staleness window. `PolarManager.updateUserProfile()` became
unreachable (nothing left to assign into a computed property) and was deleted, along with
its three call sites in `HeartRateViewModel` (`updateGender`, `updateBirthYear`, and the
scale-disconnect handler) — `profileRepo.save()`/`_uiState` updates there are unaffected,
only the now-redundant `PolarManager` bridge call was removed.

BIA body-fat % (`BodyCompositionCalculator`, via `BleScaleManager`) was already unaffected —
it always used the freshly-averaged weight from the current BLE weigh-in session directly,
never routing through `UserProfile.weightKg` at all.

## Phase 47 — Drop calorie count from in-workout HR bar; remove recovery semaphore

Two `HeartRateBar` UI elements removed per user request. Calorie count: was shown live during
exercise execution (every screen that embeds `HeartRateBar` — strength, cardio, superset,
stretch, and the active-routine overview); the underlying `sessionCalories` StateFlow and its
consumers elsewhere (session save, `SessionProgressScreen`'s own independent calorie card,
`ActiveRoutineScreen`'s post-completion `ProgressSection`) are untouched — only the `HeartRateBar`
display during exercise execution was removed, so calories are still visible on the session
summary screen. Recovery semaphore: the red/yellow/green `Semaphore`/`SemaphoreLight`
composables and their call site are deleted outright, along with the public `RecoveryState` enum
and `PolarManager.recoveryState` StateFlow — nothing else in the codebase read that StateFlow.
`PolarManager.updateRecoveryState()` is kept (renamed in spirit, not in name) as an internal-only
function: it still drives `isRecovering` reset and the `rmssd` StateFlow (used elsewhere for HRV
display), it just no longer computes or exposes a three-way recovery state. The independent HRR
delta pipeline (`pendingHrrPeaks`, `_liveHrrLast`) and peak-detection loop are unaffected — they
never depended on the semaphore.

## Phase 48 — Live HR-zone trace chart

New `HrZoneTraceChart` on the active-routine and cardio-exercise screens (always visible when
the strap is connected, not gated on the cardio timer). Vertical axis is %HRR with the Z1-Z5
bands drawn **proportionally** to their real Karvonen spans, so the dot's height agrees with
the `Z3 · 74%` chip `HeartRateBar` already shows; horizontal axis is time, with "now" pinned at
the right edge and the trace growing leftward over a ~90s window. The current-value dot is
tinted with its zone's colour over a white backing ring so it stays legible against its own band.

To avoid the two halves drifting apart, both share a single source of truth:
`HrZoneCalculator.ZONE_BOUNDARY_FRACTIONS` (extracted from what was an inline literal list in
the classifier) drives both the BPM cutoffs and the chart's bands, and
`PolarManager.HR_ZONE_TRACE_MAX_POINTS` is public so the chart right-anchors on exactly the
buffer size the manager fills. The rolling `hrZoneTracePercents` buffer lives on the
`@Singleton` manager rather than in a screen/VM, so the trace survives navigation between the
routine and cardio screens instead of restarting empty on each open. The axis runs to 110% HRR
rather than 100% so a deep-Z5 effort isn't clipped flat when true max HR beats the age estimate.

## Phase 56 — Recupero ECG via riconnessione

- **Escalation a disconnect+reconnect quando l'ECG non parte**: il 2026-07-08 il Polar H10 si è connesso ma l'ECG non è mai partito — i log mostravano un loop infinito `REQUEST_MEASUREMENT_START → ERROR_ALREADY_IN_STATE → restart` ogni ~2 s per l'intera sessione, con un file ECG finale di 20 byte. Causa: il `dispose()` Rx dello stream non manda uno STOP al sensore, che resta bloccato nello stato "measuring"; ritentare lo stesso START fallisce identico all'infinito. `PolarManager` ora conta i restart consecutivi senza sample (`ecgRestartAttempts`) e, oltre `ECG_MAX_RESTARTS` (3) — o immediatamente su `ALREADY_IN_STATE` — chiama `escalateEcgRecovery()`, che forza `api.disconnectFromDevice()`. Non essendo user-initiated e con sessione attiva, parte il loop di riconnessione involontaria già esistente; al ritorno del feature ONLINE_STREAMING l'ECG riparte pulito. Il contatore si azzera su ogni sample reale e a inizio sessione. Anche i restart del watchdog passano ora per `scheduleEcgRestart` così contano verso l'escalation. Vedi [POLAR.md](POLAR.md#ecg-streaming).

## Phase 57 — VitaFit cloud history import (one-off)

Imported ~2 years of pre-BLE weigh-in history (2024-11-13 → 2026-05-19, 106 days) from the official VitaFit app's cloud account into `gymdata/scale/`, since the app has no export feature and stores history server-side rather than in a local DB (confirmed via a failed `adb backup` and decompiling the APK). The undocumented cloud endpoint (`vitafit-api-eu.66vitafit.com/front/profile/{id}/body_index_per_day/`) was found by MITM-proxying the app's own HTTPS traffic — see [docs/vitafit-cloud-api.md](vitafit-cloud-api.md) for the endpoint, auth, and field mapping. Also deleted 65 days of leftover synthetic debug data (`gymdata/scale/2026/05/28` → `2026/07/31`, all sharing one fixed timestamp) that had been generated during earlier development and were being mistaken for real weigh-ins. This was a manual one-off import, not an in-app feature — no "Import from VitaFit" UI exists or is planned.

## Phase 58 — "Primo dato" badge fixed to use full exercise history

`ActiveRoutineViewModel`'s "primo dato" badge (`isFirstTimeTonnage`) previously judged "is this the first real data point?" by looking only at the immediately previous session of the *current routine* (`previousTonnageByExercise`, built from `getLastSessionForRoutine`). Two bugs followed from that: (1) an exercise with a long, well-tracked history would flash "primo dato" again if the single previous session happened to be `completedEmpty` (skipped/untouched), since a zero/absent tonnage in just that one session looked identical to "never tracked before"; (2) an exercise genuinely new to the user (no history at all) would *not* get the badge unless it happened to already be a key in that previous-session map. Confirmed on-device via `adb shell run-as com.mygymapp` against real `gymdata/history/` files — e.g. "V-grip horizzontal cable row" (`ex-c63b1322`) had 12 sessions of real tonnage from 2026-05-16 to 2026-08-01, then one `completedEmpty: true` session on 2026-08-07, which would have made the next real entry wrongly show "primo dato". Fixed by adding `exercisesWithPriorTonnage`, computed once per routine load from each exercise's *full* history via `WorkoutRepository.getSessionsForExercise()` (already index-backed, cross-routine, excludes `completedEmpty`/zero-tonnage sessions), and redefining `isFirstTime` as "not in that set AND current tonnage > 0" instead of inspecting only the previous session. `previousTonnageByExercise`/`previousBestE1RMByExercise` (used for the vs-last-time % change) are unchanged — they correctly still mean "the immediately previous session," a different question from "has this exercise ever had real data."

## Phase 59 — HR notification resurrected after swiping the app away

`onTaskRemoved()`/`PolarManager.disconnect()` already looked complete — stop the foreground service, dispose the Rx streams, call `api.disconnectFromDevice()` — yet the BPM notification kept reappearing seconds after swiping MyGymApp out of Recents while the H10 was still powered on. Root cause: the Polar BLE SDK has its *own* connection-management layer beneath `PolarManager`, independent of the app-level `userInitiatedDisconnect` flag and `scheduleReconnect()` loop, and by default it auto-reconnects a dropped link. `disconnectFromDevice()` only closes the current link — it doesn't stop the SDK from silently reconnecting to the still-nearby, still-on H10 moments later, which fires `deviceConnected()` again and calls `PolarStreamingService.start()`, resurrecting the foreground notification (and the process) after the user believed the app was closed. Fixed by calling `api.setAutomaticReconnection(false)` right before `disconnectFromDevice()` in `PolarManager.disconnect()` (covers both `onTaskRemoved` and any other user-initiated disconnect), and `api.setAutomaticReconnection(true)` in `connectToDevice()` so a genuine mid-workout drop (screen lock, brief out-of-range) still auto-recovers as before.

## Phase 60 — hrr60s discards non-monotonic "recovery" windows

Server-side analysis of synced sessions (cross-checked against HRR computed independently from raw ECG) found `hrr60s` was systematically underestimated: the peak-detection/HRR logic in `PolarManager` (`detectPeakAndTriggerRecovery()`) took `delta = peakHr − hr_sample_at_or_after(peak+60s)` unconditionally, but users frequently don't actually rest for the full 60s after a qualifying peak — they move on to another exercise, walk around, talk — so the +60s sample often reflects new activity rather than passive recovery, silently pulling many sessions' `hrr60s` near or under the 12 bpm "abnormal" clinical threshold for an otherwise well-trained subject. Fixed by porting the monotonicity/rebound check already implemented server-side in `MyGymApp_server/app/ecg_analysis.py`'s `_hr_recovery_at`: genuine passive recovery is (near-)monotonically decreasing, so before accepting a delta, `isMonotonicRecovery()` walks the session's raw `hrSeries` between the peak and the +60s sample tracking a running minimum, and discards the delta if HR ever rebounds more than `HRR_REBOUND_TOLERANCE_BPM` (8 bpm — looser than the server's 5, since `hrSeries` here is raw/unsmoothed rather than 12s-smoothed) above that minimum. The pure walk itself lives in a new `isMonotonicHrRecovery()` (`data/polar/HrRecoveryMonotonicity.kt`), split out of `PolarManager` specifically so it could be unit-tested without the rest of that class's Hilt/BLE dependencies — this is also the app's first JUnit test (`app/src/test/`, `libs.junit` wired into `app/build.gradle.kts`, none existed before). Discarded-delta counts are tracked per session (`hrrDeltasDiscarded` / `PolarManager.hrrDiscardedCount()`) and logged at session-save time in `ActiveRoutineViewModel`, to gauge how often users don't actually rest during the measurement window. See [docs/POLAR.md#hrr-heart-rate-recovery](POLAR.md#hrr-heart-rate-recovery).

## Phase 61 — Switch exercise mid-session

New "Switch exercise" button (discreet icon, top-right of the exercise screen — not the routine editor) lets the lifter swap a slot's exercise for a same-bodypart, same-type alternative mid-session, without polluting the originally-planned exercise's history when a machine is occupied or motivation is elsewhere. Eligible only while the slot has zero recorded sets (`WorkoutExercise.isSwitchEligible()`, reusing the same per-set-type "has real data" check the ghost-session guard already had — `hasNoRecordedSets()`); once switched, or once a set exists, the slot locks for the rest of the session — no re-switch, not even back to the original. Candidates come from `ExerciseRepository.getSwitchCandidates()`, filtered to the current exercise's bodypart+type and excluding anything already occupying a slot in this session, surfaced through the existing `ExercisePicker` route extended with optional `bodypart`/`type`/`excludeIds` query params (still a no-op for the original RoutineEdit picker). The mutation itself — `WorkoutSession.withExerciseSwitched()` — is a single shared extension function re-verifying eligibility server-side, called from the three exercise ViewModels (`StrengthExerciseViewModel`/`StretchExerciseViewModel`/`SupersetViewModel.switchExercise1`/`2`, superset sides being independent slots), each of which saves the result itself — this is the only code path that ever writes the switch to disk. The routine on disk is never touched — only the session's `WorkoutExercise` gains an optional `substitutedFor: <originalExerciseId>` field (backward-compatible, `null` on old sessions, shipped to the sync server unchanged inside the raw session file) — so the next session from the same routine proposes the original exercise again by default. Progression stays correct because `getSessionsForExercise` (used by the exercise screens' previous-set preview) is already global/cross-routine; the one extension needed was `ActiveRoutineViewModel`'s precomputed per-routine tonnage/1RM maps, which now get an on-demand entry for the switched-in exerciseId. Exercise screens re-navigate to the same route with the new exerciseId (rather than mutating state in place) since their `init{}` loads everything one-shot from `SavedStateHandle`.

**On-device bug found right after the first build**: the switch saved correctly to disk, but returning to the active-routine list still showed the *original* pre-switch exercise as "to do." Root cause — `ActiveRoutineScreen`'s list and the exercise screen are two independent ViewModels, each with its own in-memory copy of the session; `ActiveRoutineViewModel` builds its list once at session start and has no way to learn that a different ViewModel rewrote the file later. Fixed by having the exercise screen also post a `savedStateHandle` result (`"switchedExerciseIds" = "oldId,newId"`, same pattern as the existing `completedExerciseId`) onto the `ActiveRoutine` back-stack entry before re-navigating; a new `ActiveRoutineViewModel.applyExerciseSwitch()` observes it and patches its in-memory list to match the already-saved file (it does not write to disk itself — the exercise screen already did). See [docs/CONVENTIONS.md#switch-exercise](CONVENTIONS.md#switch-exercise) ("Two ViewModels, one session file") for the full account — this is the trap to remember for any future in-session mutation that can originate from a screen other than `ActiveRoutineScreen` itself.

## Phase 62 — Readiness box slimmed down

The readiness card on the Heart Rate screen was showing five numbers, two of which were noise. Removed `LnRMSSD` (an intermediate of the readiness computation — the readiness label above it already *is* its interpretation, and the raw log value means nothing at a glance); it's still computed, persisted and synced, just no longer displayed. Removed the kcal + TRIMP row that sat immediately above the card: on this screen the strap is connected but no workout is running, so they're permanently pinned at ~1 kcal / 0 TRIMP — they remain where they carry information, on the active-routine and session-progress screens. What's left — resting HR, VO2max and steps — went from `bodySmall` to `titleMedium`, and VO2max gained a leading icon (`Icons.Default.Air`) to match the heart and footsteps icons next to the other two.

Steps changed meaning, not just size: the box showed `stepsAvgPerDay`, the checkpoint-diff average whose window depends on what time yesterday's and today's readiness tests happened to be taken (and which silently becomes a multi-day average if a day is skipped). It now shows `stepsPreviousDay` — yesterday's complete local calendar day, midnight to midnight, read straight from Health Connect via a new `HealthConnectStepsReader.previousDayTotal()` independent of the checkpoint. A whole day is comparable day to day; the average was not. The checkpoint fields are still computed, persisted and synced unchanged (the server has the cross-day history to make use of them) — `stepsPreviousDay` is a new nullable field alongside them on `ReadinessEvent`, in the readiness Markdown frontmatter, and in the sync envelope, so the server needs one more nullable column (`steps_previous_day`). Same null-never-zero rule as the other step fields. See [SYNC.md § Daily step average](SYNC.md#daily-step-average).

## Phase 63 — Readiness BPM sparkline, superset PR fix, warmup moved to session bottom

Three independent fixes/additions. **Readiness BPM sparkline**: the 60s "lie still" HRV measurement already tracked `readinessMinHr` but discarded every other sample; `PolarManager` now also collects a per-second `readinessBpmTrace` for the duration of the measurement (cleared in `startReadinessMeasurement()`, carried onto every `_readinessResult` update including the final one) and exposes it as `ReadinessResult.bpmTrace`. `HeartRateScreen` renders it as a small `ReadinessBpmSparkline` (min/max/Δ labels + a plain `Canvas` line, no external chart lib) under the recommendation text, shown only when the trace has ≥2 points — absent when the card is showing a reused earlier-today measurement, since the trace itself is never persisted, only held for the live measurement's lifetime.

**Superset PR badge was computing a different "PR" than the standalone exercise screen.** `StrengthExerciseViewModel.tonnagePr` — the reference definition — is all-time (`getSessionsForExercise(id, Int.MAX_VALUE)`), across every non-excluded session (`!excludeFromTonnage`, so both warmup and daily sets are out), picking the single set with the highest **tonnage** (`reps * weight`). `SupersetViewModel.prSet1/2` instead searched only the last 30 sessions, required an exact category match against *today's* slot (warmup/daily/normal), and ranked by **weight alone** (reps as tiebreaker) — so the same exercise could show two different "PR"s depending on whether it was opened standalone or inside a superset. Replaced with a `tonnagePr()` helper mirroring `StrengthExerciseViewModel`'s exactly (all-time, tonnage-ranked, `!excludeFromTonnage`). PRs are not scoped by rep range — the badge is the best tonnage set ever, independent of what rep range the slot happens to be configured for today; that was already correct and unchanged.

**Warmup moved to the bottom of the session.** `ActiveRoutineViewModel.init` built the session as `warmup → fixed-daily → normal`; reordered to `fixed-daily → normal → warmup` so the screen leads with what actually counts and warmup trails at the end. Display-only — `SessionExerciseCategory`, `excludeFromTonnage` resolution, and the routine's own on-disk `isWarmup` leading-prefix convention (still used by the routine editor) are unaffected. See [CONVENTIONS.md § Injected into every session](CONVENTIONS.md#fixed-daily-exercise-container).

## Phase 64 — Local test gate + first real unit test suite

Repo had zero automated safety net beyond one regression test (hrr60s monotonicity, Phase before this). Added a tracked pre-commit hook (`scripts/git-hooks/pre-commit`, symlinked into `.git/hooks/pre-commit` — that directory isn't tracked, so each clone installs it once) that runs `./gradlew test` and blocks the commit on failure; `git commit --no-verify` skips it for WIP commits. No GitHub Actions — the app has no CI today, this is a local-only gate. Alongside it, five new JUnit test files cover the pure-logic code most at risk of silently corrupting data or drifting from the sync server: `WorkoutParserRoundTripTest` (toMarkdown/fromMarkdown fidelity — this parser *is* the entire session persistence layer, see [STORAGE.md](STORAGE.md)), `WorkoutSessionSwitchExerciseTest` (the "Switch exercise" eligibility guards and mutation from Phase before-before), `HrZoneCalculatorTest` (Karvonen %HRR zone boundaries, must stay in lockstep with the server's `compute_hr_zone_minutes`), `TonnageMathTest` (Epley 1RM estimate, bodyweight-set exclusion), and `BodyCompositionCalculatorTest` (BIA regression clamping). All target JVM-pure classes with no `Context`/BLE/file-I/O dependency — nothing here needed Robolectric or instrumentation. CLAUDE.md now instructs future work to add/update a test under `app/src/test/` in the same change whenever non-trivial logic changes, rather than as a follow-up. See [CONVENTIONS.md § Local test gate](CONVENTIONS.md#local-test-gate).

## Phase 65 — Gitgraph Sunday-column clip fix

`GitgraphView`'s Sunday column (the 7th, rightmost) was rendering visibly narrower than the other 6 — not an illusion, confirmed by pixel-measuring a device screenshot (6 columns at 111px, Sunday at 92px). Root cause: `cellSize` was derived from `BoxWithConstraints.maxWidth` accounting only for the outer `horizontalPadding`, but every row (header, history rows, schedule row) additionally applies its own `padding(horizontal = 3.dp)` — 5.dp for the powerlifting-week row, which draws a 2.dp border before its 3.dp padding. That inset was never subtracted from the `cellSize` formula, so the true per-row width budget was smaller than what 7×cellSize + 6×spacing assumed; the last column absorbed the whole shortfall and got clipped. Fixed by subtracting a shared `rowHorizontalInset = 6.dp` in the formula and normalizing the powerlifting row's border+padding to 3.dp/side (border 2.dp + padding 1.dp) to match the plain rows exactly — `cellSize` is computed once and shared across all rows, so every row must consume the same horizontal space. Also reduced the outer `horizontalPadding` from 12.dp to 0.dp per user request to tighten the grid's side margins.

## Phase 66 — Full-repo review pass

Whole-repo review across code, docs, and README, split across four parallel reviewers scoped to disjoint packages (sync/repository/service/util, polar/scale/model/parser, screen/navigation/steps, components/theme) plus a docs pass, all on `chore/full-repo-review`. No functional/visual behavior changed except genuine bugfixes, which are called out explicitly below; everything else is refactor-for-clarity, comments, or new tests.

**Bugs fixed:**
- `ExerciseEditViewModel.onCleared()`'s fallback save omitted `isBodyweight` (present in the normal `saveNow()` path) — a teardown that hit `onCleared()` instead of an explicit save (process death, non-back navigation clear) silently reset a bodyweight exercise's flag to `false`, since `Exercise.isBodyweight` defaults false. Now matches `saveNow()`.
- `BleScaleManager.maybeSaveWeighIn()` schedules a delayed `disconnect()` 1.5s after a completed weigh-in via a `Handler` not tied to a specific GATT connection; a fast reconnect within that window could tear down the *new* connection instead of the old one. `connectToDevice()` now clears pending callbacks before opening a new connection — same class of bug as the `StopwatchService.ACTION_START` gotcha in CLAUDE.md.
- Three sync workers (`SyncWorker`, `ReadinessSyncWorker`, `ScaleWeighInSyncWorker`) had a dead `if (currentHash != entry.contentHash) currentHash else entry.contentHash` — both branches always evaluated to `currentHash`, so it read as conditional logic that could never actually take its other branch. Observable behavior was already correct (always sends the freshly-computed hash, per SYNC.md); simplified to a direct assignment with a comment explaining why the hash is recomputed right before sending rather than reused from the ledger.
- `EcgRecorder`'s header KDoc said 16 bytes; the real header (magic 8B + sample rate 4B + timestamp 8B) is 20 bytes, matching `EcgAnalyzer.readFile`'s own arithmetic. Comment-only fix.

**Refactors:** deduplicated a `median()` helper shared by `CardioMetricsTrendLoader`/`ScaleTrendLoader`; extracted a `WeeklyDeltaLabel` composable shared by 4 call sites across `CardioMetricsTrendSection`/`ScaleTrendSection`; cleaned up redundant casts in `ScrollPickerInput.applyChange`; `BodyPartAutocomplete`'s O(n²) "is this the last item" divider check replaced with `itemsIndexed`/`lastIndex`. `data/scale/Esf551Protocol.kt` renamed to `VtrumpSenheProtocol.kt` — the filename referenced an unrelated scale protocol (ESF-551) left over from early reverse-engineering notes; the file's actual contents (`VtrumpSenheProtocol`, `DisplayUnit`, `ScaleReading`) were never renamed to match. No other file referenced the old filename.

**Tests added:** `RoutineParserRoundTripTest`, `ExerciseParserRoundTripTest`, `ScaleWeighInParserRoundTripTest` (round-trip + malformed-input handling — these three parsers had no coverage while `WorkoutParser` already did), `VtrumpSenheProtocolTest` (pure BLE frame-parsing: weight/impedance decode, sentinel values, truncated payloads), `ScaleTrendLoaderTest` (median/weekly-delta/ISO-week grouping logic newly extracted for dedup).

**Docs:** README now mentions the VitaFit scale, Health Connect steps, and self-hosted sync features it previously omitted entirely, and links SYNC.md/vitafit-cloud-api.md from the docs table. ARCHITECTURE.md's package-layout tree was missing `data/scale/`, `data/steps/`, `data/util/AppLogger.kt`, and had `ui/service` positioned outside the `ui/` tree — corrected; Repositories table gained `ScaleHistoryRepository`/`StepLedgerRepository`. SYNC.md's intro still called scale-weighin sync "out of scope for v1, not merged" despite the implementation checklist further down the same document showing it fully shipped — fixed. vitafit-cloud-api.md referenced the (long since merged) `feature/vitafit-scale-ble` branch as if still in progress — updated to point at `data/scale/` directly.

**Suspects flagged, not changed** (uncertain enough that a wrong guess would be worse than leaving them): `SessionProgressScreen`'s `FullscreenLoading()` call omits the Scaffold padding argument that every other screen passes explicitly — likely a minor inset bug, but fixing it changes rendered layout, out of bounds for a no-functional-change pass. `ExerciseListViewModel.deleteExercise()`/`RoutineListViewModel.deleteRoutine()` don't emit `DataChangedSignal` the way their edit-screen counterparts do, but neither appears wired to any UI button currently — looks like dead code, not a live bug. `ScaleHistoryRepository.save()` has no mutex unlike every sibling repository — plausible given "one weigh-in/day, last-write-wins, single BLE scale" but not confirmed intentional.

## Phase 67 — Full-repo review, round two (deep audit)

Follow-up to Phase 66 after being asked to go deeper: re-read every diff Phase 66 produced line-by-line (not just its agents' self-reports), ran a dedicated deep audit of `docs/POLAR.md`/`docs/polar/implementation-guide.md` against all of `data/polar/` claim-by-claim, read `CONVENTIONS.md` end-to-end against the code it describes, and covered files no prior pass had touched at all (`AndroidManifest.xml`, `build.gradle.kts`, `data/steps/`, `MainActivity`/`MyGymApp`/`PermissionsRationaleActivity`, every `data/repository/*.kt` in full, `ui/navigation/*.kt` in full, remaining `data/parser/*.kt`/`data/model/*.kt`).

**Bugs found and fixed:**
- **`MarkdownParser.formatValue`/`serializeYaml`** wrote frontmatter strings as `"$value"` with no escaping. Any user-entered text containing a literal `"` (an exercise/routine/bodypart name like `Push-up "diamond" variant`) corrupted the YAML at write time; every repository's `ensureLoaded()` then silently discarded that file on the next read (`catch (_: Exception) { /* Skip malformed */ }`) — the record just vanished with no error. Now escapes `"`→`\"`, `\`→`\\`, flattens `\n`/`\r` to a space. New `MarkdownParserTest` covers the round-trip. This is the most impactful fix of this pass — it affects every exercise/routine/session name field, not an edge case.
- **`Screen.ExercisePicker.createRoute`** built its query string (`bodypart=$bodypart&type=...`) without URL-encoding `bodypart`, which is free text from `BodyPartAutocomplete`. A bodypart name containing `&`/`=`/`#` would corrupt the "Switch exercise" navigation route. Now uses `Uri.encode`.
- **`SyncLedgerRepository`/`EcgSyncLedgerRepository`/`ReadinessLedgerRepository`/`ScaleWeighInLedgerRepository`**'s `markFailed()` wrote `error.take(500)` straight into a quoted YAML scalar; `error` can be a raw HTTP error response body or exception message from OkHttp, which can contain newlines. A `\n` inside the quoted value would corrupt the whole ledger file (not just that one entry) on the next read, silently resetting all sync delivery state. Now strips `\r`/`\n` before writing.
- **`HeartRateBar.kt`** KDoc referenced a `PolarManager.onSetCompleted` method that doesn't exist — recovery tracking is fully automatic via `PolarManager`'s internal `detectPeakAndTriggerRecovery`. Comment-only fix (found by the Polar sub-agent, applied here since it was outside that agent's allowed path scope).
- **`HealthConnectStepsReader.kt`**: a KDoc block documenting `totalSteps` was misplaced directly above `previousDayTotal` instead of above `totalSteps` itself (both functions had adjacent, confusingly-stacked KDoc comments) — likely left behind by an earlier reorder of the two functions. Reordered so each doc sits over the function it describes.
- **`docs/POLAR.md`**: "readinessResult simply stays at its default (never-run)" was wrong — `PolarManager.maybeStartAutoReadinessMeasurement()` explicitly sets it to `NO_BASELINE` past the 10:00 cutoff. "Both reset to 0 on connect" for calories/TRIMP was misleading — the real reset point is session start (`startHrSeriesCapture`), not connect, precisely so a mid-session reconnect doesn't wipe accumulated values; connect-time reset only fires pre-session. Both corrected (found by the dedicated Polar audit sub-agent).
- **`EcgRecorder.kt`**'s class doc said the raw file is "deleted after the session analysis runs" — stale, describing pre-migration behavior; corrected to describe the current send-then-delete-on-server-confirmation flow (found by the same sub-agent).
- **`docs/polar/testing-checklist.md`**: several sections still described the pre-migration local ECG analysis flow (SessionProgressScreen "ECG Analysis" card, SDNN/pNN50/Poincaré/AFib "saved" values, beats/irregularities compared live-vs-post-session) — all of that moved server-side and these fields stay at zero locally now. Corrected to point at what's actually still computed on-device (`cardiacDriftBpmMin`, `restingHr`, `hrr60s`) and note the rest is server-only.

**Confirmed correct, no change** (a non-exhaustive sample of what this pass verified rather than assumed): every physiological formula/constant in `data/polar/` cross-checked against `implementation-guide.md` (Keytel calories, Tanaka HRmax, Banister TRIMP, Uth VO2max, HRV z-score thresholds, HRR constants, arrhythmia/Poincaré formulas) — all matched exactly. `CONVENTIONS.md`'s claims about `DataChangedSignal`, the `onCleared()`/`clearScope` pattern, `StopwatchService` idempotency, Polar disposables, `ScrollPickerInput`'s gesture-cancellation flag, ghost-session cleanup ordering, and `EcgAnalyzer`/`analyzeSessionEcg` being genuinely dead code (confirmed zero callers) — all verified against the actual code, not just re-stated.

**New test:** `MarkdownParserTest` — the shared YAML serializer had no direct test coverage before (only exercised indirectly through the four per-type parser round-trip tests), despite being the single choke point every on-disk write goes through.

**Suspects flagged, not changed** (same reasoning as Phase 66 — not confident enough to guess): `StepLedgerRepository.recordReadingAndComputeAverage()` advances its checkpoint *before* confirming the Health Connect fetch succeeded — a transient Health Connect failure on a given day permanently loses that day from the multi-day average (though `previousDayTotal`, the value actually shown in the UI, queries Health Connect independently and isn't affected). `EcgSyncLedgerRepository.expireStale()` silently never expires an entry with a missing/unparseable `enqueuedAt` — unreachable via the app's own `enqueue()` (which always sets it), but a theoretical trap for a future schema change.

## Phase 68 — App-start speedup: unify boot maintenance, load gitgraph concurrently

Investigated slow app-open (~2.6s cold-start reported, measured on-device via `adb`). Root cause: `MainViewModel.init` ran `migrateOldSessionFiles()` → `pruneOldSessions()` → `cleanupGhostSessions()` → `cleanupOrphanEcgFiles()` → `loadGitgraphInternal()` fully sequentially, and the three cleanup steps each independently walked all of `history/` re-parsing every session's YAML from scratch — three full parses of the same files on every single app open, before the gitgraph (which needs its own set of parses) even started. On the test device (58 session files) this measured ~9s combined, dwarfing the perceived "1-2s".

`WorkoutRepository.pruneOldSessions`/`cleanupGhostSessions`/`cleanupOrphanEcgFiles` are now one `runMaintenance()` that walks `history/` once, parsing each `.md` file a single time to evaluate all three criteria (prune-by-age, ghost-session, still-valid-for-orphan-ECG-check). `MainViewModel` now launches the gitgraph load as a concurrent child job instead of awaiting maintenance first, so the screen populates as soon as the slower of the two finishes rather than after their sum. `runMaintenance` also throttles itself to at most once per 12h via an mtime sentinel (`history/_idx/.last_maintenance`) — cheap insurance for anyone opening the app more than once in a sitting, though for the reported once-a-day usage pattern the real win is the merged walk + concurrency, not the throttle.

Measured with `adb shell am start -W` (system-reported `TotalTime`, cold start, `force-stop` between runs): **2.6s → ~1.3s**, consistent across repeated runs. Along the way, instrumented timing revealed a secondary, unrelated finding: `withContext(Dispatchers.IO)` blocks can sit for ~1s waiting to resume back onto the main thread during the very first Compose frame after cold start (main thread contention from initial layout/inflation), independent of how fast the IO work itself completes — noted here in case a future pass wants to chase that further (e.g. moving more first-frame work off the critical path), but out of scope for this pass.

## Phase 69 — Active-routine superset row: type color instead of purple, no "SUPERSET" label

In-session superset rows used `colorScheme.primary` (purple/pink) for their border, divider, and a "SUPERSET" caption — visually disconnected from the single-exercise rows, which are bordered by their exercise-type accent color (FORZA orange / STRETCH blue / CARDIO red). Dropped the caption (the stacked pair + divider already reads as a superset) and replaced the purple with the type accent. Since a superset *can* mix types (`toggleSuperset` only blocks crossing the warmup line, not mixing types), the border is a vertical gradient from ex1's accent (top) to ex2's (bottom) — a same-type pair just reads as one solid color; the internal divider mirrors it horizontally at 30% alpha. Both border colors drop to 0.4 alpha once the pair is complete, matching `ExerciseRow`. Also aligned the in-box text styles to `ExerciseRow` (name `titleMedium`, "primo dato" `titleSmall`) and bumped the card's vertical padding/spacing so the row grows taller rather than shrinking the text.

## Phase 70 — Polar drop diagnostics + end-of-routine connection box

After a session that dropped the H10 seven times in ~13 minutes (then ran clean), `app.log` confirmed the app's own reconnect logic was fine — every drop was `BleDisconnected` (radio-level) and auto-recovered in 2–3s — but gave no hint *why* the link died. Added two things. (1) `deviceDisconnected` now logs `rssi=` (strap's last-known value, often stale on Android) and `hrGap=` (ms since the last HR sample) — a gap that grew before the drop points at range/fade, a fresh sample right up to it points at 2.4 GHz interference or a lost electrode contact. (2) Each involuntary mid-session drop is accumulated into `PolarManager.disconnectStats` as a `List<SessionDrop>` (time into session, RSSI, HR-sample gap, per-drop cause guess), and when the end-of-routine screen is opened right after finishing (`justCompleted`) it reads that live off the `@Singleton` and renders `PolarConnectionBox` directly under the server-sync box — a header line (count + whether all auto-recovered) then **one row per drop**: `m:ss · RSSI · gap · → cause`. Hidden entirely when there were no drops.

## Phase 71 — H10 battery: active-hours tracker with auto-detected cell swaps + measured-lifespan average

The H10's reported percentage is a voltage proxy that sits near 3V for most of a CR2025's life, so it carries no usable "hours remaining". Added a wear indicator that actually moves: the current cell's **active hours** shown against **the average measured lifespan of a cell in this setup** — `47 / 68 h` — next to the `%` on the connection screen. Until the first swap there's no average yet, so it falls back to `· 47 h attive · 11 gg`. New `BatteryLifeRepository` persists `gymdata/_sync/battery_life.yml` (local-only) and exposes `PolarManager.batteryLife: StateFlow<BatteryLifeState?>`, updated on every `batteryLevelReceived`. Install/swap detection is automatic — no button: a reported rise of **more than 5 percentage points** versus the previous reading can only be a fresh cell (a coin cell under load never recovers >5%), so it appends the outgoing cell's active-seconds to the lifespan history (guarded by a 2 h `MIN_CREDIBLE_LIFE_SEC` floor so a pull-and-reinsert doesn't skew the mean), then resets the install date to today and the counter to zero — covering the "70% one day, 100% the next" case the user hit after a real swap. `avgLifeHours` is the mean of that history. Active time is the wall-clock gap between consecutive battery callbacks, skipped when it exceeds 30 min (strap off in between): under-counts by at most one interval per session, never over-counts. All logic is the pure `BatteryLifeRepository.reduce`, unit-tested in `BatteryLifeReducerTest`.

## Phase 72 — Home 5th row: show every current-week workout, not just today's

The schedule row (5th row of `GitgraphView`) only upgraded the **today** cell from a static "to do" cell to a real history cell once a session existed. Any other day of the current week — e.g. yesterday, if viewing today — stayed a plain schedule cell, so a workout done yesterday simply didn't appear anywhere on the home screen (the 4 history rows cover only the 4 weeks *before* the current one). `MainViewModel` already fetched `currentWeekSessions` for the whole week but then used only `todaySession`. Fixed by grouping current-week sessions by date (most-recent completed per day), computing each day's outcome via a new extracted `computeDayCell()` helper (same status/tonnage-%/cardio-minutes logic the history loop and today cell use — now shared instead of triplicated), and carrying it on new `ScheduleCell.session*` fields. `GitgraphView` renders a `HistoryCell` for any past current-week day whose `sessionStatus != NONE`, tappable through to its progress view. The today cell is untouched — still driven by the dedicated `today*` params, so `MainViewModel` leaves its `session*` fields empty.

## Phase 73 — Bodyweight exercises: estimated load feeds tonnage / PR / e1RM

Bodyweight exercises (`isBodyweight`) recorded `weight: 0.0`, so `reps * weight` was always 0 — progress in reps (10 → 15 pull-ups) was invisible to `totalTonnage`, `tonnageChangePct`, `isFirstTimeTonnage`, `TonnagePr`, `bestEstimated1RM`, and every session-over-session chart. Fixed by giving each bodyweight `Exercise` a mandatory `bwLoadPercent` ∈ {25, 50, 75, 100} (squat 100, plank 75, reverse sit-up 50, tibialis raise 25), chosen via four `FilterChip`s in the exercise editor; legacy files migrate to 75 in `ExerciseParser`. At exercise-completion time (`StrengthExerciseViewModel.buildStrengthSets`, `SupersetViewModel.buildUpdatedSession`) each bodyweight set's `weight` is **materialized** to `bwLoadPercent%` of the lifter's body weight — from `ScaleHistoryRepository.getLatestWeightOnOrBefore(sessionDate)`, a new month-stepping look-up over the `scale/` tree — rounded to 0.5 kg by the pure, unit-tested `materializeBodyweightWeight` in `TonnageMath.kt`. The raw `bwLoadPercent` + `bwBaseWeightKg` ride along on the set for audit. Because `weight` is now a real positive number, every existing `reps * weight` reader (phone and server) works unchanged — no read-site special-casing, and historical sessions freeze the body weight that was current then. The "Kg" column and per-set weight picker are hidden for bodyweight exercises in both session screens. Sync: the two new set fields flow in the raw session bytes with no sync-layer change; server needs no math change (documented in [SYNC.md](SYNC.md)). Convention written up in [CONVENTIONS.md](CONVENTIONS.md#bodyweight-load-materialize-weight-at-completion-never-at-read-time).

## Phase 74 — Untouched-exercise guard: touch one value → save all shown numbers; touch none → not completed

Fixed-load warmup/daily exercises (PRI 90/90 hip lift, Bulgarian glute, Copenhagen adduction, …) were systematically losing their loads. The old untouched-exercise guard, on a "Complete" tap with no picker touched, saved `completed = true, completedEmpty = true, sets = emptyList()` — so the exercise's numbers were discarded. Next session, `StrengthExerciseViewModel.init`'s prefill walk-back (`firstOrNull { sets.any { reps > 0 || weight > 0 } }`) then found only zeros/empties in that exercise's history and started the UI at `0 × 0`, which got re-saved as empty again: a self-perpetuating loop. Confirmed on-device against real `gymdata/history/` — e.g. `ex-a377364a` had no session with real reps/weight in 3 months. New rule, applied uniformly across `StrengthExerciseViewModel`, `SupersetViewModel` (per side), `StretchExerciseViewModel`:

- **Touch ≥1 value** (any reps/weight picker; for stretch, toggle ≥1 set `done`) → `completed = true`, and **every** set is persisted as shown — touched and still-grey pre-filled alike. This is the point: the lifter shouldn't have to re-key "8 × 30" every session.
- **Touch nothing** → `completed = false`, but `sets` keeps the on-screen pre-fill (not `emptyList()`) so re-entry shows the same numbers. The screen still navigates back.

Because a non-completed exercise can now carry pre-filled `sets`, "empty sets" is no longer a proxy for "not performed", so two checks were re-keyed onto `completed`:
- `WorkoutExercise.isUntouched()` is now just `!completed` (was `!completed && sets.isEmpty()`). `MainViewModel.computeCommonTonnage()` already excludes `isUntouched()` exercises from the day-vs-day comparison, so a non-completed daily's pre-fill can't skew the gitgraph.
- Ghost-session detection (`ActiveRoutineViewModel.onCleared()` + `WorkoutRepository.isGhostSession()`) is now `completedAt.isBlank() && exercises.none { it.completed }` — set data no longer keeps an unfinalized session alive. This also fixes abandoned sessions that merely opened a pre-filling daily surviving boot cleanup (see the 9 stray debug sessions cleaned from disk the same day).

Convention rewritten at [CONVENTIONS.md](CONVENTIONS.md#untouched-exercise-guard-completing-without-changing-anything); `WorkoutSessionSwitchExerciseTest` updated for the new `isUntouched()` semantics.

Also in this phase: the completion save in all three exercise VMs (`completeExercise`/`completeSuperset`) moved off `viewModelScope` onto `clearScope`, with `onCleared()` now `join()`ing the tracked `completionJob` before `clearScope.cancel()`. Navigation tears the VM down right after the tap, so a `viewModelScope` save could be cancelled mid-write by a process death (observed repeatedly during this day's debugging — ~15 app restarts in an hour), losing `completed = true` with `onCleared()` unable to recover it. `clearScope` survives teardown; the join guarantees the write lands. See [CONVENTIONS.md](CONVENTIONS.md#completionsaved-pattern).

## Phase 75 — End-of-session sync box: starts on "checking", confirms receipt with size + time

The "invio al server" reassurance card on the session-completed screen used to open on **"Sync col server disattivata"** (`SessionSyncStatus` defaulted to `SYNC_OFF`) and only correct itself a beat later once `refreshSyncStatus()` had run — a misleading flash when sync is in fact configured and on. New initial state `SessionSyncStatus.CHECKING` ("Verifica connessione al server…", spinner), and the box resolves to `SYNC_OFF` only after we've actually confirmed sync is off. `pollSyncStatusWhilePending()` keeps polling through `CHECKING` too.

On success the box now shows what moved: **"4.2 KB in 0.4 s"** plus the server's own confirmation word — `stored` ("Il server ha confermato la ricezione e l'ha salvata") vs `duplicate` ("Il server aveva già questa versione"). This is a real receipt: the ledger only flips to `SENT` on a confirmed 2xx (`docs/SYNC.md` §1.3 step 4), and the server echoes what it did. `SyncResult.Success` carries `bytesSent` (raw file size) + `durationMs` (wall time of the POST, measured in `SyncApi.postSession`); `SyncLedgerRepository.markSent` persists those plus `serverStatus` into three new optional fields on `SyncLedgerEntry` (`bytesSent` / `durationMs` / `serverStatus`, all default 0/""). Only the session ledger writes them — the readiness/scale ledgers reuse the type and ignore them. `SessionProgressViewModel` reads them back off the ledger entry and hands them to `SyncStatusBox`. Ledger schema + debug preview (`SessionSummaryPreviewScreen`) updated.

## Phase 76 — Readiness card + Polar-connection box: shared, restyled, and in the debug preview

The HRV-readiness UI on `HeartRateScreen` and the Polar strap facts below it were both inline in `ConnectedContent`; they're now `ReadinessCard` and `PolarDeviceBox` in a new `ReadinessCard.kt`, reused verbatim by the end-of-routine debug preview (`SessionSummaryPreviewScreen`).

Readiness card changes: the "Readiness" title is gone; the verdict word (`NORMAL`/`PEAK`/…) jumps from `headlineSmall` to `displaySmall`; the recommendation line shows only its actionable half — `recommendation.substringAfterLast(". ")`, so "HRV within normal range. Proceed with planned workout." renders as just **"Proceed with planned workout."** — centred. Steps lose the "passi" suffix and get a `.`-grouped thousands separator (`12.641`). Under the resting-HR figure sits the raw mean of `bpmTrace` ("media 61 bpm"). The BPM sparkline's header is one centred line: `[57, 70] bpm   Δ 13 bpm`.

The live-HR block at the top of the connected view drops its "BPM" caption under the big number.

`PolarDeviceBox` replaces the old one-line "Connected to … · 88% · 62 / 410 h" row with a boxed `SpaceEvenly` row of three large items: **MAC address · battery % with icon · active-hours / estimated-lifespan h**. The standalone low-battery warning row underneath is unchanged.

## Phase 77 — All-time PR: match the slot's own context (daily / warmup / normal)

The "PR: reps × weight" badge on the strength and superset screens was computed over `!excludeFromTonnage` sets only. That flag is true for **both** warmup sets and fixed-daily entries, so a fixed-daily slot's PR was drawn from the *routine* history of the same exercise (which excludes daily executions entirely) — and vice versa. In practice: Bulgarian glute run daily at 13×16 for weeks, but its badge read "11 × 16" (the best set from its unrelated routine appearances), i.e. a PR sitting *below* the grey pre-filled numbers from last session. Reported from a screenshot.

Fix: introduced `WorkoutExercise.slotContext` (`SlotContext.NORMAL` / `WARMUP` / `DAILY`) — the same three-way split the "previous" pre-fill already did inline with `ex.isDaily == isDaily && (ex.excludeFromTonnage && !ex.isDaily) == isWarmup`. Both `StrengthExerciseViewModel` and `SupersetViewModel` now filter *both* the previous-session lookup and the all-time-PR scan by `ex.slotContext == <current slot's context>`, so a daily slot's PR comes only from prior daily executions, a normal slot's only from normal ones, a warmup slot's only from warmup ones. Four hand-rolled copies of the predicate collapsed to one. New `WorkoutExerciseSlotContextTest` covers the classification (notably: `isDaily` wins over `excludeFromTonnage`).

## Future enhancements

- Export / import `gymdata/` as a zip
- R8 / ProGuard minify for release with `-keep` rules for Polar SDK + RxJava
- Consolidate `cache/images/` and `image_cache/` under Coil
