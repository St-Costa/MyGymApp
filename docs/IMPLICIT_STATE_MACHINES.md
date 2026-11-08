# Implicit state machines — inventory

Read-only audit (2026-11-08). No behavior changed. Purpose: list every place where
the app behaves like a finite state machine but the states/transitions are not
reified in a single type — so a later pass can prioritize and make them explicit
one by one.

Rule of thumb used here: a cluster counts as an *implicit* state machine when
(1) behavior depends on history/order, not just current values, (2) there are
invariants across fields ("if A then B"), (3) an illegal combination costs data /
radio / UI lies, or (4) there are multiple writers (VM, worker, BLE callback,
`onCleared`). Plain independent attributes (`isFavorite`, `bodypart`, names) are
**not** listed.

Priority assigned 2026-11-08 — see § Priority below. Code untouched.

## Priority

Ranked by (a) cost of an illegal transition (data loss > radio/battery burn >
stuck session > UI lie), × (b) likelihood (writers count, crash windows, frequency).
Dependencies noted — a later item sometimes must follow an earlier one.

### Tier S — data at risk, fix first

| Rank | ID | Why now |
|---|---|---|
| 1 | ISM-01 session envelope | `sessionFinalized` RAM-only vs `completedAt` on disk; two durable crash windows (finalized-not-registered, RPE-saved-not-enqueued); ghost sweep re-derives from disk. Every other session fix builds on this. |
| 2 | ISM-05 sync ledgers | All transitions unchecked on source state; `resetAllToPending` resurrects EXPIRED; EXPIRED is irreversible data-loss-by-design; 5 pipelines drifted apart (only ECG expires, only repo deletes). |
| 3 | ISM-02 exercise slot | `completed/completedEmpty/substitutedFor/sets` decide tonnage/history/PR/sidecars; one wrong combo forges or deletes training data. Shares files/tests with ISM-01 — do together. |

### Tier A — recording reliability (BLE/scale/cardio)

| Rank | ID | Why |
|---|---|---|
| 4 | ISM-07 BLE link/reconnect | 6 correlated flags + dual SDK/app reconnect layers; stale `userInitiatedDisconnect`, 3 meanings of CONNECTING, 5-min silent give-up. Root cause of most "strap vanished" reports. |
| 5 | ISM-10 ECG streaming/recording | Truncated-file resume (new header, same id), `active/pending` divergence, watchdog blind spots. Corrupt ECG = corrupt server analysis. Depends on ISM-07 vocabulary. |
| 6 | ISM-11 in-session capture | HRR/recovery runs on every sample incl. out-of-session; stale `hrSeries` after timeout; rebound-tolerance fork vs server. Wrong numbers silently persisted to session. |
| 7 | ISM-09 HR streaming/standby/watchdog | Guard-by-early-return only; display-vs-restart split (5s vs 15s) lives in one runnable. Do with ISM-07. |
| 8 | ISM-29 maintenance/sidecars | Ghost sweep vs throttled maintenance vs completed-only rebuild; prune of a completed session without rebuild = phantom PRs. Do right after ISM-01. |
| 9 | ISM-19 cardio block | Only VM persisting an *open* block on exit; kill-recovery ghost-close counts as performed work; RUNNING-without-target legal. Direct tonnage/history impact. |
| 10 | ISM-21 scale connection | CONNECTING has no timeout and blocks new scans; silent `unitCode`/`accuracy` misparse accepted as valid weigh-ins. |

### Tier B — stuck UI / lost edits

| Rank | ID | Why |
|---|---|---|
| 11 | ISM-03 exercise save protocol | `finished` set before launch; `switchExercise*` still on `viewModelScope` (teardown cancels it); cross-VM last-writer-wins vs `completionMutex` (intra-VM only). |
| 12 | ISM-27 routine editor | Transient `warmupCount` mid-segment marks too many warmup on save; drafts cover name/day only; silent blank-name drop. |
| 13 | ISM-23 Home two-phase load | `force`-cancel aborts with no emit and no error surface; today split across `today*` vs `scheduleCells`. Visible flicker/stale risk, no data risk. |
| 14 | ISM-04 switch-result delivery | Queue-string protocol in `savedStateHandle`; consume-then-remove ordering; dual switch+complete in one recomposition (already mutexed, still implicit). |
| 15 | ISM-14 service notification mirror | 4 fields updated via 5 intents; can lag `PolarManager`; impossible combos excluded by ordering only. Do with ISM-07. |
| 16 | ISM-28 exercise editor | Same draft/silent-drop shape as ISM-27, smaller blast radius; no `claimSave` lock. |
| 17 | ISM-15 stopwatch mode | Unknown intent freezes service; `EXTRA<=0` silently becomes count-up; STOP doesn't cancel in-flight buzz. |
| 18 | ISM-26 Options async ops | 6 copies of running/progress/error/result; resync denominator fixed at start; restore fallback paths; single-shot verify can disagree with sync box. |
| 19 | ISM-20 strength touch/modify | Purple-≠-performed invariant lives in comments; `allSetsFilled` init-false; anchor-clearing rules. Local to one VM, well-tested. |

### Tier C — small / already-pure / bounded

| Rank | ID | Why last |
|---|---|---|
| 20 | ISM-12 readiness | Mostly linear 60s flow; residual transients (MEASURING+not-measuring, pending sleepQuality) are self-healing by next connect. Pure kernels already extracted. |
| 21 | ISM-25 sessionprogress verify box | Same quad as ISM-26, single screen, single-shot by design. Fold into ISM-26. |
| 22 | ISM-17/18 stretch/superset display timers | Pure `tick()` core exists; remainder is VM↔service lockstep owned by Screens. |
| 23 | ISM-13 battery tracking | Caps + fire-and-forget drops bound the damage; worst case is a lost span, not lost workouts. |
| 24 | ISM-06 sync gate | 3-bit boolean function, one pure `shouldSyncRun`; only the force-path wiring is implicit. |
| 25 | ISM-24 seed gate | Two independent flows, re-entrancy already guarded; cosmetic. |
| 26 | ISM-08 linkStatus projection, ISM-16 buzz, ISM-22 protocol parse | Already pure/total or near — reference patterns, not targets. |

