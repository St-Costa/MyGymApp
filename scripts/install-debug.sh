#!/usr/bin/env bash
# Build the debug variant and install it on the connected device, backing up
# the on-device gymdata first. See the CLAUDE.md banner for why the backup
# step is non-negotiable: all user data lives ONLY on the phone.
#
# Usage: scripts/install-debug.sh
set -euo pipefail

cd "$(dirname "$0")/.."

APP_ID="com.mygymapp"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BACKUP_DIR="backups/gymdata"
KEEP_BACKUPS=5
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "==> Checking device connection"
if ! adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
  echo "ERROR: no device in 'device' state. Is it connected and authorized (adb devices)?" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
timestamp="$(date +%Y%m%d-%H%M%S)"
backup_file="$BACKUP_DIR/gymdata-backup-${timestamp}.tar"

echo "==> Backing up on-device gymdata"
if ! adb shell run-as "$APP_ID" tar -C /data/data/"$APP_ID"/files -cf - gymdata > "$backup_file" 2>/tmp/install-debug-backup-err.log; then
  echo "ERROR: backup failed (installed build may not be debuggable). Details:" >&2
  cat /tmp/install-debug-backup-err.log >&2
  echo "Install the debug APK first with 'adb install -r $APK_PATH' (keeps data), then re-run this script." >&2
  rm -f "$backup_file"
  exit 1
fi

file_count="$(tar -tf "$backup_file" | wc -l)"
if [ "$file_count" -lt 1 ]; then
  echo "ERROR: backup tar is empty ($backup_file) — refusing to proceed." >&2
  exit 1
fi
echo "    backup OK: $backup_file ($file_count entries, $(du -h "$backup_file" | cut -f1))"

echo "==> Pruning old backups (keeping last $KEEP_BACKUPS)"
ls -t "$BACKUP_DIR"/gymdata-backup-*.tar 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -v

echo "==> Building debug APK"
ANDROID_HOME="$ANDROID_HOME" ./gradlew assembleDebug

echo "==> Installing on device (adb install -r, data preserved)"
adb install -r "$APK_PATH"

echo "==> Done. Backup kept at: $backup_file"
