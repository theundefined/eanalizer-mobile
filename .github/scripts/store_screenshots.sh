#!/usr/bin/env bash
# Store screenshots (StoreScreenshotsTest) w jasnym i ciemnym motywie, na
# działającym już emulatorze. Uruchamiane z .github/workflows/screenshots.yml.
#
# Każdy przebieg connectedDebugAndroidTest czyści katalog z dodatkowym wyjściem testów, więc
# zrzuty kopiujemy po każdym motywie. Nieudany test (np. wolna strona biblioteki) nie przerywa
# drugiego motywu — status zwracamy na końcu, po zebraniu wszystkiego, co się udało.
set -uo pipefail

out=store-screenshots
mkdir -p "$out"
status=0

# Świeżo uruchomiony emulator bywa jeszcze zajęty (ANR launchera na pierwszych zrzutach) —
# wyłączamy okna błędów i dajemy systemowi chwilę.
adb shell settings put global hide_error_dialogs 1
sleep 30

for theme in light dark; do
  night=false
  [ "$theme" = dark ] && night=true
  ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.theundefined.eanalizer.StoreScreenshotsTest \
    -Pandroid.testInstrumentationRunnerArguments.storeScreenshots=true \
    -Pandroid.testInstrumentationRunnerArguments.night="$night" || status=1
  find app/build/outputs/connected_android_test_additional_output -name 'store_*.png' \
    -exec cp {} "$out/" \;
done

ls -l "$out"
exit "$status"