Suggested resolution order (dependency-aware): (01+02) → 05 → 29 → (07+09+14) → 10 → 11 → 19 → 21 → 03+04 → 27+28 → 23 → 15 → 26(+25) → 20 → Tier C as encountered.

## Index

| ID | Area | State is spread across | Files |
|---|---|---|---|
| ISM-01 | Session envelope (ghost → live → finalized → registered) — disk side explicit via `SessionLifecycle` (`data/model/SessionLifecycle.kt`); VM flow flags (`sessionFinalized`, `sessionRegistered`, `showRpePrompt`) still implicit | `completedAt`, `sessionRpe/Load`, `sessionFinalized` (RAM-only), `sessionRegistered`, `showRpePrompt` | `data/model/WorkoutSession.kt`, `ui/screen/activeroutine/ActiveRoutineViewModel.kt`, `data/repository/WorkoutRepository.kt` |
| ISM-02 | Per-exercise slot (open / performed / skipped / switched) — explicit via `SlotLifecycle` (`data/model/SessionLifecycle.kt`); all predicates/guards delegate to it | `completed`, `completedEmpty`, `substitutedFor`, `sets`, `startedAt/completedAt`, derived `isUntouched/hasNoRecordedSets/isSwitchEligible/slotContext` | `data/model/WorkoutSession.kt:46-192` |
| ISM-03 | Exercise-screen save protocol — teardown decision exhaustive via `ExerciseSaveState` (`Idle`/`Completing(job)`/`Switched(pendingCompletion)`) in `ExerciseSessionViewModel`; switch carries the in-flight write | `finished`, `completionJob`, `completionSaved`, `completionEvent`, `clearScope`, `mergeExitSets` | `ui/screen/exercise/ExerciseSessionViewModel.kt` |
| ISM-04 | Switch-result delivery (cross-VM queue) — keys centralized in `NavResultKeys`, all three codecs (`append/parseSwitchResults`, `encode/parseSupersetResult`) + per-side picker keys in `ui/navigation/NavResults.kt`; producers/consumers rewired, no raw strings | `switchedExerciseIds` queue string, `completedExerciseId`, `completedSupersetIds` in `savedStateHandle` | `ui/navigation/AppNavigation.kt`, `ui/navigation/NavResults.kt`, `ui/navigation/SwitchResultQueueTest.kt` |
| ISM-05 | Sync ledgers ×5 + `SessionSyncStatus` projection — transitions explicit via `SyncStatus.canTransitionTo` (guards in every `mark*`, `resetAllToPending` preserves EXPIRED); `enqueue`/`requeueIfChanged`/`markRestored`/`markDeleted` stay sanctioned resets | `(status, op, contentHash, attempts)` per pipeline; `SessionSyncStatus` | `data/sync/SyncLedgerEntry.kt`, `data/sync/*LedgerRepository.kt`, `data/sync/SyncWorker.kt`, `ui/screen/sessionprogress/SessionProgressViewModel.kt:41` |
| ISM-06 | Sync scheduling gate | `configured × enabled × force`, periodic vs expedited workers | `data/sync/SyncGate.kt`, `data/sync/SyncWorker.kt:62-165` |
| ISM-07 | Polar BLE link + reconnect — tick/anchor decisions explicit via `reconnectTick`/`anchorReconnectStart` (`data/polar/LinkRecovery.kt`); SDK/handler wiring untouched | `connectionState × connectedDeviceId × userInitiatedDisconnect × reconnectStartAtMs × SDK autoReconnect × streamingFeatureReady` | `data/polar/PolarManager.kt:393-846` |
| ISM-08 | `linkStatus` UI projection | `connectionState × receivingData × noSignalGrace × hrStandby` → `PolarLinkStatus` (pure `linkStatusOf`) | `data/polar/PolarLinkStatus.kt`, `data/polar/PolarManager.kt:238-283` |
| ISM-09 | HR streaming / standby / watchdog — decisions explicit via `hrWatchdogDecision`, `shouldParkForStandby`, `shouldEnsureStreaming` (`data/polar/LinkRecovery.kt`); disposables/handlers untouched | `hrDisposable × hrStandby × receivingData × lastHrSampleAtMs × restart/watchdog handlers` | `data/polar/PolarManager.kt:848-1020,1465-1515` |
| ISM-10 | ECG streaming / recording / live analysis — watchdog decision explicit via `ecgWatchdogDecision` (`data/polar/LinkRecovery.kt`, routes through the escalation cap as before) | `active/pendingEcgSessionId × ecgDisposable × streamingFeatureReady × ecgRestartAttempts × recorder channel` | `data/polar/PolarManager.kt:1272-1458`, `data/polar/EcgRecorder.kt`, `data/polar/LiveEcgAnalyzer.kt`, `data/polar/HrZoneTracker.kt` |
| ISM-11 | In-session capture (HR series / HRR / recovery) — trigger/settlement/exit/window rules explicit via `peakTrigger`, `settleHrrPeak`, `hasRecovered`, `clampSampleMinutes` (`data/polar/HrrDecisions.kt`); queue mechanics + thresholds wiring stay in `PolarManager` | `hrSeriesActive × hrSeries × sessionDrops × isRecovering × pendingHrrPeaks × hrrDeltas` | `data/polar/PolarManager.kt:288-368,1153-1261,1027-1128` |
| ISM-12 | Readiness 60s measurement | `readinessMeasuring × ReadinessResult.readiness × pendingSleepQuality × persisted file` | `data/polar/PolarManager.kt:1591-1818`, `data/polar/ReadinessMetrics.kt`, `data/polar/ReadinessRepository.kt` |
| ISM-13 | H10 battery-life tracking | `batteryLevel/Low × batterySpanAnchor × heartbeat handler × BatteryLifeState` | `data/polar/PolarManager.kt:1526-1574`, `data/polar/BatteryLifeRepository.kt` |
| ISM-14 | `PolarStreamingService` notification mirror — explicit via `ServiceLinkState` reducer + `serviceNotificationTitle` (`ui/service/ServiceLinkState.kt`); intents/`isRunning` untouched | `reconnecting × standby × currentHr` + 5 intent actions, priority in `buildNotification` | `ui/service/PolarStreamingService.kt:194-300` |
| ISM-15 | `StopwatchService` count-up vs countdown-loop — mode explicit via `StopwatchMode`/`stopwatchModeFor`, tick update explicit via `advanceCountdown` (`ui/service/StopwatchState.kt`); unknown-intent/STOP quirks preserved + documented | `countdownDuration: Int?` (null = count-up) × `loopStartElapsed × loopCount × lastVibrationAt` | `ui/service/StopwatchService.kt` |
| ISM-16 | `StopwatchBuzzSchedule` buzz arbitration | `(loopElapsed, duration, lastPeriodicAt)` → `NONE / PERIODIC / LOOP_END` | `ui/service/StopwatchBuzz.kt` |
| ISM-17 | Stretch display timer | `isStopwatchRunning × timerJob × remainingSeconds × loopCount × countdownDuration` (VM) + pure `StretchCountdown.tick()` | `ui/screen/stretchexercise/StretchCountdown.kt`, `ui/screen/stretchexercise/StretchExerciseViewModel.kt:29-44,135-170` |
| ISM-18 | Superset display timer | same shape as ISM-17, duration = first STRETCH set; `toggleSetDone` is two-way (unlike stretch one-way) | `ui/screen/superset/SupersetCountdown.kt`, `ui/screen/superset/SupersetViewModel.kt:97-113,415-454,451-454` |
| ISM-19 | Cardio block lifecycle — button/completion/recovery/exit/average rules explicit via `cardioButtonState`, `cardioCompletion`, `closeOpenCardioBlocks`, `exitSaveBlocks`, `cardioBlockAverage` (`ui/screen/cardioexercise/CardioBlockState.kt`); jobs/timers stay in the VM | `isBlockRunning × completedBlocks × configuredDuration × remainingSeconds (goes negative) × timerJob/hrCollectJob`, projected to `CardioButtonState{IDLE,RUNNING,DONE}` | `ui/screen/cardioexercise/CardioExerciseViewModel.kt:35-49,170-345`, `ui/screen/cardioexercise/CardioExerciseScreen.kt` |
| ISM-20 | Strength per-set touch/modify/anchor | `reps/weight × previous × repsModified/weightModified × repsTouched/weightTouched × tonnageAnchor`, `allSetsFilled`, `switchEligible` | `ui/screen/strengthexercise/StrengthExerciseViewModel.kt` |
| ISM-21 | Scale BLE connection + save pipeline — transitions explicit via `reduceScaleLink`, frame merge via `ScaleReading.mergedWith`, save guard via `shouldSaveWeighIn` (`data/scale/ScaleLinkState.kt`); reconnect-while-connected now refused whole-operation (was a gatt leak); CONNECTING-without-timeout documented as known gap | `ScaleConnectionState × gatt × scanCallback × autoConnectAttempted × weightSamples × lastReading/lastError × scanTimeout/autoDisconnect` | `data/scale/BleScaleManager.kt` |
| ISM-22 | Scale protocol parse + averaging | `parse()` null-vs-reading, `DisplayUnit.fromCode` else-branch, `weightStable` per frame kind, tail-average window | `data/scale/VtrumpSenheProtocol.kt`, `data/scale/ScaleWeightAverager.kt` |
| ISM-23 | Home two-phase load — coalescing/emit/cell/open rules explicit via `refreshCommand`, `shouldEmitScaffold`, `scheduleCellSession`, `openRoutineForColumn` (top-level in `HomeStateLoader.kt`) | `job × everCompleted × force`, phase-1 scaffold vs phase-2 full swap | `ui/screen/main/HomeStateLoader.kt`, `ui/screen/main/MainViewModel.kt:31-68` |
| ISM-24 | Debug-seed gate on Home | `isSeedingData` (VM `_isSeedingData` flow) vs loader state | `ui/screen/main/MainViewModel.kt:66-67,88-144` |
| ISM-25 | SessionProgress backup-verify box | `backupVerifyRunning × progress × error × report`, `justCompleted` one-shot | `ui/screen/sessionprogress/SessionProgressViewModel.kt:86-159` |
| ISM-26 | Options async ops — resync progress, ECG-debug gate and self-test summary explicit via `resyncProgress`, `ecgDebugGate`, `polarSelfTestLiveSummary` (`ui/screen/options/OptionsOps.kt`); verify-box terminal rule shared via `backupVerifyOutcomeBox` (`data/sync/BackupVerifier.kt`, also closes ISM-25) | each: `*Running/isTesting × progress/secondsLeft × error/result × searching` | `ui/screen/options/OptionsViewModel.kt:30-99,249-900` (resync 332-441, restore 456-623, verify 626-657, ecg-debug 677-724, step-debug 746-771, self-test 807+) |
| ISM-27 | Routine editor (links / warmup line / save) — toggle/remove/line/snap/build rules explicit as pure top-level `toggleSupersetLink`, `removeExerciseAt`, `moveWarmupLineDown/Up`, `snapWarmupToBoundary`, `buildRoutine`; VM only assigns state | `supersetWithNext` chains, `warmupCount` vs segment boundary, `isNew/isFixedDaily/deleted`, `saveClaimed/saveLock`, name/day-only drafts | `ui/screen/routineedit/RoutineEditViewModel.kt` |
| ISM-28 | Exercise editor (type/load/drafts/autosave) — load-mode exclusion, rep-range coupling, mapping and save-guard explicit as pure `applyBodyweightChange`, `applyAssistedChange`, `applyRepMin/MaxChange`, `isSavable`, `toExercise`; handlers only assign state | `isNew/deleted`, `isBodyweight XOR isAssisted`, `type=CARDIO` forces, `savedExplicitly` vs `onCleared` autosave, 4-text-field-only drafts | `ui/screen/exerciseedit/ExerciseEditViewModel.kt` |
| ISM-29 | Repo maintenance + sidecar invalidation — per-file verdict explicit via `MaintenanceAction`/`decideMaintenanceAction` (`data/repository/MaintenanceAction.kt`); sidecar rule falls out of `SessionLifecycle` | unthrottled ghost sweep vs 12h-throttled `runMaintenance`, stats rebuild only for completed sessions, sentinel file, mtime re-check | `data/repository/WorkoutRepository.kt:773-962` |

