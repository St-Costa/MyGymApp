# Server sync

Status: **phone-side transport implemented** (`data/sync/`, hooked into
`ActiveRoutineViewModel.registerRoutine()` and the Options screen). The server side lives
in a separate repository (`MyGymApp_server`) per `docs/sync-ingestion/SPEC.md` there — not
in this codebase. This document is the design this implementation followed; kept in sync
with the code per CLAUDE.md's working conventions.

## Goal

At the end of every workout session, push that session's data (tonnage, calories, TRIMP,
VO2max, ECG/HRV metrics, per-exercise sets — everything currently in the session YAML,
see [STORAGE.md](STORAGE.md#workout-session-historyyyyymmyyyy-mm-dd_routineid_sessionidmd))
to a server on the user's own tailnet, reachable via Tailscale. The server stores it and
runs weekly analysis. No other party involved — one phone, one server, both owned by the
same person.

**Out of scope for v1**: VitaFit BLE scale readings (separate in-progress feature,
`feature/vitafit-scale-ble` — not merged). The design below is generic enough that scale
readings become a second record type later without restructuring anything; see
[Extensibility](#extensibility-adding-a-second-record-type-later).

## The core design problem

This codebase's on-disk schema changes almost every phase (see CHANGELOG.md — readiness,
`hrrPerSet`, `isDaily`, cardio trend charts, PR badges, etc. all added fields over time).
A sync system that serializes the app's Kotlin data classes straight to a bespoke JSON
contract will break, silently, every time `WorkoutSession` gains a field — because nothing
forces the sync code to be touched in the same commit.

The fix applied throughout this design: **sync the raw session file, unmodified**. The
wire format *is* the on-disk format already documented in STORAGE.md. No second schema to
hand-maintain in two codebases. The phone-side sync code becomes "read bytes, POST bytes,
record success" — it has zero knowledge of what fields exist inside a session. All
interpretation of the YAML lives server-side, in one place, and gets touched exactly when
STORAGE.md gets touched (same discipline the project already has for docs/code drift).

**This played out for real, not just in theory**: while implementing server-side analysis
against synced data, the server found that `weight: 0.0` was ambiguous between "set never
touched" and "genuinely bodyweight work" (plank, push-ups — no external load by design),
silently dropping all bodyweight tonnage from any analysis that filtered on `weight > 0`.
Fixed by adding `Exercise.isBodyweight` (set once per exercise in the catalog, not
per-session) and propagating it onto each `WorkoutSession` set as `isBodyweight: true`
(omitted when false, same convention as every other optional boolean field) — see
STORAGE.md's session/exercise format examples. No sync-layer code changed at all: the new
field just started appearing in the same raw bytes already being sent, exactly as this
design intends. The server's raw-first parser already tolerated unknown fields, so
`parse_failures` didn't even spike while the server-side column/filter update landed.

**Second example, same pattern**: `sessionRpe`/`sessionLoad` (session-RPE, Foster method —
subjective 0-9 "how hard was this session", asked via a mandatory prompt right after the
session ends — registration is blocked until answered) were added the same way — new
fields on `WorkoutSession`, still nullable/omitted at the model level (covers abandoned
sessions and pre-existing history), no sync-layer changes. These exist to give the
server's tonnage-based ACWR monitoring (external load) a matching internal-load signal to
validate/enrich against, and could feed a server-side readiness score later. That
interpretation is out of scope for this repo — see `MyGymApp_server`'s
`docs/sync-ingestion/SPEC.md`.

## Architecture overview

```
┌─────────────────────────┐  Tailscale Serve   ┌──────────────────────────┐
│  Phone (MyGymApp)       │  (tailnet-only,     │  Self-hosted server      │
│                          │   HTTPS, no public  │                          │
│  session finalized       │   exposure)          │  receiver (FastAPI)      │
│    → write .md to disk   │ ───────────────────▶│    → validate envelope   │
│    → enqueue in          │  POST /v1/sessions   │    → write raw .md to   │
│      SyncQueue (local     │                      │      disk (source of    │
│      ledger)              │                      │      truth)              │
│                          │                      │    → parse → upsert into│
│  SyncWorker (WorkManager) │◀──────────────────── │      SQLite (query layer)│
│    drains queue,          │  200 OK {sessionId}  │                          │
│    retries w/ backoff     │                      │  weekly cron/script      │
└─────────────────────────┘                      │    reads SQLite, runs     │
                                                   │    analysis               │
                                                   └──────────────────────────┘
```

Two separate concerns, deliberately kept apart:

1. **Transport**: get the exact bytes of a session file from phone to server, reliably,
   exactly-once-effectively (idempotent on retry).
2. **Interpretation**: turn those bytes into queryable rows for analysis. This can be
   re-run at any time from the raw files without touching the phone again.

---

## Part 1 — Transport (phone side)

### 1.1 Trigger

Sync is triggered at the same point the session is finalized — the existing
`registerRoutine()` path in `ActiveRoutineViewModel` (the point where the session `.md` is
already durably written to disk and the ghost-session guard has already decided the
session is real, not abandoned). Adding one line after the existing save:
`syncQueue.enqueue(sessionId)`.

This does **not** perform the network call inline. It only writes a queue entry. The
actual send happens asynchronously via WorkManager (§1.3). This matches the project's
existing discipline of never letting network/IO uncertainty block the save-and-navigate
path (`completionSaved` pattern, `onCleared()` save pattern — save first, side effects
after, never block UI on network).

### 1.2 Local sync ledger

New small file-based ledger, following the project's "no database" convention rather than
introducing Room for one small table:

```
filesDir/gymdata/_sync/state.yml
```

```yaml
sessions:
  3c4d5e6f:
    status: PENDING           # PENDING | SENT | FAILED
    attempts: 0
    lastAttemptAt: ""
    lastError: ""
    contentHash: ""           # sha256 of the file bytes at enqueue time
  a1b2c3d4:
    status: SENT
    attempts: 1
    lastAttemptAt: 2026-08-05T09:15:00
    lastError: ""
    contentHash: 9f8e7d6c5b4a...
```

Owned by a new `SyncRepository` (mirrors the existing repository pattern — in-memory cache
+ mutex + IO dispatcher, same as `ExerciseRepository`/`WorkoutRepository`). Read/write
through `MarkdownParser`-style YAML (snakeyaml-engine, already a dependency — no new
parsing library needed).

Why a hash and not just "file exists": if a session file is edited after being marked SENT
(not common today, but sessions technically could be re-saved — e.g. name-sync-on-rename
rewrites session files, see STORAGE.md), the ledger should notice the content changed and
requeue it. `WorkoutRepository`'s save path emits `DataChangedSignal`-adjacent hooks
already; add one more call there: after any session file write, if the new content hash
differs from the ledger's recorded hash for that ID, flip status back to `PENDING`.

Why not "ask the server what it already has" (considered and rejected): it makes offline
queueing pointless (the phone needs the server reachable just to know what to send) and
adds a round trip. A local ledger is simpler, works fully offline, and is authoritative for
the one thing that matters — "have I successfully handed this exact content to the server."

### 1.3 Delivery worker

A `SyncWorker : CoroutineWorker`, scheduled two ways:

- **Expedited one-off** enqueued right after `syncQueue.enqueue(sessionId)` — tries to
  send promptly if network+tailnet are available, so data usually lands within seconds of
  session end, not just "eventually this week."
- **Periodic** (e.g. every 4 hours, `ExistingPeriodicWorkPolicy.KEEP`) as the durability
  net — catches anything the expedited attempt couldn't send (phone off tailnet, server
  down, app killed before the one-off ran).

