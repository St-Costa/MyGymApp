#!/usr/bin/env bash
# Copy the app's diagnostics (gymdata/logs/: app.log + crashes/) from the connected phone
# to backups/logs/<timestamp>/ so they can be read on this machine (by you or by Claude).
# Read-only on the device: it never installs, clears or writes anything there.
#
# Needs a *debuggable* build installed (run-as). With a release build, share the files from
# the app instead: Options → Debug → LOGS & CRASHES → open → share.
# See docs/CONVENTIONS.md#crash-reports--action-trail.
#
# Usage: scripts/pull-logs.sh
set -euo pipefail

cd "$(dirname "$0")/.."

APP_ID="com.mygymapp"
OUT_ROOT="backups/logs"

if ! adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
  echo "ERROR: no device in 'device' state. Is it connected and authorized (adb devices)?" >&2
  exit 1
fi
if [ -z "${ANDROID_SERIAL:-}" ] && [ "$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)" -gt 1 ]; then
  echo "ERROR: more than one device connected — re-run with ANDROID_SERIAL=<serial>:" >&2
  adb devices -l >&2
  exit 1
fi

if ! adb shell run-as "$APP_ID" ls files/gymdata/logs >/dev/null 2>&1; then
  echo "ERROR: cannot read $APP_ID's files (not a debuggable build, or no logs yet)." >&2
  echo "       Share them from the app: Options → Debug → LOGS & CRASHES." >&2
  exit 1
fi

out="$OUT_ROOT/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
adb exec-out run-as "$APP_ID" tar -C files/gymdata -cf - logs | tar -C "$out" -xf -

echo "==> Pulled to $out"
find "$out" -type f -printf '%TY-%Tm-%Td %TH:%TM  %8s  %P\n' | sort
crashes=0
if [ -d "$out/logs/crashes" ]; then
  crashes=$(find "$out/logs/crashes" -type f -name '*.txt' | wc -l)
fi
echo "==> $crashes report(s) in crashes/"