Explicit already (not implicit — listed so nobody re-audits them): `StretchCountdown`
(pure state + `tick()`), `linkStatusOf()` (pure 4→5 projection), `StopwatchBuzz`
priority (pure), `ExerciseSessionViewModel` (centralized save protocol extracted from
4 copies), `SwitchResultQueueTest` queue helpers, `Screen` sealed routes,
`BackupVerifyOutcome` / `SyncResult` / `RestoreResult` / `RepoBulkOutcome` sealed
results, `HrZone` / `LoadMode` / `ExerciseType` / `SlotContext` / `DayCellStatus`
classifiers.

---

## ISM-01 — Session envelope

**States (reconstructed, never typed):** ghost (blank `completedAt`) → live → all-exercises-closed → finalized (`completedAt` stamped, charts computed) → RPE-answered → registered (saved + sync-enqueued + event fired). Ghost is terminal-by-deletion.

**Variables:**
- `WorkoutSession.completedAt: String = ""` (`data/model/WorkoutSession.kt:9`) — blank = ghost, the *only* ghost test (`WorkoutRepository.kt:786-787` `isGhostSession`).
- `WorkoutSession.sessionRpe: Int? = null`, `sessionLoad: Float? = null` (`WorkoutSession.kt:39-42`) — "null only transiently before the prompt".
- `ActiveRoutineViewModel.sessionFinalized: Boolean` (`ui/screen/activeroutine/ActiveRoutineViewModel.kt:238`) — **RAM-only**, never persisted, never in `UiState`.
- `ActiveRoutineUiState.sessionRegistered: Boolean` (`:72`), `showRpePrompt: Boolean` (`:80`), `selectedRpe: Int?` + `DRAFT_RPE` handle (`:84,:188,:212,:747-751`), `sessionRegisteredEvent: Channel` (`:223-224`, sent `:888`).

