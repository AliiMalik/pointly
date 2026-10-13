#!/usr/bin/env bash
# SPEC rule 1 and 2 / acceptance test A9.
# Fails if Rehnuma's Kotlin, Java or Dart sources use any API that acts for the user, captures the
# screen or launches other apps, or if the accessibility service XML asks for gesture/screenshot rights.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/app"
FORBIDDEN='performAction|dispatchGesture|performGlobalAction|takeScreenshot|MediaProjection|getLaunchIntentForPackage'
status=0

mapfile -t sources < <(
  find "$APP" \
    \( -name build -o -name .dart_tool -o -name .gradle -o -name .cxx \) -prune -o \
    -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.dart' -o -name '*.java' \) \
    ! -name 'GeneratedPluginRegistrant.java' -print
)

if [ "${#sources[@]}" -eq 0 ]; then
  echo "FAIL: no Kotlin/Dart sources found under $APP"
  exit 1
fi

hits="$(grep -nE "$FORBIDDEN" "${sources[@]}" 2>/dev/null || true)"
if [ -n "$hits" ]; then
  echo "FAIL: forbidden APIs found:"
  echo "$hits"
  status=1
fi

xml="$APP/android/app/src/main/res/xml/rehnuma_accessibility_service.xml"
if [ ! -f "$xml" ]; then
  echo "FAIL: missing $xml"
  status=1
elif grep -nE 'android:(canPerformGestures|canTakeScreenshot)' "$xml"; then
  echo "FAIL: accessibility service XML declares gesture or screenshot capability"
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "PASS: no forbidden APIs in ${#sources[@]} source files; service XML is read-only."
fi
exit "$status"
