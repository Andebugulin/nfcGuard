#!/usr/bin/env bash
# Put a connected device into the state the instrumented suite expects.
#
# Every APK reinstall wipes these, so re-run after each install. Prefer this
# over ./gradlew :app:connectedDebugAndroidTest, which reinstalls each run and
# therefore always starts from a wiped state.
#
# Usage: bash scripts/grant-test-permissions.sh
set -u
PKG=com.andebugulin.nfcguard
SVC="$PKG/$PKG.service.ForegroundDetectorService"

command -v adb >/dev/null || { echo "adb not on PATH"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no device connected"; exit 1; }
adb shell pm list packages | grep -q "$PKG" || { echo "$PKG is not installed"; exit 1; }

adb shell appops set "$PKG" GET_USAGE_STATS allow
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell dumpsys deviceidle whitelist "+$PKG" >/dev/null

# Append to the accessibility list — never replace it, or other apps'
# accessibility services get silently switched off.
EXISTING=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
[ "$EXISTING" = "null" ] && EXISTING=""
case ":$EXISTING:" in
  *":$SVC:"*) ;;
  *) adb shell settings put secure enabled_accessibility_services \
       "$([ -n "$EXISTING" ] && echo "$EXISTING:$SVC" || echo "$SVC")" ;;
esac
adb shell settings put secure accessibility_enabled 1

# Simulated NFC taps need android.nfc.Tag.createMockTag, which is @hide: the
# non-SDK-interface blocklist hides it from reflection entirely (getDeclaredMethods
# does not even list it) until this policy is relaxed. 1 = allow all.
# Restore the device default afterwards with:
#   adb shell settings delete global hidden_api_policy
adb shell settings put global hidden_api_policy 1

adb shell input keyevent KEYCODE_WAKEUP

echo "usage access : $(adb shell appops get $PKG GET_USAGE_STATS | tr -d '\r')"
echo "overlay      : $(adb shell appops get $PKG SYSTEM_ALERT_WINDOW | tr -d '\r')"
echo "a11y services: $(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
echo "hidden api   : $(adb shell settings get global hidden_api_policy | tr -d '\r') (1 = mock NFC taps work)"