**Transitions:**
1. `init` builds fixed→warmup→normal, eager `save()` with blank `completedAt` (`:467-475`) — this write *creates* the ghost risk.
2. `allCompleted = updatedExercises.all { completed }` (`:527,:708`) → `finalizeSession` (`:903-909`) stamps `completedAt`, computes tonnage skipping `excludeFromTonnage || isUntouched` (`:918`), saves (`:937`, merges sidecars), sets `sessionFinalized = true` (`:991`).
3. `requestRegisterRoutine` (`:735-738`) → `showRpePrompt = true` → `submitSessionRpe` validates 0..9 (`:753-759`) → `registerRoutine` (`:774`): re-finalize if needed (`:776-781`), stop ECG/HR (`:783-784`), compute drift/HRR + apply RPE/Load (`:807-822`), sync enqueue + expedited workers (`:830-839`), ECG ledger or file delete (`:852-878`), `sessionRegistered = true` + event (`:883-888`).
4. Exit without register: `onCleared` (`:1021-1049`) always stops ECG/HR; if `!sessionFinalized` reloads (`:1031`) and deletes + `.ecg` iff `completedAt.isBlank()` (`:1036-1040`).
5. Boot: `deleteUnfinalizedSessions` (`WorkoutRepository.kt:819-853`, unthrottled, every launch) deletes blank-`completedAt` + paired `.ecg` + index rows; `runMaintenance` (`:870-962`, 12h-throttled via sentinel) prunes >3mo + orphan ECG.

**Illegal combinations (no assert, silent divergence if produced):** `completed=false + completedEmpty=true`; `sessionRegistered=true + completedAt=""`; `sessionRpe!=null + completedAt=""`; `sessionLoad!=null + sessionRpe==null` (set atomically `:815-818`, so a split means a partial write). `sessionFinalized=true + completedAt=""` in one VM is transient-only; after a process kill RAM resets while disk keeps `completedAt` — the two disagree by design and the sweep re-derives from disk.

**Crash windows:** finalized-but-not-registered (kill between `finalizeSession` save and Registra: durable charts + sidecars, `sessionRpe=null`, never sync-enqueued); RPE-saved-but-not-enqueued (kill between `:821` save and `:834` enqueue — only "Resync all" recovers it); completion-write in flight (kill between `finished=true` and `clearScope` flush — tap lost, slot stays open, safe).

## ISM-02 — Per-exercise slot

**States:** open → performed | skipped (completed-empty) | switched (once). Re-entry: skipped→performed allowed (un-skip on explicit re-complete); performed never re-opens; switched slots locked.

**Variables** (`data/model/WorkoutSession.kt`): `completed` (`:51`), `completedEmpty` (`:81`), `substitutedFor: String?` (`:90`), `sets` (grey pre-fill retained even when skipped, `:72-80` — except cardio-skip `emptyList()`), `startedAt/completedAt` visit window (`:61-62`), `excludeFromTonnage` (`:65`), `isDaily` (`:71`). Derived, pure, unit-tested: `isUntouched() = !completed || completedEmpty` (`:100`), `hasNoRecordedSets()` per-set-type check (`:109-115`), `isSwitchEligible() = substitutedFor==null && hasNoRecordedSets() && !completed` (`:122`), `slotContext` (`:131-136`).

**Transitions:** Strength complete (`StrengthExerciseViewModel.kt:422-452`: `completedEmpty = !anyTouched`); stretch complete (`StretchExerciseViewModel.kt:195-202`); superset complete per-member (`SupersetViewModel.kt:574-644`, `performed = supersetMemberPerformed(...)` `:633`); cardio `stopBlock` both closes *and* completes (`CardioExerciseViewModel.kt:210-246`), `completeExercise` skip path (`:295-300`). Back-navigation exit preserves entry completion flags via `withExerciseSetsOnExit` (`WorkoutSession.kt:201-211`) + per-set `mergeExitSets` (`ExerciseSessionViewModel.kt:142-150`). Active-routine catch-up `markExerciseCompleted` (`ActiveRoutineViewModel.kt:497-538`) bails unless disk says `completed==true` (`:515-517`); `applyExerciseSwitch` (`:620-719`) under `completionMutex` (`:247`).

## ISM-03 — Exercise-screen save protocol

