#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
STOCK_LAUNCHER="${STOCK_LAUNCHER-com.google.android.tvlauncher}"   # set STOCK_LAUNCHER= (empty) to leave the stock launcher enabled
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
PY=../tools/venv/bin/python
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/tvhome.apk" \
  "rm -f /data/local/tmp/tvhome.apk" \
  "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow; echo overlay-allowed" \
  "cmd package set-home-activity --user 0 com.gathin.tvhome" \
  ${STOCK_LAUNCHER:+"pm disable-user --user 0 $STOCK_LAUNCHER"} \
  | "$PY" -I ../tools/tv.py --batch
if [ -n "${REDIRECT_HOME:-}" ]; then
  # Fire OS: turn on the accessibility service that sends the stock Home to this launcher.
  SVC=com.gathin.tvhome/com.gathin.tvhome.HomeRedirectService
  CUR=$("$PY" -I ../tools/tv.py "settings get secure enabled_accessibility_services" | tr -d '\r\n')
  case "$CUR" in null|"") NEW=$SVC ;; *"$SVC"*) NEW=$CUR ;; *) NEW="$CUR:$SVC" ;; esac
  # Clear first: a reinstalled service only rebinds (and re-reads its config) after being switched off and on.
  printf '%s\n' \
    "settings delete secure enabled_accessibility_services" \
    "settings put secure enabled_accessibility_services $NEW" \
    "settings put secure accessibility_enabled 1" \
    | "$PY" -I ../tools/tv.py --batch
  echo "Home redirect on. Turn off: settings delete secure enabled_accessibility_services (if it was empty before), or put back: $CUR"
fi
echo "Installed. Press Home on the remote to see it."
