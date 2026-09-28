#!/usr/bin/env bash
# Audits an APK for anything that could make network access possible.
# Fails (exit 1) if the merged manifest requests INTERNET (or other network permissions), or
# declares Google Play Services / Firebase components.
#   usage: scripts/verify_offline.sh app/build/outputs/apk/release/app-release.apk
set -euo pipefail
APK="${1:?usage: verify_offline.sh <apk>}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
AAPT2=$(ls -1 "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)
if [ -z "$AAPT2" ]; then echo "aapt2 not found (set ANDROID_HOME)"; exit 2; fi
fail=0
perms=$("$AAPT2" dump permissions "$APK")
echo "$perms"
for p in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE android.permission.ACCESS_WIFI_STATE \
         android.permission.CHANGE_NETWORK_STATE android.permission.CHANGE_WIFI_STATE; do
  if echo "$perms" | grep -q "$p"; then echo "FAIL: APK requests $p"; fail=1; fi
done
manifest=$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK")
if echo "$manifest" | grep -Eq "com\.google\.android\.gms|com\.google\.firebase"; then
  echo "FAIL: Google Play Services / Firebase components declared"; fail=1
fi
if unzip -l "$APK" | grep -Eq "libcurl|libssl|libcrypto"; then
  echo "FAIL: networking native libraries packaged"; fail=1
fi
[ $fail -eq 0 ] && echo "OK: no network permissions, no GMS/Firebase, no network libraries in $(basename "$APK")"
exit $fail