**States:** editing → saving → saved | switched | exit-saved. `finished` (`ExerciseSessionViewModel.kt:51`) set on complete *or* switch gates `onCleared` (`:106-119`): finished → join `completionJob` then `clearScope.cancel()`; else `saveProgressOnExit()`. `markCompletionAndSave` (`:78-86`): `if (finished) return; finished = true; clearScope.launch { block(); _completionSaved = true; _completionEvent.send() }`. Navigation uses the one-shot `Channel` event (`:71-72`), never `LaunchedEffect(flag)` — documented `:57-70` why Channel and not SharedFlow-with-buffer.

## ISM-04 — Switch-result delivery

`switchedExerciseIds` is a `"old,new;old,new"` queue (not a single slot — two switches before returning must not overwrite), `completedExerciseId` / `completedSupersetIds` single slots; all read reactively via `getStateFlow` (`AppNavigation.kt:188-223`), consumed then `remove()`d. Helpers `appendSwitchResult` / `parseSwitchResults` (`:47-50`) are unit-tested (`SwitchResultQueueTest`); malformed pairs skipped, not crashed on.

## ISM-05 — Sync ledgers ×5

Shared `SyncStatus { PENDING, SENT, FAILED, EXPIRED, DELETED_PENDING, DELETED_SENT }` (`data/sync/SyncLedgerEntry.kt:17`). `EXPIRED` = terminal gave-up, excluded from every `getPending()`. `DELETED_*` only from `RepoLedgerRepository`. Real state is the tuple `(status, op, contentHash, attempts)` and **every** transition function is unchecked on source state (only unknown-id no-ops):

- sessions (`SyncLedgerRepository.kt`): `enqueue→PENDING/0` blind overwrite (`:144-156`); `requeueIfChanged→PENDING` keeps `attempts`, fires from *any* state despite "if SENT" doc (`:164-177`); `markSent/markDead/markFailed` any→target (`:179-237`); `resetAllToPending` resurrects SENT **and** EXPIRED (`:245-251`). No retry cap (`SyncWorker.kt:35-36`); missing file → `markDead` (`:84-89`).
- readiness (`ReadinessLedgerRepository.kt`) / scale (`ScaleWeighInLedgerRepository.kt`): same shape minus bytes fields, no `requeueIfChanged` / `resetAllToPending` / `expireStale` / delete ops. Scale id = ISO date, same-day re-weigh = hash update.
- ECG (`EcgSyncLedgerRepository.kt`): + `enqueuedAt`; `expireStale(30d)→EXPIRED` without bumping `attempts` (`:196-212`) — only pipeline that must give up (ephemeral raw file, `docs/SYNC.md:813-844`). Hash is over compressed bytes; enqueue-time hash is provisional (`:32-39`).
- repo (`RepoLedgerRepository.kt`): keyed by relPath; no `enqueue` — `requeueIfChanged` *is* the upsert and **resets** `attempts` (`:142-166`, un-tombstones `DELETED_*` when the file reappears); `markDeleted→DELETED_PENDING` idempotent (`:177-195`); `markSent`/`markFailed` targets depend on `op` — deletes never enter FAILED (`:197-231`); `markRestored→SENT/upsert/0` blind overwrite (`:239-252`). Rename = `requeueIfChanged(new)` + `markDeleted(old)`.

Projection: `SessionSyncStatus { CHECKING, SYNC_OFF, PENDING, SENT, FAILED }` (`SessionProgressViewModel.kt:41`) derived gate-first from config then ledger (`:195-217`); `EXPIRED` collapses to FAILED (`:203`); missing row → PENDING (`:205-206`); live via worker observer (`:185-193`) + 2s poll while pending (`:168-178`). `bytesSent/durationMs/serverStatus` surfaced only on SENT (`:211-216`) — written only by the session ledger.

## ISM-06 — Sync scheduling gate

`shouldSyncRun = configured && (enabled || force)` (`SyncGate.kt:24-25`); force only via `SYNC_FORCE_KEY` from session-finalize/manual-send (`SyncWorker.kt:138-155`); periodic never forced (`:158-165`); enqueue gated only on *configured*, never on *enabled*.

## ISM-07 — Polar BLE link + reconnect

`connectionState` (`PolarManager.kt:206-207`) × `connectedDeviceId`/`lastConnectedDeviceId` (`:393,:462`) × `@Volatile userInitiatedDisconnect` (`:463`) × `reconnectStartAtMs` + handler (`:464,:458`) × SDK `setAutomaticReconnection` (`:745,:783,:1172,:1253`) × `streamingFeatureReady` (`:442`). `CONNECTING` means three different things (first handshake, mid-session retry → `SVC.setReconnecting`, out-of-session drop → `DISCONNECTED + noSignalGrace`). Mid-session involuntary drop → `CONNECTING + scheduleReconnect` (`:609-618`, 3s→10s backoff, 5min cap → `DISCONNECTED + hrSeriesActive=false` `:808`); out-of-session involuntary drop → `DISCONNECTED + startNoSignalGrace` (`:619-627`). `disconnect()` vs `shutdown()` vs `blePowerStateChanged(false)` differ in which of `connected/lastConnected` ids they null and whether the SDK is shut down (`:749-789,:825-846,:486-514`). Dual reconnect layers (SDK auto-reconnect + app `scheduleReconnect`) — teardown order matters; `stopHrSeriesCapture` disables SDK reconnect but does not touch ECG (caller must `stopEcgRecording` separately, `:1228-1261`).

## ISM-08 — `linkStatus` projection (already pure — pattern to copy)

`combine(_connectionState, _receivingData, _noSignalGrace, _hrStandby)` (`PolarManager.kt:266-269`) → total pure `linkStatusOf` (`PolarLinkStatus.kt:50-66`): `CONNECTED` + standby wins → STANDBY, else receiving?CONNECTED:NO_SIGNAL; `CONNECTING/DISCONNECTED` + grace → NO_SIGNAL. The 4 inputs are still mutated from ~15 sites (connect/disconnect/standby/watchdog/HR callbacks) with no single `setLink()`; `receivingData=false` on `deviceConnected` deliberately yields NO_SIGNAL until the first sample.

## ISM-09 — HR streaming / standby / watchdog

