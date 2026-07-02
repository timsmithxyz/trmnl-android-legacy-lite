#!/usr/bin/env bash
# Post-process an AGP-built APK so it launches on Android <= 2.1 (API 7):
# transcode UTF-8 resource string pools to UTF-16, then realign and re-sign.
#
# Usage: tools/build-legacy.sh [input.apk] [output.apk]
# Defaults to the debug APK in/out under app/build/outputs/apk/debug/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IN="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
OUT="${2:-$ROOT/app/build/outputs/apk/debug/app-debug-legacy.apk}"

# Resolve the SDK from (in order): explicit override, local.properties, env vars.
resolve_sdk() {
  if [ -n "${ANDROID_SDK:-}" ]; then echo "$ANDROID_SDK"; return; fi
  if [ -f "$ROOT/local.properties" ]; then
    local d; d="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1)"
    [ -n "$d" ] && { echo "$d"; return; }
  fi
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" \
           "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [ -n "$d" ] && [ -d "$d/build-tools" ] && { echo "$d"; return; }
  done
  echo "ERROR: Android SDK not found (set ANDROID_SDK or sdk.dir)" >&2; exit 1
}
SDK="$(resolve_sdk)"
# Pick the highest build-tools version available.
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)}"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
KEYSTORE="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo ">> transcoding string pools to UTF-16"
python3 "$ROOT/tools/utf16_patch.py" --apk "$IN" "$TMP/patched.apk"

echo ">> zipalign"
"$ZIPALIGN" -f -p 4 "$TMP/patched.apk" "$TMP/aligned.apk"

echo ">> signing (debug key)"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" "$TMP/aligned.apk"

"$APKSIGNER" verify --print-certs "$OUT" >/dev/null && echo ">> signature OK"
echo ">> done: $OUT"
