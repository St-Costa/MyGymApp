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

## Phase 41 — Daily step average, piggybacked on readiness

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

## Future enhancements

- Export / import `gymdata/` as a zip
- R8 / ProGuard minify for release with `-keep` rules for Polar SDK + RxJava
- Consolidate `cache/images/` and `image_cache/` under Coil