`hrDisposable` (`:438`) × `_hrStandby` (`:249-250`) × `_receivingData` (`:238-239`) × `lastHrSampleAtMs` (`:468`) × restart/watchdog handlers (`:456-457`). `startHrStreaming` (`:848-968`) disposes-then-subscribes; `doOnComplete/onError → scheduleHrRestart` 2s (`:970-978`); watchdog 1s (`:1465-1515`): stale >5s flips display only, >15s restarts the stream. `enterHrStandby` (`:986-1003`) no-ops unless CONNECTED and idle; `ensureHrStreaming` (`:1012-1020`) clears standby first. `standby=true + hrDisposable!=null` and `standby=true + hrSeriesActive/readinessMeasuring` are excluded only by early-return guards, not by construction.

## ISM-10 — ECG streaming / recording / live analysis

`ecgDisposable` (`:439`) × `streamingFeatureReady` (`:442`) × `pending/activeEcgSessionId` (`:443-444`) × `ecgRestartAttempts` (cap 3, `:450 + :177`) × recorder `Channel` (`EcgRecorder.kt:75-77`). `startEcgRecording` (`:1272-1285`) parks to `pending` when offline; `scheduleEcgRestart` (`:1410-1430`) counts toward cap; `escalateEcgRecovery` (`:1439-1458`) keeps `active`, parks `pending`, forces a BLE disconnect so the reconnect path restarts ECG on `FEATURE_ONLINE_STREAMING ready` (`:646-653`). `deviceDisconnected` (`:594-607`) keeps `active` for resume while stopping the recorder (file truncated, resume re-`start()`s same id with a new header); `disconnect/shutdown/bleOff` null both. Disconnect-while-recording vs stop-before-disconnect produce different `active/pending` pairs; the watchdog (`:1490-1510`) only watches when all three of `active/featureReady/disposable` hold. Live side: `LiveEcgAnalyzer` buffers + `suppressUneven` gate (`:60`, driven by HR≥70%max `:913-914`), `snapshot()` `regularPct=NaN` until first beat; `HrZoneTracker.onTick` drops `dt` outside 0..0.2min (reconnect-gap guard).

## ISM-11 — In-session capture

`hrSeriesActive` (`:295`) × `hrSeries` (cap 28800, `:296 + :159`) × `sessionDrops`/`_disconnectStats` (`:305-307`) × `isRecovering` (`:429`) × `pendingHrrPeaks`/`hrrDeltas`/`hrrDeltasDiscarded` (`:311-316`) × zone/TRIMP/calorie accumulators (`:288-360`). `startHrSeriesCapture` (`:1153-1198`) zeroes everything and re-enables SDK reconnect; `stopHrSeriesCapture` (`:1228-1261`) parks to standby. Peak detection runs on *every* HR sample including out-of-session/readiness (`:952`); `hrrWindowAverage` falls back to instantaneous HR when `hrSeries` is empty (`:1070`). Rebound tolerance 8 BPM here vs 5 server-side (documented `:147-156`: raw 1Hz vs 12s-smoothed). Mid-session drop appends `SessionDrop` + `everyDropAutoRecovered=false` (`:577-587`), cleared to true on reconnect (`:545-551`); 5min-timeout flips `hrSeriesActive=false` without clearing `hrSeries`.

## ISM-12 — Readiness measurement

`readinessMeasuring` (`:413`) × `ReadinessResult.readiness ∈ {MEASURING,DELOAD,LIGHT,NORMAL,GOOD,PEAK,NO_BASELINE}` (`:81-89`, default MEASURING `:92`) × `pendingSleepQuality` (`:423-424`) × persisted `readiness/YYYY-MM-DD.md`. Auto-path on connect when idle (`:541-544 → :1591-1623`): already-measured → publish stored + park; past 10:00 cutoff → NO_BASELINE + park; else `startReadinessMeasurement` (`:1625-1645`, 60s countdown driven by HR samples `:933-949`). `finishReadinessMeasurement` (`:1647-1780`): <20 clean RR → NO_BASELINE + park (`:1654-1664`); else RMSSD/LnRMSSD/resting-HR, then fire-and-forget baseline + `uthVo2max` + `classify` + save + ledger + sync, `pendingSleepQuality=null` after save (`:1757`); always `enterHrStandby()` (`:1779`, no-op mid-session). Pure kernels already extracted: `ReadinessMetrics.filterArtifacts/calculateRMSSD/uthVo2max` (`ReadinessMetrics.kt:28-48`). `readiness=MEASURING + readinessMeasuring=false` is reachable transiently (initial default, async gaps); `pendingSleepQuality!=null + already-on-disk` transiently true until the async `setSleepQuality` clears it (`:1792-1818`).

## ISM-13 — Battery tracking

`_batteryLevel: Int?` / `_batteryLow` (≤70, `:212-217 + :168`) × `batterySpanAnchorSec` (0 = no span, `:454`) × heartbeat handler 5min (`:455 + :193`, cap 15min `:196`) × `BatteryLifeState` (`BatteryLifeRepository.kt:237-259`). Anchor on connect (`:537-538`), flush on every disconnect path + heartbeat (`:1548-1558`, fire-and-forget `addActiveSeconds`); `batteryLevelReceived` (`:674-691`) seeds/updates the cell; `resetBatteryCell` is the only manual path (`:1566-1574 → BL.resetForNewBattery`, history kept iff ≥2h credible). `_batteryLife=null + anchor>0` possible until the first level seeds the cell (span dropped, `BL:87`).

## ISM-14 — `PolarStreamingService` notification mirror

`isRunning × reconnecting × standby × currentHr` (`:194-197`) driven by 5 intent actions (`:31-35`): START clears all + foreground (`:212-228`); UPDATE_HR(hr>0) clears flags, sets HR (`:229-238`); RECONNECTING/STANDBY clear each other + null HR (`:239-253`); STOP/onDestroy clears `isRunning` (`:254-268`); OS restart with null intent self-stops (`:205-210`). `buildNotification` priority: reconnecting > standby > currentHr > "Connecting…" (`:283-288`). `updateHr/setReconnecting/setStandby` no-op when `!isRunning` and swallow background-start exceptions (`:107-152`) — never resurrecting a `forceStop`ped notification. `onTaskRemoved` force-disconnects (`:275-280`). `reconnecting && standby` and `currentHr!=null && (reconnecting||standby)` are impossible only by action-ordering, not by type.

## ISM-15 — `StopwatchService` mode