Constraints: `NetworkType.CONNECTED` (WorkManager constraint). No tailnet-specific
constraint exists in WorkManager — connectivity to the *tailnet* specifically is verified
by the request itself failing/succeeding, not pre-checked. This is fine: a failed attempt
(server unreachable) just leaves the ledger entry `PENDING`/`FAILED` and WorkManager's own
backoff policy (`BackoffPolicy.EXPONENTIAL`, e.g. starting at 30s, capped at the periodic
interval) retries later without any custom logic.

Worker logic per pending ledger entry:

1. Read session file bytes from `history/YYYY/MM/...md` by re-deriving the path from the
   session ID the same way `WorkoutRepository` already does (or, simpler: store the
   relative path in the ledger entry at enqueue time, since the ledger already knows the ID
   the moment the file is written and its path is known then).
2. Compute sha256, confirm it matches the ledger's recorded hash (guards against sending a
   file that changed underneath the ledger between enqueue and send — rare, but cheap to
   check).
3. POST to the server (§1.4).
4. On 2xx: ledger entry → `SENT`, `attempts += 1`.
5. On non-2xx or exception: ledger entry → `FAILED`, `attempts += 1`, `lastError` recorded,
   `lastAttemptAt` updated. Left as `PENDING`-equivalent for the next worker run (i.e.
   `FAILED` is still eligible for retry — it's a status for user-visible diagnostics, not a
   dead-letter state). No cap on retry count for v1 — a self-hosted personal server being
   down for a while is expected and should just catch up whenever it's back, not give up.

### 1.4 Request shape

```
POST /v1/sessions
Content-Type: multipart/form-data
Authorization: Bearer <shared-secret>

  part "envelope" (application/json):
    {
      "sessionId": "3c4d5e6f",
      "relPath": "2026/08/2026-08-05_rt-b2c3d4e5_3c4d5e6f.md",
      "contentHash": "sha256:9f8e7d6c...",
      "appVersion": "1.0-phase29",       // git describe or versionName, for server-side
                                          // "which parser logic applies" if ever needed
      "clientSentAt": "2026-08-05T10:05:42Z"
    }
  part "file" (text/markdown):
    <raw bytes of the .md file, unmodified>
```

Multipart chosen over a single JSON body with base64-embedded content: avoids the
33%-inflation and escaping headaches of embedding a Markdown+YAML blob (which itself
contains a free-text body that can hold arbitrary characters) inside a JSON string. The
server gets the exact original bytes back, byte-for-byte, which matters since
`contentHash` is checked against them on receipt too (§2.2).

Response:

```
200 OK  { "sessionId": "3c4d5e6f", "status": "stored" }       // first time
200 OK  { "sessionId": "3c4d5e6f", "status": "duplicate" }    // already had this hash
400/422 { "error": "..." }                                    // envelope malformed
401     (missing/wrong bearer token)
```

Both `stored` and `duplicate` are treated as success by the worker (§1.3 step 4) — this is
what makes retries safe (§3).

### 1.5 Settings / configuration

New section in the existing `OptionsScreen` (`ui/screen/options/`, already has a "Dati di
debugging" section and the powerlifting-week settings — this fits the same screen rather
than inventing a new one):

- **Server URL** text field (the Tailscale Serve HTTPS address, e.g.
  `https://gym-server.<tailnet-name>.ts.net`) — see §4 for why this specific form.
- **Bearer token** text field (masked, like a password field).
- **Sync enabled** toggle — off by default until both fields are filled in; lets the
  feature ship dormant and be turned on deliberately. **Scope of what this gates**: only
  the *automatic* per-session enqueue in `ActiveRoutineViewModel.registerRoutine()`. It
  does **not** gate whether `SyncWorker` will drain entries that are already `PENDING` in
  the ledger — those exist only because of an explicit action (the toggle was on when that
  session finished, or "Resync all" was pressed), and once queued they get delivered
  regardless of the toggle's current state. Conflating the two — checked live on-device —
  makes "Resync all" a silent no-op whenever sync is off, which defeats its own purpose
  (testing/backfilling *before* committing to automatic sync).
- **Status line**: "N elementi in attesa (sessioni, misurazioni, pesate), ultimo invio:
  <time>" — sums the pending count across all three ledgers (sessions + readiness +
  scale), read without needing `adb` to check.
