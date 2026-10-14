#!/usr/bin/env bash
# Claude Code PreToolUse hook (Bash): forces an explicit user confirmation before any
# command that can wipe the app's on-device data (gymdata/ lives ONLY on the phone —
# see the CLAUDE.md banner). Enforced by the harness, so it holds even when a
# permission rule (e.g. "adb shell *") would otherwise let the command run silently.
#
# Wired in .claude/settings.json. Reads the tool-call JSON on stdin; prints an "ask"
# decision when a pattern matches, nothing otherwise (= normal permission flow).
# Test: echo '{"tool_input":{"command":"adb shell pm clear com.mygymapp"}}' | .claude/hooks/guard-device-data.sh
set -euo pipefail

cmd="$(jq -r '.tool_input.command // empty')"
[ -z "$cmd" ] && exit 0

reason=""
check() { # check <extended-regex> <reason>
  if [ -z "$reason" ] && printf '%s' "$cmd" | grep -Eq -- "$1"; then reason="$2"; fi
}

check '\bpm[[:space:]]+(uninstall|clear)\b' \
  "pm uninstall/clear deletes gymdata/ for good."
check '\bcmd[[:space:]]+package[[:space:]]+(uninstall|clear)\b' \
  "cmd package uninstall/clear deletes gymdata/ for good."
check '\badb\b.*[[:space:]]uninstall\b' \
  "adb uninstall deletes gymdata/ for good."
check 'run-as[[:space:]]+com\.mygymapp\b.*[[:space:]](rm|mv|truncate)[[:space:]]' \
  "Write/delete inside the app's private files."
check 'connected[A-Za-z]*AndroidTest|generateBaselineProfile|:baseline-profile|generate-baseline-profile\.sh|[Mm]acrobenchmark' \
  "Instrumented tests / baseline profile uninstall the app (uninstall_after_test: true)."
check '\bgradlew\b.*\binstall(Debug|Release|[A-Z][A-Za-z]*)\b' \
  "gradlew install* skips the backup — use scripts/install-debug.sh (/install)."
check '\badb\b.*[[:space:]]install(-multiple)?[[:space:]]' \
  "adb install by hand skips the backup — use scripts/install-debug.sh (/install)."

[ -z "$reason" ] && exit 0

jq -n --arg r "⛔ MyGymApp data guard: $reason Take a verified tar backup of gymdata/ first (CLAUDE.md banner)." '{
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    permissionDecision: "ask",
    permissionDecisionReason: $r
  }
}'