`countdownDuration: Int?` (`:58`, null = legacy count-up) × `loopStartElapsed` (`:59`) × `loopCount` (`:60`) × `lastVibrationAt` (`:56`) × handler loop (`:75-110`). `ACTION_START` resets all together (`:121-127`); `EXTRA_DURATION_SECONDS<=0` silently falls back to count-up (`:125`); `ACTION_STOP` resets mode/count/vibration but not `loopStartElapsed` (`:141-147`, harmless — next START overwrites); unknown action leaves a frozen foreground service (`:117`). In-flight triple-buzz waveform is not cancelled on STOP (`:248-259` vs `:252` cancel-only-at-next-LOOP_END).

## ISM-16 — `StopwatchBuzzSchedule` (already pure)

`(loopElapsed, duration, lastPeriodicAt)` → LOOP_END wins over PERIODIC, PERIODIC suppressed within 2s of loop end, `lastPeriodicAt` baseline 0 (first buzz at exactly 30s). `countUp` has no LOOP_END/suppression. Caller: `tickRunnable` (`StopwatchService.kt:83,101`). Edge by design: 30s-into-31s stretch skips the periodic buzz.

## ISM-17 / ISM-18 — Stretch / superset display timers

VM quintuple `isStopwatchRunning × timerJob × remainingSeconds × loopCount × countdownDuration` (`StretchExerciseViewModel.kt:34,38-40,60`; `SupersetViewModel.kt:101,107-109,137`) mirrors the service (§ISM-15) with lockstep kept only by the Screen (`SupersetViewModel.kt:411-414`) — the VM stop does not itself stop the service. Pure core already extracted: `StretchCountdown.start/tick` (`StretchCountdown.kt:17-36`, wrap + `true` on loop end, display never shows 0). Deltas: superset duration = first STRETCH set else 60 (`SupersetCountdown.kt:11-13`); superset `toggleSetDone` is two-way (`:451-454`) while stretch is one-way done (`StretchExerciseViewModel.kt:127`), feeding `anyTouched/performed` (`SupersetViewModel.kt:618-620`); chain with no STRETCH member still offers a meaningless 60s timer (`SupersetViewModel.kt:282` forbids CARDIO so the timer always means stretch-or-60s).

## ISM-19 — Cardio block

Screen-level `CardioButtonState{IDLE,RUNNING,DONE}` (`CardioExerciseScreen.kt:50-56`) derived: RUNNING if `isBlockRunning`, else DONE if `completedBlocks` non-empty, else IDLE. VM (`CardioExerciseViewModel.kt`): `configuredDurationSeconds` 0 = unset (`:41`), `remainingSeconds` counts negative = overtime (`:42-46`), per-block `startedAt/hrSum/hrMax` (`:78-81`), `timerJob/hrCollectJob` (`:82-83`). `stopBlock` closes *and* completes in one tap (`:210-246`); back-press persists an **open** block (`endedAt=""`, `:317-345`) — the only VM that does; kill-recovery path synthesizes `endedAt` at init (`:111-112`) and that ghost-closed block counts as performed. `RUNNING + configuredDuration==0` is legal (runs with no target, warning hidden while running `:137-145`).

## ISM-20 — Strength touch / modify / anchor

Per `StrengthSetUi` (`StrengthExerciseViewModel.kt:30-47`): pre-fill vs input split four ways — `repsModified/weightModified` (purple display, `curr!=prev` `:214-215`) vs `repsTouched/weightTouched` (performed signal). Weight edit with untouched reps re-anchors tonnage and rewrites reps grey (`:325-342`, manual-load + bodyweight/assisted exceptions `:311,:317-324`); any touch/confirm clears `tonnageAnchor` (`:302,:321,:348,:354`) so anchor>0 implies untouched-since-anchor. `repsModified=true + repsTouched=false` is reachable (disk-loaded diff shows purple at init `:214`, yet `anyTouched=false → completedEmpty=true :430,:438-439`) — purple ≠ performed by design. `allSetsFilled` (`:526`) allows weight-0 for bodyweight/assisted but inits false until first `updateSet` (`:284-285,:514-533`) even with non-zero pre-fills.

## ISM-21 — Scale connection + save

`ScaleConnectionState{DISCONNECTED,SCANNING,CONNECTING,CONNECTED}` (`BleScaleManager.kt:38-43`) × `gatt` (`:97`) × `scanCallback` (`:98`) × `autoConnectAttempted` (`:80`) × `weightSamples` (cap 600, `:86 + :77`) × `lastReading/lastError` (`:106-110`) × `scanTimeout` 30s (`:90`) / `autoDisconnect` 1500ms (`:91`). `startScan` no-ops unless DISCONNECTED (`:118`) and never extends the timeout; CONNECTING has no timeout (dead `connectGatt` sticks forever, `startScan` blocked); `onConnectionStateChange` ignores non-CONNECTED/DISCONNECTED states (`:262-277`); `disconnect` relies on the async callback to flip state (`:190-193` vs `:270-274`); `onScanFailed` flips to DISCONNECTED but leaves callback + armed timeout (`:145-148`); service-discovery failure disconnects without a state change (`:279-313`, stuck CONNECTING until callback); failed auto-connect never retries within the same scan (flag latched `:123,:139-142`); every impedance packet re-saves + re-enqueues + re-pushes `autoDisconnect` (`maybeSaveWeighIn :205-258`, last-wins `:196-204`).

## ISM-22 — Scale protocol + averaging (already mostly pure)

`parse()` → null (short/bad-header/unknown-cmd/sentinel) vs weight vs body frame (`VtrumpSenheProtocol.kt:42-91`); `weightStable` only meaningful on weight frames (compo hardcodes false `:87`, merge ignores it `:346`); unknown `unitCode` falls to KG (`:102-108`) instead of rejecting; unbounded `accuracyRaw` shift accepted (`:64`); `ScaleWeightAverager` tail-10 average, empty→null (`:23-27`), <10 samples averages whatever exists (documented trade-off).

## ISM-23 — Home two-phase load

