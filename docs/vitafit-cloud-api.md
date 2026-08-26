# VitaFit cloud API (undocumented, discovered by MITM)

MyGymApp talks to the VitaFit VT701 scale directly over BLE (see [`data/scale/`](../app/src/main/java/com/mygymapp/data/scale/), merged to master in commit `893d86e`) and does not depend on the official VitaFit app for new readings. This document is about a different, one-off need: **importing weigh-in history that predates MyGymApp's own BLE integration**, which only exists in VitaFit's cloud account, not on the phone or in the scale itself.

The official VitaFit app (`com.vt.vitafit`) is cloud-first — `adb backup` of its private data directory comes back empty (its `BackupAgent` doesn't implement anything meaningful despite `ALLOW_BACKUP` being set), and decompiling the APK confirms it stores measurements via a REST API (`com.vt.vitafit.core.models.entities.weighing.*`) rather than a local Room/SQLite DB. The only local DB found (`mm003.db`, from the bundled `vtble-scale-sdk-android-v4.2.6` SDK) is just BLE device pairing metadata, not measurement history.

The API below was found by MITM-proxying the app's own HTTPS traffic (mitmproxy + a user-installed CA cert, on the account owner's own phone and own VitaFit account) — not from any public documentation or third-party repo. A GitHub search at the time turned up [`prabhjotsbhatia-ca/vitafit_body_fat_scale`](https://github.com/prabhjotsbhatia-ca/vitafit_body_fat_scale) (BLE-only Home Assistant integration, no cloud API) and the already-referenced `etekcity_esf551_ble` (BIA formulas for a different scale) — neither covers this endpoint.

## Endpoint

```
GET https://vitafit-api-eu.66vitafit.com/front/profile/{profileId}/body_index_per_day/
    ?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD
```

- `profileId` — one of the `id` fields under `details.profiles[]` from `GET /front/user/info/` (a VitaFit account can have multiple profiles, e.g. family members sharing one scale).
- The date range is inclusive and can span the account's entire history in one call (tested with a 6-year range) — no need to page day by day even though the official app's UI does so.
- Response `details[]` is one entry per **day that has data** (no entry for empty days), each with a `data[]` array of individual weigh-ins for that day (there can be more than one, e.g. re-weighing to get a stable BIA reading).

### Auth

Header-based, not OAuth/JWT:

```
token: <opaque per-account token, e.g. 67460cc9d7d3c9e180d07e4c>
timeZone: GMT+01:00
```

The token was read directly off a live request in the MITM capture; no login flow was reverse-engineered. It appears to be long-lived (worked across the whole capture session and in later ad-hoc `curl` calls).

### Response shape (per weigh-in)

```json
{
  "created": "Fri, 01 May 2026 04:39:38 GMT",
  "created_ts": 1777610378,
  "id": "69f42e8a7cfb503e17a64442",
  "measure_user_info": { "age": 27, "category": 3, "gender": 2, "height": 170 },
  "values": [
    { "type": "weight", "value": 80.95 },
    { "type": "bmi", "value": 28.01 },
    { "type": "fat_content", "value": 21.42 },
    { "type": "muscle_content", "value": 74.6 },
    { "type": "muscle_weight", "value": 60.38 },
    { "type": "water_content", "value": 49.11 },
    { "type": "bone_content", "value": 3.23 },
    { "type": "visceral_fat_content", "value": 11.0 },
    { "type": "calorie", "value": 1803.0 },
    { "type": "protein", "value": 25.49 },
    { "type": "body_age", "value": 22.0 }
    // ... more VitaFit-computed fields (score, body_shape, desirable_weight, etc.)
  ]
}
```

`values` is a flat array of `{type, value}` rather than fixed fields — iterate and pick by `type`. Most entries also carry a `range` array (VitaFit's own low/normal/high bucket boundaries for that metric); unused for import.

## Mapping to MyGymApp's `ScaleWeighIn`

MyGymApp only tracks 4 fields ([`ScaleWeighIn.kt`](../app/src/main/java/com/mygymapp/data/model/ScaleWeighIn.kt)) and one record per **day** ([`ScaleHistoryRepository.kt`](../app/src/main/java/com/mygymapp/data/repository/ScaleHistoryRepository.kt) — `id` is the ISO date, saving again same-day overwrites). VitaFit's per-weigh-in granularity was collapsed to one-per-day (last weigh-in of the day wins) before writing.

| MyGymApp field | VitaFit `values[].type` | Note |
|---|---|---|
| `weightKg` | `weight` | direct |
| `bmi` | `bmi` | direct |
| `bodyFatPercent` | `fat_content` | direct |
| `leanMassPercent` | `muscle_content` | **not an exact match** — VitaFit has no "lean mass %" field; `muscle_content` (% skeletal muscle) is the closest available proxy. Expect it to read differently from whatever "lean mass" meant in another tool. |

Fields VitaFit has that MyGymApp doesn't model at all and were dropped on import: `visceral_fat_content`, `water_content`, `bone_content`, `calorie` (BMR), `protein`, `body_age`, `score`, `body_shape`, `desirable_weight`.

## Redoing this (e.g. for another profile on the account)

1. Get the account's per-account `token` (MITM the app once, or reuse a previously captured one if still valid) and the target `profileId` from `/front/user/info/`.
2. `curl -H "token: ..." -H "timeZone: GMT+01:00" "https://vitafit-api-eu.66vitafit.com/front/profile/{id}/body_index_per_day/?start_date=2000-01-01&end_date=<today>"`
3. Flatten `details[].data[]`, keep the last entry per calendar day, map fields per the table above, write one `.md` per day under `gymdata/scale/YYYY/MM/` in the format `ScaleWeighInParser.toMarkdown` produces.
4. Only write days MyGymApp doesn't already have data for — check `gymdata/scale/` first, don't overwrite existing BLE-sourced weigh-ins.

This was done as a one-off manual import (2026-08-09); there is no in-app "Import from VitaFit" feature and none is planned — the token has no refresh flow reverse-engineered and the endpoint is undocumented/unofficial, so it's not something to depend on for anything ongoing.
