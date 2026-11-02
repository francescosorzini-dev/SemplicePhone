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
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell cmd role add-role-holder android.app.role.HOME $P 0 || true
# Il launcher dell'immagine va in ANR sull'emulatore lento e il suo dialogo copre lo schermo: lo si disattiva.
for L0 in com.google.android.apps.nexuslauncher com.android.launcher3; do adb shell pm disable-user --user 0 $L0 >/dev/null 2>&1 || true; done
adb shell settings put global hide_error_dialogs 1 || true
adb shell pm grant $P android.permission.READ_CONTACTS
# Contatti dimostrativi: nell'SOS compaiono i nomi invece dei numeri
contact() {
  adb shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:
  local id; id=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id | tail -1 | sed -E 's/.*_id=([0-9]+).*/\1/')
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id \
    --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"$1"
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id \
    --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:"$2" --bind data2:i:2
}
contact Maria +393331234567
contact Luca +393477654321
# Barra di stato pulita
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }

# In caso di errore salva l'albero di uiautomator per capire cosa c'era a schermo.
dbg() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/debug_$1.xml" || true; adb exec-out screencap -p > "$OUT/debug_$1.png" || true; }
trap 'dbg error' ERR
# Barra di stato in modalità demo, con l'ora del telefono (uguale all'orologio dell'app).
statusbar() {
  demo enter; demo clock -e hhmm "$(adb shell date +%H%M | tr -d '\r')"; demo battery -e level 100 -e plugged false
  demo network -e wifi show -e level 4 -e mobile show -e level 4 -e datatype none; demo notifications -e visible false
}
shot() { sleep "${2:-2}"; statusbar; sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; echo "  $1"; }
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
  python3 tap.py --long 2600 --regex '^SOS'
  shot "${L}_2_sos" 0
  python3 tap.py "${CANCEL[$L]}" || adb shell input keyevent HOME; sleep 1

  # Promemoria a schermo intero (activity interna, avviabile da root)
  adb shell am start -W -n $P/.reminders.ReminderActivity --es id r1 >/dev/null
  shot "${L}_3_reminder" 3
  home; sleep 1

  # Impostazioni: 5 tocchi sull'orologio, PIN (creato la prima volta), editor
  python3 tap.py --times 5 --regex '^\d\d:\d\d$' >/dev/null; sleep 1
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