- **"Invia tutti i dati in coda" button**: re-enqueues every session, readiness event, and
  scale weigh-in found on disk, across all three independent sync pipelines (§ below),
  regardless of prior SENT status. This is the backfill mechanism (§3.3) and covers three
  cases at once: "the server's parser just learned to handle a new field, re-send
  everything" during development; catching up data that was created while the server
  didn't exist yet or was unreachable (the common case while building the server side —
  the phone always persists and queues locally regardless); and a manual nudge after a
  known outage. Works even with the enabled toggle off — see above.

URL + token stored in `SharedPreferences` (`sync_config`, `MODE_PRIVATE`) — same tier of
sensitivity as `user_profile`, already documented in STORAGE.md's SharedPreferences table;
add a row there once implemented.

---

## Part 2 — Server side

### 2.1 Stack

Small Python service (FastAPI) — matches "simple script/service I run myself." No
particular reason it couldn't be Node/Go instead; FastAPI is a reasonable default for a
single-person receiver with async I/O and good multipart support out of the box.

### 2.2 Receiver endpoint (`POST /v1/sessions`)

1. Check `Authorization: Bearer` against the configured shared secret (constant-time
   compare). Reject with 401 otherwise.
2. Parse the `envelope` JSON part, validate required fields present.
3. Read the `file` part's bytes, compute sha256, compare to `envelope.contentHash`. Reject
   with 422 on mismatch (corrupted upload) — do **not** store a file that failed its own
   integrity check.
4. **Idempotency check**: does a row already exist for `sessionId` with this exact
   `contentHash`? If yes → respond `200 duplicate`, no write. (Handles retried requests
   where the phone never saw the first response, and re-sent "Resync all" runs.)
5. If `sessionId` exists with a **different** hash (session was edited on the phone after
   an earlier sync — e.g. rename-sync rewrote it), this is an update: overwrite, don't
   duplicate.
6. Write raw bytes to disk, mirroring the phone's own layout for familiarity:
   `~/gym-server-data/raw/{relPath}` (i.e. `raw/2026/08/2026-08-05_....md`). This is the
   **source of truth** on the server — human-readable, `git`-able if you want history on
   it, and the thing every future re-parse reads from. Never derived-only data lives only
   in SQLite.
7. Parse the YAML frontmatter (a from-scratch small parser — the server doesn't share code
   with the Android app, so this is a second implementation of "read this YAML shape,"
   deliberately thin: a handful of known fields per STORAGE.md, defaulting missing ones,
   same tolerance policy the Kotlin `WorkoutParser` already uses). Upsert into SQLite
   (§2.3) keyed by `sessionId`.
8. Respond `200 stored`.

