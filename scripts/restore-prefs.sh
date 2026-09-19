#!/usr/bin/env bash
# Restore a SharedPreferences tar created by scripts/install-debug.sh onto the
# connected device (debuggable build required for `run-as`).
#
# Usage: scripts/restore-prefs.sh backups/prefs/shared-prefs-backup-<ts>-v<ver>.tar
#
# The app is force-stopped afterwards because SharedPreferences are cached in
# memory — without the restart the running process would keep serving stale values.
#
# Caveat: after a FULL uninstall (not `adb install -r`) the Android Keystore keys are
# gone, so the restored sync_config_enc.xml can no longer be decrypted. In that case
# re-enter the server URL + token by hand in Options; everything else (powerlifting
# schedule, paired devices) comes back. The user profile needs no restore at all — it
# lives in gymdata/exercises/_settings.md since Phase 118.
set -euo pipefail

cd "$(dirname "$0")/.."

APP_ID="com.mygymapp"

if [ $# -ne 1 ]; then
  echo "Usage: $0 backups/prefs/shared-prefs-backup-<ts>-v<ver>.tar" >&2
  exit 1
fi
backup_file="$1"
if [ ! -f "$backup_file" ]; then
  echo "ERROR: backup file not found: $backup_file" >&2
  exit 1
fi

echo "==> Checking device connection"
if ! adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
  echo "ERROR: no device in 'device' state. Is it connected and authorized (adb devices)?" >&2
  exit 1
fi

echo "==> Preview of $backup_file"
tar -tf "$backup_file" | head -20

echo "==> Restoring shared_prefs from $backup_file"
adb shell run-as "$APP_ID" mkdir -p /data/data/"$APP_ID"/shared_prefs
adb shell run-as "$APP_ID" tar -xf - -C /data/data/"$APP_ID"/shared_prefs < "$backup_file"

echo "==> Force-stopping $APP_ID so restored prefs take effect"
adb shell am force-stop "$APP_ID"

echo "==> Done. Reopen the app and verify (profile + sync config in Options)."
