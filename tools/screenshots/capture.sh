#!/usr/bin/env bash
# Screenshot reali dell'app sull'emulatore, in tutte le lingue. Lanciato da .github/workflows/screenshots.yml.
set -euo pipefail
cd "$(dirname "$0")"
APK=$1; OUT=$2; mkdir -p "$OUT"
P=app.semplicephone
declare -A CONFIRM=([it]=Conferma [en]=Confirm [de]=Bestätigen [fr]=Confirmer [es]=Confirmar)
declare -A CANCEL=([it]=ANNULLA [en]=CANCEL [de]=ABBRECHEN [fr]=ANNULER [es]=CANCELAR)

adb root >/dev/null; adb wait-for-device; sleep 2
adb install -r "$APK"
adb shell settings put global auto_time 0
adb shell date 110209302026.00 >/dev/null
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell cmd role add-role-holder android.app.role.HOME $P 0 || true
# Barra di stato pulita
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter; demo clock -e hhmm 0930; demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile show -e level 4 -e datatype none; demo notifications -e visible false

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "  $1"; }
home() { adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null; }
first=1
for L in it en de fr es; do
  echo "== $L"
  adb shell cmd locale set-app-locales $P --locales $L
  adb shell am force-stop $P
  python3 demo_config.py $L > /tmp/c.json
  adb push /tmp/c.json /data/local/tmp/c.json >/dev/null
  adb shell "run-as $P sh -c 'mkdir -p files && cp /data/local/tmp/c.json files/config.json'"
  home; shot "${L}_1_home" 4

  # SOS: pressione prolungata, conto alla rovescia, annulla
  python3 tap.py --long 2600 --regex '^SOS:'
  shot "${L}_2_sos" 1
  python3 tap.py "${CANCEL[$L]}"; sleep 1

  # Promemoria a schermo intero (activity interna, avviabile da root)
  adb shell am start -W -n $P/.reminders.ReminderActivity --es id r1 >/dev/null
  shot "${L}_3_reminder" 3
  home; sleep 1

  # Impostazioni: 5 tocchi sull'orologio, PIN (creato la prima volta), editor
  for i in 1 2 3 4 5; do python3 tap.py --regex '\d\d:\d\d' >/dev/null; done
  for round in $( [ $first = 1 ] && echo 1 2 || echo 1 ); do
    for d in 1 2 3 4; do python3 tap.py "$d" >/dev/null; done
    python3 tap.py "${CONFIRM[$L]}" >/dev/null; sleep 1
  done
  first=0
  shot "${L}_4_editor" 3
  python3 tap.py Maria >/dev/null
  shot "${L}_5_tile" 3
  home
done
ls -la "$OUT"
