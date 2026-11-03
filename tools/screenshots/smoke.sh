#!/usr/bin/env bash
# Prova su emulatore delle funzioni 0.10.0: riquadro «Chiamate perse» e pulsante «Casa» sopra le altre app.
# Uso: smoke.sh <apk> <cartella uscita>
set -u
APK=$1; OUT=$2; P=app.semplicephone
mkdir -p "$OUT"; cd "$(dirname "$0")"
adb root >/dev/null; adb wait-for-device; sleep 2
adb install -r "$APK"
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell cmd role add-role-holder android.app.role.HOME $P 0 || true
for L0 in com.google.android.apps.nexuslauncher com.android.launcher3; do adb shell pm disable-user --user 0 $L0 >/dev/null 2>&1 || true; done
adb shell settings put global hide_error_dialogs 1 || true
for perm in READ_CONTACTS READ_CALL_LOG CALL_PHONE; do adb shell pm grant $P android.permission.$perm; done
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell cmd locale set-app-locales $P --locales it

contact() {
  adb shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:
  local id; id=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id | tail -1 | sed -E 's/.*_id=([0-9]+).*/\1/')
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id \
    --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"$1"
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id \
    --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:"$2" --bind data2:i:2
}
contact "Maria Rossi" +393331234567
contact "Luca" +393477654321

NOW=$(( $(date +%s) * 1000 ))
call() { adb shell content insert --uri content://call_log/calls --bind number:s:"$1" --bind type:i:"$2" --bind date:l:"$3" --bind duration:i:0 --bind new:i:1; }
call 3331234567 3 $((NOW - 3600000))      # Maria, persa
call +393331234567 3 $((NOW - 7200000))   # Maria, persa (2 volte)
call 3477654321 3 $((NOW - 9000000))      # Luca, persa…
call 3477654321 2 $((NOW - 1800000))      # …poi richiamato: non deve comparire
call 0612345678 3 $((NOW - 600000))       # sconosciuto, perso
adb shell content query --uri content://call_log/calls --projection number:type:date > "$OUT/calllog.txt"

cat > /tmp/c.json <<'J'
{"version":1,"pages":[{"cols":2,"rows":5,"tiles":[
 {"id":"clock","col":0,"row":0,"w":2,"h":1,"type":"clock","color":"#FFFFFF"},
 {"id":"missed","col":0,"row":1,"w":2,"h":3,"type":"missed_calls","label":"Chiamate perse","color":"#FFE7A8"},
 {"id":"set","col":0,"row":4,"w":2,"h":1,"type":"app","target":"com.android.settings","label":"Impostazioni","color":"#1F4FA8"}]}],
 "phone":{"homeButton":true}}
J
adb push /tmp/c.json /data/local/tmp/c.json >/dev/null
adb shell am force-stop $P
adb shell "run-as $P sh -c 'mkdir -p files && cp /data/local/tmp/c.json files/config.json'"
home() { adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null; }
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/$1.xml"; echo "  $1"; }
focus() { adb shell dumpsys window | grep -m1 mCurrentFocus | tee -a "$OUT/focus.txt"; }

home; shot 1_home 5; focus
adb shell am start -W -n com.android.settings/.Settings >/dev/null; shot 2_settings 4; focus
# Tocco sul pulsante «Casa» (cercato per descrizione)
B=$(python3 - "$OUT/2_settings.xml" <<'PY'
import re,sys
x=open(sys.argv[1]).read()
m=re.search(r'content-desc="Torna alla schermata principale"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
print(f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}" if m else "")
PY
)
echo "pulsante: '$B'" | tee "$OUT/button.txt"
if [ -n "$B" ]; then adb shell input tap $B; fi
shot 3_after_tap 3; focus
# Richiamata dal riquadro: tocco sulla prima riga (Maria)
R=$(python3 - "$OUT/3_after_tap.xml" <<'PY'
import re,sys
x=open(sys.argv[1]).read()
m=re.search(r'text="Maria Rossi"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
print(f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}" if m else "")
PY
)
echo "riga: '$R'" | tee -a "$OUT/button.txt"
if [ -n "$R" ]; then adb shell input tap $R; shot 4_callback 5; focus; fi
adb shell dumpsys telecom | grep -iE "Call TC|state" | head -5 > "$OUT/telecom.txt" || true
adb logcat -d -s AndroidRuntime:E HomeButton:* MissedCalls:* > "$OUT/logcat.txt" || true
