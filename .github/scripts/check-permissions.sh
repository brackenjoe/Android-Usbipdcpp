#!/usr/bin/env bash
# Permission allowlist check for the built APK.
#
#   .github/scripts/check-permissions.sh path/to/app-debug.apk
#
# Runs `aapt2 dump permissions` on the APK and fails if the manifest asks for
# any uses-permission outside the list below, or if the output cannot be read
# (no package line, no permissions at all). The list is exact: a new
# permission is a decision, so it is added here in the same commit that adds
# it to the manifest. CAMERA, for one, is not on it.
#
# aapt2 is found, in order, from $AAPT2, the newest build-tools under
# $ANDROID_HOME (or $ANDROID_SDK_ROOT), then PATH. Exit 0 pass, 1 a
# permission outside the list or unreadable output, 2 usage or no aapt2.
set -euo pipefail

ALLOWED_PERMISSIONS=(
  android.permission.INTERNET
  android.permission.ACCESS_NETWORK_STATE
  android.permission.FOREGROUND_SERVICE
  android.permission.FOREGROUND_SERVICE_SPECIAL_USE
  android.permission.POST_NOTIFICATIONS
  android.permission.WAKE_LOCK
  android.permission.USE_BIOMETRIC
)
# Plus <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, which AndroidX
# adds for the app's own use; the applicationId is read from the APK.

find_aapt2() {
  if [ -n "${AAPT2:-}" ]; then
    printf '%s\n' "$AAPT2"
    return
  fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" newest=""
  if [ -n "$sdk" ] && [ -d "$sdk/build-tools" ]; then
    newest=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name aapt2 -type f \
      | sort -V | tail -n 1)
  fi
  if [ -n "$newest" ]; then
    printf '%s\n' "$newest"
  elif command -v aapt2 >/dev/null 2>&1; then
    command -v aapt2
  fi
}

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 path/to/app.apk" >&2
  exit 2
fi
apk=$1

aapt2=$(find_aapt2)
if [ -z "$aapt2" ]; then
  echo "::error::aapt2 not found (set AAPT2, or ANDROID_HOME with build-tools installed)" >&2
  exit 2
fi

dump=$("$aapt2" dump permissions "$apk")

package=$(printf '%s\n' "$dump" | sed -n "s/^package: //p" | head -n 1)
if [ -z "$package" ]; then
  echo "::error::no 'package:' line in aapt2 output; cannot check $apk" >&2
  exit 1
fi
allowed=("${ALLOWED_PERMISSIONS[@]}" "$package.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

# Every line that begins uses-permission (also uses-permission-sdk-23 and any
# later variant); the name is the first quoted value after name=.
requested=$(printf '%s\n' "$dump" \
  | sed -n "s/^uses-permission[A-Za-z0-9-]*: name='\([^']*\)'.*/\1/p")
declared=$(printf '%s\n' "$dump" | grep -c '^uses-permission' || true)
if [ -z "$requested" ] || [ "$(printf '%s\n' "$requested" | wc -l)" -ne "$declared" ]; then
  echo "::error::could not read every uses-permission line of $apk (read $(printf '%s' "$requested" | grep -c . || true) of $declared)" >&2
  exit 1
fi

bad=0
while IFS= read -r perm; do
  ok=0
  for a in "${allowed[@]}"; do
    if [ "$perm" = "$a" ]; then ok=1; break; fi
  done
  if [ "$ok" -eq 0 ]; then
    echo "::error::permission not on the allowlist: $perm" >&2
    bad=1
  fi
done <<< "$requested"

if [ "$bad" -ne 0 ]; then
  exit 1
fi
echo "permissions OK: $(printf '%s\n' "$requested" | wc -l) requested by $package, all on the allowlist"