Steps 6 and 7 are two different failure domains and should not be coupled: if step 7 (YAML
parsing) throws because of a field shape the server doesn't understand yet, step 6 (raw
file write) has **already succeeded and is durable**. The endpoint should catch a
parse-layer exception, log it, and still return `200 stored` (the phone's job — reliably
delivering bytes — is done; the server's own backlog of "raw files not yet reflected in
SQLite" is a server-side problem with a server-side fix, see §2.4). This is the direct
payoff of splitting raw storage from parsed storage: a schema you haven't taught the parser
about yet **cannot** cause data loss or a phone-visible failure, it just delays that
session's appearance in the SQL view until you fix the parser and re-run it.

### 2.3 SQLite schema (parsed / queryable layer)

One `sessions` table with the well-known scalar fields from STORAGE.md's frontmatter,
plus a normalized `sets` table for per-exercise data (better for weekly aggregate queries
than a JSON blob column):

```sql
CREATE TABLE sessions (
  id                TEXT PRIMARY KEY,     -- 8-hex session id
  date              TEXT NOT NULL,
  routine_id        TEXT,
  routine_name      TEXT,
  started_at        TEXT,
  completed_at      TEXT,
  total_tonnage     REAL,
  session_calories  REAL,
  session_trimp     REAL,
  vo2max            REAL,
  ecg_beats         INTEGER,
  ecg_duration_sec  INTEGER,
  ecg_session_rmssd REAL,
  ecg_pac_count     INTEGER,
  ecg_pause_count   INTEGER,
  ecg_irregular_beats INTEGER,
  afib_suspicion_episodes INTEGER,
  sdnn              REAL,
  pnn50             REAL,
  poincare_sd1      REAL,
  poincare_sd2      REAL,
  poincare_ratio    REAL,
  cardiac_drift_bpm_min REAL,
  resting_hr        INTEGER,
  hrr60s            INTEGER,
  content_hash      TEXT NOT NULL,
  raw_path          TEXT NOT NULL,        -- relative path under raw/, for re-parse / audit
  received_at       TEXT NOT NULL,
  schema_version     INTEGER NOT NULL      -- see §2.4
);

CREATE TABLE exercise_sets (
  session_id   TEXT NOT NULL REFERENCES sessions(id),
  exercise_id  TEXT NOT NULL,
  exercise_name TEXT,
  bodypart     TEXT,
  set_index    INTEGER NOT NULL,
  reps         INTEGER,
  weight       REAL,
  exclude_from_tonnage INTEGER,   -- 0/1
  is_daily     INTEGER            -- 0/1
);

CREATE TABLE tonnage_by_bodypart (
  session_id TEXT NOT NULL REFERENCES sessions(id),
  bodypart   TEXT NOT NULL,
  tonnage    REAL NOT NULL
);
```

Unknown/new frontmatter fields the parser doesn't yet recognize are simply not written to
a column — they're still safe in the raw file (§2.2 step 6) and get backfilled once the
parser and a matching column are added (§2.4). This is the server-side mirror of the
`WorkoutParser` convention already documented in CONVENTIONS.md ("reader defaults missing
numeric fields to 0 ... so newly omitted fields round-trip cleanly") — same tolerance
philosophy, applied in the other direction.

### 2.4 `schema_version` and re-parse workflow

`schema_version` is an integer the parser code stamps on every row it writes, bumped
whenever the parser's field set changes. This gives you:

```sql
-- "which raw files were parsed by an older version of my parser?"
SELECT raw_path FROM sessions WHERE schema_version < 3;
```

A small `reparse.py` maintenance script: given a list of `raw_path`s (or "all"), re-reads
the raw file from disk, re-runs the *current* parser, and upserts — the exact same code
path as step 7 of the live receiver, factored into a shared function so there is only one
parser implementation, called from two entry points (live receiver, batch reparse). This
is the concrete mechanism for "the app's format changed, catch the server up": edit the
parser once, run `reparse.py --all`, done — no phone involvement, because the raw files
already have everything.

### 2.5 Weekly analysis job

Out of scope for this document's detail (per your "the details is not important" framing
at the start) beyond noting the shape: a cron-scheduled script (`cron` or systemd timer,
your choice) that queries SQLite, produces whatever report/output you want, running
entirely server-side against the `sessions`/`exercise_sets` tables. Because it's decoupled
from ingestion, iterating on the analysis logic never touches the phone or the receiver.

---

## Part 3 — Reliability properties (why this survives real-world flakiness)

### 3.1 Phone-side crash / kill mid-send
Ledger entry stays `PENDING` (only flipped to `SENT` after a confirmed 2xx). Next
WorkManager run retries. No partial state possible because the ledger write happens after
the HTTP response, not before the request.

### 3.2 Server down / unreachable (off tailnet, box rebooting, etc.)
WorkManager's own retry/backoff handles this without custom code — request fails, entry
stays retryable, periodic worker keeps trying. Nothing is lost; delivery is delayed, not
dropped. This is the main reason a local durable queue was chosen over "just try to send
and shrug on failure."

This is not just a theoretical case: the server has been intermittently offline for
extended stretches (multi-day) in practice while its own analysis/GUI side is built out on
a dev machine rather than run continuously. All three sync pipelines (sessions, readiness,
scale) have their own 4-hourly periodic durability net for exactly this — WorkManager's
backoff between retries is capped around 5 hours regardless of the requested interval, so
in the worst case a queued item gets retried roughly every few hours indefinitely, never
giving up, for as long as the server stays down. `AppLogger` records every attempt
(success and failure) so a multi-day gap can be reviewed after the fact — see
`adb shell run-as com.mygymapp cat files/gymdata/logs/app.log`, filtering for `SyncWorker`
/ `ReadinessSyncWorker` / `ScaleWeighInSyncWorker` — and "Invia tutti i dati in coda" in
Options exists specifically to force a fresh attempt across all three once the server is
confirmed back up, rather than waiting for the next periodic tick.

### 3.3 Retry produces a duplicate delivery
Content-hash idempotency check (§2.2 step 4) makes a duplicate POST a safe no-op
server-side. The phone doesn't need exactly-once semantics on its end — at-least-once
delivery + idempotent receiver = effectively-exactly-once storage.

### 3.4 Session edited after being marked `SENT`
Rare today (rename-sync rewrites are the main case) but handled: content hash mismatch on
next check flips the ledger entry back to `PENDING`; server-side, a resend with a new hash
for a known `sessionId` is treated as an update (§2.2 step 5), not a duplicate rejection.

### 3.5 Schema drift (the original concern that shaped this whole design)
- Adding a new frontmatter field on the phone requires **zero** sync-code changes — the
  raw bytes are sent as-is regardless of what fields exist.
- The server's SQLite view of that new field lags until the parser is updated, but nothing
  is lost or blocked in the meantime (§2.2's split of raw-write vs parse).
- `schema_version` + `reparse.py` (§2.4) is the explicit, deliberate step for catching the
  SQLite layer up — done once, on your schedule, not silently or automatically.
- The "Resync all" button (§1.5) exists for the rare case you actually want the *phone* to
  re-send everything (e.g. you wiped the server's raw store and need to rebuild it from
  the phone's `history/`), which is a different scenario from a schema catch-up (that only
  needs `reparse.py`, no phone involvement).

### 3.6 Partial multipart upload / network drop mid-transfer
Server-side hash check (§2.2 step 3) rejects a truncated/corrupted body before it's ever
written to disk or SQLite. The phone sees a 422, ledger entry stays `FAILED`/retryable,
next attempt re-sends the whole file cleanly.

---

## Part 4 — Tailscale specifics

### 4.1 Serve, not Funnel
The phone runs the Tailscale Android app and is joined to the same tailnet as the server —
confirmed. That makes this a **tailnet-internal** connection between two peers, which is
exactly what **Tailscale Serve** is for: `tailscale serve https / http://localhost:<port>`
exposes the local FastAPI service at `https://<server-hostname>.<tailnet-name>.ts.net`
*only* to other devices on the tailnet, with a real (Let's Encrypt via Tailscale's
integration) TLS cert — no self-signed cert to pin, no public internet exposure at all.

**Tailscale Funnel** is the wrong tool here — it exposes the endpoint to the public
internet through Tailscale's relay. There is no reason to do that for a phone that is
itself always a tailnet member; using Funnel would trade "private by construction" for
"public, defended by a bearer token" for zero benefit. Do not use Funnel for this feature.
(If a future need arises for delivery from a device *not* on the tailnet — e.g. someday
wanting a non-Tailscale device to submit data — that would be the moment to reconsider
Funnel, with the bearer-token auth already in place from §1.4/§2.2 as the defense-in-depth
layer that setup would lean on.)

### 4.2 Server setup checklist
1. `tailscale up` on the server (already presumably done, self-hosted box on the tailnet).
2. Run the FastAPI receiver bound to `localhost:<port>` (not `0.0.0.0` — Serve reverse-
   proxies to localhost, no need to expose the port itself beyond loopback).
3. `tailscale serve --bg https / http://localhost:<port>` — persists across reboots with
   `--bg`; check `tailscale serve status` to confirm the mapping.
4. Note the resulting hostname (`tailscale status` or `tailscale serve status` shows it,
   form `https://<machine-name>.<tailnet-name>.ts.net`) — this is the value that goes into
   the phone's Server URL setting (§1.5).
5. Generate the bearer token (e.g. `openssl rand -hex 32`), put it in the server's config
   (env var, not hardcoded) and the phone's Bearer token setting.
6. Confirm MagicDNS is enabled on the tailnet (Tailscale admin console → DNS) — required
   for the `.ts.net` hostname to resolve; it's on by default for most tailnets.

### 4.3 What Tailscale is/isn't doing for you here
- Tailscale (WireGuard under the hood) already encrypts the transport between phone and
  server — the `https://` on top (via Serve's cert) is defense-in-depth / lets you use
  normal HTTP client code without disabling cert validation, not the thing actually
  securing the link.
- Tailscale does **not** authenticate the *app* — any device on your tailnet could in
  principle hit the endpoint. The bearer token (§1.4/§2.2) is what scopes "only MyGymApp,
  not some other tailnet peer, may POST sessions" — keep it even though the transport is
  already private. Cheap insurance, and it's the same token that'd matter if Funnel were
  ever turned on later.

---

## Second record type: readiness events

Implemented (phone side) as a second, independent record type — a real instance of the
pattern anticipated below, built as a **separate** ledger/worker/API rather than
generalizing the session sync code, specifically so the already-verified session path
could not regress. See CHANGELOG.md for the phase this landed in.

### What it is

`PolarManager.finishReadinessMeasurement()` computes a 60s HRV/resting-HR readiness
reading (see POLAR.md) but historically only held it in the `readinessResult` StateFlow
for the UI — never written to disk. Now persisted immediately as its own file,
`gymdata/readiness/{id}.md`:

```yaml
---
id: a1b2c3d4
measuredAt: "2026-08-05T07:04:10.123"
readiness: "GOOD"              # Readiness enum name: MEASURING|DELOAD_RECOMMENDED|LIGHT_DAY|NORMAL|GOOD|PEAK|NO_BASELINE
lnRmssd: 4.30
restingHr: 65
vo2max: 45.2
recommendation: "HRV above baseline. Good day to push intensity."
stepsAvgPerDay: 8214.5         # nullable — see "Daily step average" below
stepsDaysSpanned: 1            # nullable — always present together with stepsAvgPerDay
stepsPreviousDay: 9037         # nullable — yesterday's full calendar-day total (what the UI shows)
---
```

Only persisted (and thus only synced) for measurements that produce a real result —
the early-return case (`cleanRR.size < 20`, "not enough clean data, try again") is
discarded, never written, since it's a failed measurement, not a data point.

#### Daily step average

Piggybacks on the readiness event rather than being its own record type, because the
morning readiness test is already the app's one guaranteed daily touchpoint — see
`StepLedgerRepository` (`data/steps/`).

**Reads through Health Connect, not the raw `TYPE_STEP_COUNTER` sensor.** The first
implementation used `SensorManager`/`TYPE_STEP_COUNTER` directly (a plain
`ACTIVITY_RECOGNITION` runtime permission, one-shot or persistent listener). That was
abandoned after real-device testing: on this project's Samsung/One UI test phone,
`dumpsys sensorservice` showed the hardware sensor reporting `has sensor access: false` for
the app even with `ACTIVITY_RECOGNITION` granted and a listener held open continuously. The
actual gate turned out to be a separate OS-level "Health, fitness and wellness" permission
with **no manual toggle reachable from Settings** — Health Connect's own permission-request
flow is the only way to grant it. This is very likely not Samsung-specific (modern Android
increasingly routes health-adjacent sensor data through Health Connect regardless of OEM),
so `HealthConnectStepsReader` (`data/steps/`) is the permanent design, not a workaround.
Requires `androidx.health.connect:connect-client` (bumped `agp` to 8.9.3 in
`libs.versions.toml` — 1.1.0 of that library requires AGP 8.9.1+) and the
`android.permission.health.READ_STEPS` manifest permission, requested at runtime via
`PermissionController.createRequestPermissionResultContract()` (a Health Connect-specific
contract, not `ActivityResultContracts.RequestPermission()`).

Health Connect aggregates over an explicit time range natively
(`HealthConnectClient.aggregate(AggregateRequest(...))`), so unlike a raw cumulative-since-
boot sensor value, there's no manual "diff two counter readings, handle the counter going
backwards on reboot" logic needed — `StepLedgerRepository` only remembers *when* steps were
last read (an `Instant`), not a counter value:

- Each readiness test asks Health Connect for the step total between the last saved
  checkpoint and now (`gymdata/_sync/step_checkpoint.yml`, local-only, never synced), then
  advances the checkpoint to now. On the very first read ever (no checkpoint saved yet —
  e.g. the day the permission is first granted), there's no "previous" instant to diff
  against, but Health Connect still has real historical data from before the app had
  permission to read it — so this case queries the last 24h directly (a real time range,
  not a diff) instead of returning nothing and making day one look broken.
- `stepsAvgPerDay` = (steps in that range) / (days elapsed since the previous test, or 1 on
  the first-ever read). On the common path — a test done every morning — that's 1 day, i.e.
  a true daily count. If a day (or several) was skipped, the same total gets divided across
  however many days actually elapsed, so the number is an **average**, not a guaranteed
  single-day count.
- `stepsDaysSpanned` says which case applies: `1` means the value is a real single-day
  reading; anything greater means N days were collapsed into one average. The app itself
  only ever shows/stores the single averaged number, but the server receives both fields
  because it has the cross-day history to decide how to weight, flag, or chart a
  multi-day-average point differently from a clean single-day one — that distinction would
  be unrecoverable if only the averaged value were sent.
- `stepsPreviousDay` is a *separate* read, not derived from the checkpoint at all:
  yesterday's complete calendar day in the device's local timezone (midnight to midnight,
  `HealthConnectStepsReader.previousDayTotal()`). This is the number the readiness box on
  the Heart Rate screen displays, because a whole day is comparable day to day, whereas
  `stepsAvgPerDay` shifts with whatever time the test happened to be taken. The
  checkpoint-derived fields are still computed and synced — the server has the history to
  use them — they're just no longer what the user sees.
- Both checkpoint fields are `null` together — never `0`/`1` as a fallback — whenever Health Connect
  itself can't answer: not installed on this device, permission not granted, or the query
  failing. A `null` here means "no data", not "zero steps" — the server must not coerce it
  to `0`.

### Sync path

Mirrors the session sync design (§1.1–§1.4) with dedicated classes rather than shared
ones:

| Session sync | Readiness sync |
|---|---|
| `SyncLedgerRepository` (`_sync/state.yml`) | `ReadinessLedgerRepository` (`_sync/readiness_state.yml`) |
| `SyncApi` (`POST /v1/sessions`) | `ReadinessSyncApi` (`POST /v1/readiness`) |
| `SyncWorker` | `ReadinessSyncWorker` |
| Enqueued in `ActiveRoutineViewModel.registerRoutine()` | Enqueued in `PolarManager.finishReadinessMeasurement()` |

Trigger is immediate, not batched with a session: readiness fires once per HR connect
(60s after pairing), independent of whether the user goes on to complete a workout that
day — this is the point of keeping it separate from session sync, which only fires at
workout end.

Same 4-hourly periodic durability net as sessions (`ReadinessSyncWorker.Scheduler.ensurePeriodic()`,
scheduled at app start alongside the other two in `MyGymApp.onCreate()`). Originally
shipped without one on the reasoning that a missed send would just wait for the next HR
connect — but the self-hosted server is expected to be offline for days at a stretch while
its own analysis/GUI side is still being built, so the periodic net matters here just as
much as it does for sessions: it's what keeps retrying regularly even if the app is never
force-killed-and-relaunched (which would otherwise be the only other trigger for a stuck
expedited work item).

Same `isEnabled()` scoping rule as sessions (§1.5): gates the automatic enqueue in
`PolarManager` only, not whether `ReadinessSyncWorker` drains what's already queued. No
"resync all readiness" UI action exists yet (unlike sessions) — add one the same way if
backfilling old readiness events is ever needed; today only events measured after this
feature shipped exist to backfill anyway.

### Server-side spec

The wire format and server implementation follow the exact same principles as
`POST /v1/sessions` (raw-first, content-hash idempotent, `parse_failures` on parser
error). A companion spec for the server repo (`MyGymApp_server`, mirroring
`docs/sync-ingestion/SPEC.md`'s structure there) should be written before implementing
`/v1/readiness` — same multipart envelope shape (`eventId` instead of `sessionId`, no
`exercises`/`sets` child tables needed, just a flat `readiness_events` table mirroring the
YAML fields above.

The multipart `envelope` JSON part additionally carries `stepsAvgPerDay` (number or
JSON `null`), `stepsDaysSpanned` (integer or JSON `null`) and `stepsPreviousDay`
(integer or JSON `null`) — duplicated from the
attached file's frontmatter so the server can validate/store them without parsing
Markdown first, same reasoning as every other envelope field. Both keys are always
*present*, holding JSON `null` rather than being omitted, when there was nothing to
diff against on the phone (see "Daily step average" above) — the server-side column(s)
must be nullable and a `null` must be stored/treated as "no data for this event", never
coerced to `0`. `readiness_events` needs three new nullable columns for this:
`steps_avg_per_day` (float/numeric), `steps_days_spanned` (integer) and
`steps_previous_day` (integer).

## Third record type: scale weigh-ins

Implemented (phone side), following the exact same dedicated-classes pattern as readiness.

### What it is

[BleScaleManager.maybeSaveWeighIn()](../app/src/main/java/com/mygymapp/data/scale/BleScaleManager.kt)
already persists each VitaFit VT701 weigh-in via
[ScaleHistoryRepository](../app/src/main/java/com/mygymapp/data/repository/ScaleHistoryRepository.kt)
(`gymdata/scale/YYYY/MM/{date}.md` — see STORAGE.md). Now also enqueues and sends it
immediately after that save, same as readiness.

One difference from sessions/readiness: a weigh-in's ID is an **ISO date string**
(`2026-08-05`), not an 8-hex UUID — `ScaleHistoryRepository` saves one weigh-in per
calendar day, overwriting same-day re-weighs. This flows through unchanged: a same-day
re-weigh naturally produces a content-hash change for a known ID, which the existing
"different hash for known ID = update, not duplicate" rule (§2.2 step 5) already handles
correctly without any special-casing.

### Sync path

| Sessions | Readiness | Scale weigh-ins |
|---|---|---|
| `SyncLedgerRepository` (`_sync/state.yml`) | `ReadinessLedgerRepository` (`_sync/readiness_state.yml`) | `ScaleWeighInLedgerRepository` (`_sync/scale_state.yml`) |
| `SyncApi` (`POST /v1/sessions`) | `ReadinessSyncApi` (`POST /v1/readiness`) | `ScaleWeighInSyncApi` (`POST /v1/scale-weighins`) |
| `SyncWorker` | `ReadinessSyncWorker` | `ScaleWeighInSyncWorker` |
| `ActiveRoutineViewModel.registerRoutine()` | `PolarManager.finishReadinessMeasurement()` | `BleScaleManager.maybeSaveWeighIn()` |

Same 4-hourly periodic durability net as sessions and readiness
(`ScaleWeighInSyncWorker.Scheduler.ensurePeriodic()`) — same reasoning: the server is
expected offline for extended stretches, so all three record types need a net that keeps
retrying on its own rather than depending on the next natural trigger (a workout, an HR
connect, a weigh-in) to notice a stuck send.

### Server-side spec

A companion spec (`docs/sync-ingestion/SCALE_SPEC.md` in `MyGymApp_server`, same structure
as the session and readiness specs) covers `POST /v1/scale-weighins` and the
`scale_weighins` SQLite table.

## Fourth record type: raw ECG

Implemented (phone side), following the same dedicated-classes pattern as the other three
— but with one structural difference from all of them that's worth calling out up front.

### What it is

Historically, the app ran the full ECG post-session analysis (Pan-Tompkins QRS detection,
arrhythmia markers, HRV metrics — see [POLAR.md](POLAR.md#post-session-analysis)) entirely
on the phone, wrote the 14 derived metrics into the session YAML, and **deleted the raw
`ecg/{sessionId}.ecg` file** once analysis succeeded. This record type instead uploads the
raw waveform to the server, so the server can run a heavier/more accurate analysis (more
CPU than a phone, potentially ML/LLM-assisted interpretation, and comparison against the
user's full history rather than one isolated session) — see
[POLAR.md](POLAR.md#post-session-analysis) for what stays local vs. moves server-side.

The phone still runs its own lightweight analysis unchanged (for the immediate at-a-glance
metrics shown in the app and stored in the session YAML) — this is purely an *additional*
upload of the raw bytes behind those metrics, not a replacement of the local analysis.

### The structural difference: an ephemeral, not permanent, source file

Sessions/readiness/scale all sync a file that is a **permanent local record** — it's never
deleted, so a failed or delayed sync just means "not yet delivered," and the file is always
there to retry from, indefinitely, no matter how long the server stays down.

The raw `.ecg` file is different: it was already ephemeral before sync existed (deleted
right after local analysis), and it still needs to be, since 130Hz raw waveform data adds
up (~940 KB/session uncompressed) in a way YAML frontmatter doesn't. So this pipeline
changes **when** the file is deleted, not whether:

- **Before this feature**: deleted immediately once local analysis succeeded
  (`ecgResult.hasAnything`), kept only if analysis failed.
- **Now**: deleted only after `EcgSyncWorker` confirms a `SENT` upload. If sync isn't
  configured/enabled, the old immediate-delete-on-success behavior is the fallback (so a
  phone with no server configured doesn't accumulate `.ecg` files with nothing to ever
  drain them).
- **New: a 30-day age cap.** `EcgSyncLedgerRepository.expireStale()` marks any
  `PENDING`/`FAILED` entry older than 30 days as `EXPIRED` and deletes its file — a
  deliberate departure from the other three pipelines' "never give up, retry forever"
  philosophy (docs/SYNC.md §3.2). That philosophy only works when the source file is
  permanent; here it would mean unbounded local storage growth during an extended outage
  (the server has been observed offline for multi-day stretches in practice). Once expired,
  that session's raw ECG is unrecoverable — only the phone-computed summary metrics survive
  (in the session YAML, synced separately via `/v1/sessions`).

### Sync path

| Sessions | Readiness | Scale weigh-ins | ECG |
|---|---|---|---|
| `SyncLedgerRepository` (`_sync/state.yml`) | `ReadinessLedgerRepository` (`_sync/readiness_state.yml`) | `ScaleWeighInLedgerRepository` (`_sync/scale_state.yml`) | `EcgSyncLedgerRepository` (`_sync/ecg_state.yml`) |
| `SyncApi` (`POST /v1/sessions`) | `ReadinessSyncApi` (`POST /v1/readiness`) | `ScaleWeighInSyncApi` (`POST /v1/scale-weighins`) | `EcgSyncApi` (`POST /v1/ecg`) |
| `SyncWorker` | `ReadinessSyncWorker` | `ScaleWeighInSyncWorker` | `EcgSyncWorker` |
| `ActiveRoutineViewModel.registerRoutine()` | `PolarManager.finishReadinessMeasurement()` | `BleScaleManager.maybeSaveWeighIn()` | `ActiveRoutineViewModel.registerRoutine()` (enqueue only — deletion moved to `EcgSyncWorker`) |

Same 4-hourly periodic durability net as the other three
(`EcgSyncWorker.Scheduler.ensurePeriodic()`, scheduled alongside the others in
`MyGymApp.onCreate()`).

Two differences from the shared `SyncLedgerEntry`/multipart pattern:

- **Gzip compression**: the file is gzip-compressed before upload (binary Int16 sample
  streams compress well) — `EcgSyncApi.postEcg()` sends `application/gzip`, not
  `text/markdown`, and `contentHash` is computed over the *compressed* bytes, checked
  against what the server actually receives before it decompresses. The ledger's
  `enqueue()`-time hash (computed over the raw file, since `ActiveRoutineViewModel` only
  has the raw bytes at that point) is provisional — `EcgSyncWorker` recomputes the real hash
  over the compressed bytes it transmits.
- **Longer HTTP timeouts** (`writeTimeout` 90s / `readTimeout` 60s vs. 30s/30s for the
  other three) — a compressed ~130Hz ECG stream is still larger than a session/readiness/
  weigh-in YAML file even after compression.

Same `isEnabled()` scoping rule as the other three (§1.5): gates the automatic enqueue only,
not whether `EcgSyncWorker` drains what's already queued.

"Invia tutti i dati in coda" also resends any `.ecg` file still present in `gymdata/ecg/`
— but unlike the other three, this is not a true backfill: once a file has been uploaded
and deleted, there's nothing left on the phone to resend. Only files that are still
pending/failed/not-yet-queued get picked up.

**Debug send**: Options' debug section has a "Registra e invia ECG di debug" button
(`OptionsViewModel.sendDebugEcg()`) that records ~10s of ECG from the connected Polar
device using a synthetic `debug-{epoch millis}` id instead of a real session id, then
enqueues it through this exact same pipeline — a manual way to exercise `POST /v1/ecg`
end to end without a full workout session. Requires a connected Polar device and a
configured sync server. See the server-side spec below for how to tell these apart from
genuine session recordings.

### Server-side spec

A companion spec (`docs/sync-ingestion/ECG_SPEC.md` in `MyGymApp_server`) covers
`POST /v1/ecg`, the binary `.ecg` format (documented in
[STORAGE.md](STORAGE.md#raw-ecg-ecgsessionidecg)), and the new `ecg_recordings` SQLite
table. Unlike the other three record types, the server's raw store becomes the **only**
copy of the waveform once the phone deletes its local file — server-side backup/retention
of `raw/ecg/` matters more here than for sessions/readiness/scale, which all keep the phone
as a permanent secondary copy.

### Cardio blocks — deriving ECG segments without touching the binary format

`ExerciseType.CARDIO` (see [POLAR.md](POLAR.md#cardio-blocks)) lets a session mark one or
more explicit cardio intervals via `ExerciseSet.Cardio.startedAt`/`endedAt` (absolute ISO
`LocalDateTime`, per block — see [STORAGE.md](STORAGE.md) for the YAML shape). This adds
**zero** change to the raw ECG pipeline described above: the `.ecg` file is still one
continuous stream per session, sent exactly as before.

Instead, the server derives per-block sample ranges after the fact, once it has received
*both* record types for the same `sessionId` (order doesn't matter — the existing
upsert-by-id/update-by-hash logic in §2.2 already tolerates either arriving first):

```
startSample = (block.startedAt - ecg.startTimestamp) * ecg.sampleRate
endSample   = (block.endedAt   - ecg.startTimestamp) * ecg.sampleRate
```

where `ecg.startTimestamp`/`ecg.sampleRate` come from the `.ecg` file's own header (see
[STORAGE.md](STORAGE.md#raw-ecg-ecgsessionidecg)) and `block.startedAt`/`block.endedAt` come
from the synced session YAML's `exercises[].sets[]` for that `CARDIO` exercise. Using
absolute timestamps rather than a phone-computed sample offset avoids compounding any drift
between the ECG stream's nominal vs. actual sample rate over a long recording.

This is documentation only on the phone side — the actual slicing/tagging logic belongs in
`MyGymApp_server`'s `ECG_SPEC.md`/analysis code, to be coordinated there, the same way
readiness/scale/ECG each got a companion spec above.

---

## Implementation status

Phone side (this repo, package `data/sync/`):
- [x] `SyncConfigRepository.kt` — server URL / bearer token / enabled flag, SharedPreferences (`sync_config`)
- [x] `SyncLedgerEntry.kt` / `SyncLedgerRepository.kt` — ledger read/write (`gymdata/_sync/state.yml`)
- [x] `SyncApi.kt` — OkHttp client for the multipart POST + `/health` check
- [x] `SyncWorker.kt` (`@HiltWorker`) + `SyncWorker.Scheduler` — expedited one-off on
      enqueue, periodic (4h) durability net, both via WorkManager with exponential backoff
- [x] `MyGymApp` is now a `Configuration.Provider` (`HiltWorkerFactory`); WorkManager's
      default `androidx.startup` initializer is disabled in the manifest so Hilt can
      construct `SyncWorker` — required whenever a `@HiltWorker` is introduced.
- [x] Hook into `ActiveRoutineViewModel.registerRoutine()` — enqueues right after the
      final `workoutRepository.save(updated)`, then triggers an expedited `SyncWorker` run.
      Never inline/blocking — same discipline as `completionSaved`/`onCleared()` save.
- [x] Hook into `WorkoutRepository.updateExerciseNameInHistory` /
      `updateRoutineNameInHistory` — `syncLedgerRepository.requeueIfChanged()` after each
      rewritten session file, so a rename-triggered edit gets resynced (§3.4).
- [x] `OptionsScreen`/`OptionsViewModel` additions — server URL + token fields, enabled
      switch, "Verifica connessione" (health check), pending-count/last-sync status line,
      "Rinvia tutte le sessioni" (resync-all, backed by
      `WorkoutRepository.getAllCompletedSessions()`).
- [x] `STORAGE.md`'s SharedPreferences table updated with `sync_config`; new
      `gymdata/_sync/` directory documented in the root layout.
- [x] Session sync verified end-to-end against the live Tailscale server, including a
      58-session backfill via "Resync all" — see CHANGELOG.md for the debugging session
      that found and fixed the DNS/ACL/port issues, a ledger YAML crash, and a stale
      status-line bug along the way.
- [x] Readiness events: `ReadinessEvent.kt`/`ReadinessRepository.kt` (persist),
      `ReadinessLedgerRepository.kt`/`ReadinessSyncApi.kt`/`ReadinessSyncWorker.kt` (sync),
      hooked into `PolarManager.finishReadinessMeasurement()`. Build-verified; not yet
      tested against a live HR connect + server round-trip.
- [x] Scale weigh-ins: `ScaleWeighInLedgerRepository.kt`/`ScaleWeighInSyncApi.kt`/
      `ScaleWeighInSyncWorker.kt`, hooked into `BleScaleManager.maybeSaveWeighIn()`.
      Build-verified; not yet tested against a live weigh-in + server round-trip.
- [x] All three sync workers (sessions/readiness/scale) now have a matching 4-hourly
      periodic durability net (`ensurePeriodic()`, scheduled together in
      `MyGymApp.onCreate()`) — added once it became clear the server would be offline for
      multi-day stretches during its own development, not just brief outages.
- [x] "Invia tutti i dati in coda" (Options) now backfills and resends all four record
      types in one action — `OptionsViewModel.resyncAll()` re-enqueues every session,
      readiness event, scale weigh-in, and still-on-disk `.ecg` file regardless of prior
      status, and the pending-count status line sums all four ledgers.
- [x] Raw ECG: `EcgSyncLedgerRepository.kt` (`_sync/ecg_state.yml`, adds `enqueuedAt` +
      `expireStale()` 30-day cap not present in the other ledgers)/`EcgSyncApi.kt`
      (gzip, longer timeouts, `POST /v1/ecg`)/`EcgSyncWorker.kt` (drains ledger, gzips,
      deletes the raw file only after confirmed `SENT`), hooked into
      `ActiveRoutineViewModel.registerRoutine()` (enqueue) — deletion is no longer
      triggered there, it moved into `EcgSyncWorker`. `PolarManager.ecgFileFor()` added
      as a thin wrapper. Build-verified; not yet tested against a live session + server
      round-trip.
- [ ] Server side for readiness (`/v1/readiness`), scale weigh-ins
      (`/v1/scale-weighins`), and ECG (`/v1/ecg`) — specs written (`READINESS_SPEC.md`,
      `SCALE_SPEC.md`, `ECG_SPEC.md` in `MyGymApp_server/docs/sync-ingestion/`), currently
      being implemented.

New Gradle dependencies added: `com.squareup.okhttp3:okhttp`, `androidx.work:work-runtime-ktx`,
`androidx.hilt:hilt-work` (+ `hilt-compiler` via KSP). `buildFeatures.buildConfig = true`
enabled (needed for `BuildConfig.VERSION_NAME` in the sync envelope's `appVersion` field).