`job` (`HomeStateLoader.kt:49`) × `@Volatile everCompleted` (`:58`, once true `:219`) × `force` (`:64-69`: running + plain → join; force → cancel + fresh). Cold: phase-1 scaffold (`isLoading=false`, history final, schedule without outcomes `:137-157`) → await week (`:161`) → phase-2 full swap (`:209-218`). Warm (incl. force-after-first): hold last complete, single swap — no flicker (`:150-154`). `force`-cancel mid-first-load re-emits scaffold twice (legal); `force` during phase-2 await aborts with `CancellationException` and no emit (stale held, no error surface); non-force concurrent refresh silently joins (`:66`); today lives in `today*` fields, not `scheduleCells[today]` (`:192` vs `:209-215`, renderer must combine).

## ISM-24 — Debug-seed gate

`isSeedingData` (VM `_isSeedingData` flow combined over loader state, `MainViewModel.kt:88-93`) guards `seedDebugData` re-entry (`:134`), cleared in `finally` (`:140`), followed by `refresh(force=true)` (`:144`). Loader state and seeding flag are independent flows — seeding during a phase-1 load is legal and simply re-emits after.

## ISM-25 — SessionProgress backup-verify box

`backupVerifyRunning × backupVerifyProgress × backupVerifyError × backupVerifyReport` (`SessionProgressViewModel.kt:89-92`), armed only when `justCompleted` (`:114-115,:125`). Entry clears the loser (`:149`); terminal clears progress and sets exactly one of error (HardFail `:155`) / report (Done `:157`). `running=true + progress=null` unreachable after init; unconfigured server → same slot with string-only distinction (`:137-143`); single-shot, no retry/poll (`:131-134`) — can disagree with the adjacent `syncStatus` box (SENT + HardFail simultaneously).

## ISM-26 — Options async ops

Six instances of the same `(running/testing × progress/secondsLeft × error/result)` shape with per-op triggers (`OptionsViewModel.kt`): connection test (`syncIsTestingConnection × syncConnectionTestResult: Boolean?`, null = untested, URL/token edits reset to null `:73-74,:249-287`); resync (`syncIsResyncing × syncResyncProgress 0..1`, re-entrancy guard `:339`, denominator fixed at start `:399-413`, terminal `false + 1f :417`, polled per-second, not WorkManager state `:422-441`); restore (`syncIsRestoring × syncRestoreResult: String?`, guard `:457`, tarball-then-manifest fallback `:469-486`, allowlist + `markRestored` so restored files don't bounce back `:450-455`, summary builder `:931+`); backup-verify (same quad as ISM-25, guard `:632`, `:626-657`); ECG debug (`ecgDebugRecording × ecgDebugSecondsLeft × ecgDebugResult`, guards `:677-724`); step debug (`stepDebugRunning × stepDebugResult`, `:746-771`); Polar self-test (`polarSelfTestRunning × polarSelfTestSecondsLeft × polarSelfTestSearching × polarSelfTestResult`, live sample + offline checks `:798-900`, parked-strap read first via `resumeLiveHr` `:801`).

## ISM-27 — Routine editor

`supersetWithNext` chains (cap 3, `canEnableSupersetLink`, `RoutineEditViewModel.kt:83-88`): tail-true only via remove-becoming-last (handled `:187-191`), direct toggle on last blocked (`:84`); `warmupCount` snapped to segment boundary on drag (`snapWarmupToBoundary :243-253` applied `:287`) and toggle-across-line refused (`:202`), but `removeExercise` only decrements without re-snapping (`:195`) — transient mid-segment value marks too many warmup in `buildRoutine` (`:383`); `isNew` (blank vs loaded `:98-140`) / `isFixedDaily` (name/day locked, `isWarmup=false` forced `:383`); `deleted` blocks save (`:348,:358`) with one-shot `_deletedEvent` (`:331`); `saveClaimed/saveLock` single-flights `saveNow` (back) vs `onCleared` autosave (`:339-344`); drafts cover name/day strings only (`:394-395`, sets/flags lost on kill `:390-392`); blank name → silent drop (`:348,:358`).

## ISM-28 — Exercise editor

`isNew` (null id → blank + bodyparts `:82-107`) / `deleted` (flag + event `:185-193`); `isBodyweight XOR isAssisted` enforced both toggle directions (`:123-145`), `LoadMode` priority BODYWEIGHT > ASSISTED > MANUAL (`:215-219`); `type=CARDIO` forces blank bodypart on save (`:206`); autosave `saveNow` (explicit, sets `savedExplicitly :235`) vs `onCleared` (iff `!savedExplicitly`, non-blank, `!deleted :238-251`) — single-flight one direction only, no `claimSave` lock (unlike ISM-27); drafts cover 4 text fields only, numerics/steppers/type lost on kill (`:254-263`); `bwLoadPercent` unclamped on persist (`:213`); non-FORZA tempo stored as-is (inert, `:222`).

## ISM-29 — Repo maintenance + sidecar invalidation

Unthrottled ghost sweep (`deleteUnfinalizedSessions :819-853`, blank-`completedAt` → delete + index strip + paired `.ecg`, never rebuilds sidecars `:815-816`) vs throttled `runMaintenance` (`:870-962`, sentinel-gated `:965,:1044-1052`, single walk, one parse serving prune/ghost checks `:905-913`) with stats rebuild only when the removed session was completed (`:917-918`) — ghost cleanup never feeds sidecars because `save()` only merges completed sessions. `delete()` shares the completed-only rebuild rule (`:587-591`); `runMaintenance` rebuilds outside the lock with an mtime re-check (`:609-624`, `:956`).

## How to find more (method used)

- ≥2 mutually-exclusive booleans per class → cluster.
- Nullable used as mode discriminant (`Int?` countdown, `String?` substitutedFor, `sessionRpe`) + "null means" comments.
- Repeated predicate (`completedAt.isBlank/isNotBlank` — 20 hits in 9 files) → un-typed state.
- `when`-with-priority as the state table (`buildNotification`, `buttonState`, `linkStatusOf`, `BuzzSchedule`).
- Invariant comments ("only when", "never both", "must be", "idempotent", "transiently") → unwritten transition rules.
- Transition sites: count writers per field (`enqueue/mark*/expireStale`, `start/stop*`, `ACTION_*`, `onCleared`, watchdog/handler posts).
- History: files with many Phase-fix commits (session, superset, sync, switch) mark high-state areas.
