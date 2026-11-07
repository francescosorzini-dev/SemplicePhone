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
adb shell cmd locale set-app-locales $P --locales ${SMOKE_LANG:-fr}

contact() {
  adb shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:
  local id; id=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id | tail -1 | sed -E 's/.*_id=([0-9]+).*/\1/')
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id \
    --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"$1"
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id \
    --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:"$2" --bind data2:i:2
}
contact Maria +393331234567
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
 {"id":"set","col":0,"row":4,"w":1,"h":1,"type":"app","target":"com.android.settings","label":"Impostazioni","color":"#1F4FA8"},
 {"id":"con","col":1,"row":4,"w":1,"h":1,"type":"app","target":"com.google.android.contacts","label":"Rubrica","color":"#1E7B34","homeButton":false}]}],
 "phone":{"homeButton":true}}
J
adb push /tmp/c.json /data/local/tmp/c.json >/dev/null
adb shell am force-stop $P
adb shell "run-as $P sh -c 'mkdir -p files && cp /data/local/tmp/c.json files/config.json'"
home() { adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null; }
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/$1.xml"; echo "  $1"; }
focus() { adb shell dumpsys window | grep -m1 mCurrentFocus | tee -a "$OUT/focus.txt"; }

home; shot 1_home 5; focus
# Un'app qualsiasi (non Impostazioni: da Android 12 nasconde le sovrapposizioni delle altre app)
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.APP_CONTACTS >/dev/null; shot 2_other_app 4; focus
adb shell dumpsys window windows > "$OUT/windows.txt"
# Finestra del pulsante: tipo APPLICATION_OVERLAY del nostro pacchetto, centro della sua cornice
B=$(python3 - "$OUT/windows.txt" <<'PY'
import re,sys
x=open(sys.argv[1]).read()
for blk in re.split(r"\n  Window #", x):
    if "app.semplicephone" in blk and "APPLICATION_OVERLAY" in blk:
        m=re.search(r"frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", blk)
        if m: print(f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}"); break
PY
)
echo "pulsante: '$B'" | tee "$OUT/button.txt"
if [ -n "$B" ]; then adb shell input tap $B; fi
shot 3_after_tap 3; focus
# Richiamata dal riquadro: tocco sulla prima riga (Maria)
R=$(python3 - "$OUT/3_after_tap.xml" <<'PY'
import re,sys
x=open(sys.argv[1]).read()
m=re.search(r'text="Maria"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
print(f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}" if m else "")
PY
)
echo "riga: '$R'" | tee -a "$OUT/button.txt"
if [ -n "$R" ]; then adb shell input tap $R; shot 4_callback 5; focus; fi
# Riquadro con «Pulsante Casa» spento: l'app si apre senza pulsante
adb shell input keyevent KEYCODE_ENDCALL; sleep 2; home; sleep 2
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/5_home.xml"
C=$(python3 - "$OUT/5_home.xml" <<'PY'
import re,sys
x=open(sys.argv[1]).read()
m=re.search(r'text="Rubrica"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
print(f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}" if m else "")
PY
)
echo "rubrica: '$C'" | tee -a "$OUT/button.txt"
if [ -n "$C" ]; then adb shell input tap $C; shot 6_tile_no_button 4; focus; adb shell dumpsys window windows | grep -c "APPLICATION_OVERLAY.*semplicephone\|semplicephone.*APPLICATION_OVERLAY" > "$OUT/overlay_after_tile.txt" || true; fi
adb shell dumpsys telecom | grep -iE "Call TC|state" | head -5 > "$OUT/telecom.txt" || true
adb logcat -d -s HomeButton:* MissedCalls:* | grep -v "^-" > "$OUT/logcat.txt" || true

# ---- Lingue: config con testi predefiniti italiani (come creata in italiano o dal portale)
cat > /tmp/l.json <<'J'
{"version":1,"pages":[{"cols":2,"rows":5,"tiles":[
 {"id":"clock","col":0,"row":0,"w":2,"h":1,"type":"clock","color":"#FFFFFF"},
 {"id":"dialer","col":0,"row":1,"type":"app","target":"@dialer","label":"Telefono","color":"#1F4FA8"},
 {"id":"sms","col":1,"row":1,"type":"app","target":"@sms","label":"Messaggi","color":"#1E7B34"},
 {"id":"fam","col":0,"row":2,"type":"photos","label":"Famiglia","color":"#6A1B9A"},
 {"id":"m","col":1,"row":2,"type":"call","target":"3331234567","label":"Maria","color":"#C2185B"},
 {"id":"miss","col":0,"row":3,"w":2,"type":"missed_calls","label":"Chiamate perse","color":"#FFE7A8"},
 {"id":"sos","col":0,"row":4,"w":2,"type":"sos","label":"SOS","color":"#B71C1C"}]}],
 "sos":{"numbers":["3331234567"],"message":"Ho bisogno di aiuto."},
 "reminders":[{"id":"r1","time":"08:00","title":"Doccia","icon":"shower"},{"id":"r2","time":"09:00","title":"È ora della medicina"}],
 "phone":{}}
J
adb push /tmp/l.json /data/local/tmp/l.json >/dev/null
for L in en de it; do
  adb shell cmd locale set-app-locales $P --locales $L
  adb shell am force-stop $P
  adb shell "run-as $P sh -c 'cp /data/local/tmp/l.json files/config.json'"
  home; shot "lang_${L}_home" 4
  adb shell am start -W -n $P/.reminders.ReminderActivity --es id r1 >/dev/null; shot "lang_${L}_reminder" 3
  adb shell am start -W -n $P/.reminders.ReminderActivity --es id r2 >/dev/null; shot "lang_${L}_reminder2" 3
  # Risposta al secondo: deve ricomparire il primo, non chiudersi
  T=$(python3 - "$OUT/lang_${L}_reminder2.xml" <<'PY'
import re,sys
x=open(sys.argv[1]).read()
m=re.search(r'text="(DONE|ERLEDIGT|FATTO|TAKEN|GENOMMEN|PRESO)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
print(f"{(int(m[2])+int(m[4]))//2} {(int(m[3])+int(m[5]))//2}" if m else "")
PY
)
  [ -n "$T" ] && adb shell input tap $T; shot "lang_${L}_reminder3" 3
  for f in home reminder reminder2 reminder3; do grep -o 'text="[^"]\+"' "$OUT/lang_${L}_$f.xml" | tr '\n' ' ' >> "$OUT/lang.txt"; echo " <- $L $f" >> "$OUT/lang.txt"; done
  adb shell input keyevent BACK
done

# ---- Cambio lingua con l'app aperta (come dalla pagina di sistema «Lingua dell'app»)
adb shell cmd locale set-app-locales $P --locales it; adb shell am force-stop $P
adb shell "run-as $P sh -c 'cp /data/local/tmp/l.json files/config.json'"
adb logcat -c
home; sleep 3
setl() { echo "-- set $1: $(adb shell cmd locale set-app-locales $P --locales $1 2>&1)" >> "$OUT/crash.txt"; }
step() { echo "== $1" >> "$OUT/crash.txt"; adb shell pidof $P >> "$OUT/crash.txt" || echo "PROCESSO MORTO" >> "$OUT/crash.txt"; adb shell cmd locale get-app-locales $P >> "$OUT/crash.txt"; adb shell dumpsys activity activities | grep -E "topResumedActivity|ResumedActivity" | head -2 >> "$OUT/crash.txt"; }
setl en; sleep 5; step "home it->en"; shot crash_1_home 1
python3 tap.py --times 5 --regex '^\d\d:\d\d$' >/dev/null; sleep 1
for round in 1 2; do for d in 1 2 3 4; do python3 tap.py "$d" >/dev/null; done; python3 tap.py Confirm >/dev/null; sleep 1; done
shot crash_2_editor 2
setl de; sleep 5; step "editor en->de"; shot crash_3_editor_de 1
adb shell am start -W -n $P/.reminders.ReminderActivity --es id r1 >/dev/null; sleep 2
setl fr; sleep 5; step "promemoria de->fr"; shot crash_4_reminder_fr 1
adb shell input keyevent HOME; sleep 2
setl es; sleep 5; step "home fr->es"; shot crash_5_home_es 1
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.APP_CONTACTS >/dev/null; sleep 3
setl it; sleep 5; step "altra app con pulsante Casa es->it"; shot crash_6_other 1
adb logcat -d -b crash > "$OUT/logcat_crash.txt" || true
adb logcat -d | grep -E "AndroidRuntime|FATAL|semplicephone" | grep -iE "fatal|exception|caused by|at app\.semplicephone|ANR" | head -80 > "$OUT/logcat_errors.txt" || true

# ---- Riquadro «Cerca app»
cat > /tmp/s.json <<'J'
{"version":1,"pages":[{"cols":2,"rows":5,"tiles":[
 {"id":"clock","col":0,"row":0,"w":2,"h":1,"type":"clock","color":"#FFFFFF"},
 {"id":"cerca","col":0,"row":1,"w":2,"h":1,"type":"search","label":"Cerca app","color":"#1F4FA8"}]}],"phone":{}}
J
adb push /tmp/s.json /data/local/tmp/s.json >/dev/null
adb shell cmd locale set-app-locales $P --locales it; adb shell am force-stop $P
adb shell "run-as $P sh -c 'cp /data/local/tmp/s.json files/config.json'"
home; sleep 3
python3 tap.py "Cerca app" >/dev/null; shot search_1_open 3
adb shell input text "sett"; shot search_2_typed 2
grep -o 'text="[^"]\+"' "$OUT/search_2_typed.xml" | tr '\n' ' ' > "$OUT/search.txt"
python3 tap.py "Settings" >/dev/null || python3 tap.py "Impostazioni" >/dev/null; sleep 3; focus >> "$OUT/search.txt"

# ---- Risposta automatica dai numeri SOS e messaggio della famiglia (0.13.0)
cat > /tmp/a.json <<'J'
{"version":1,"pages":[{"cols":2,"rows":5,"tiles":[
 {"id":"clock","col":0,"row":0,"w":2,"h":1,"type":"clock","color":"#FFFFFF"},
 {"id":"sos","col":0,"row":4,"w":2,"h":1,"type":"sos","label":"SOS","color":"#B71C1C"}]}],
 "sos":{"numbers":["3331234567"]},"phone":{"autoAnswer":true,"autoAnswerSeconds":5,"announceCaller":true}}
J
adb push /tmp/a.json /data/local/tmp/a.json >/dev/null
adb shell am force-stop $P
adb shell "run-as $P sh -c 'cp /data/local/tmp/a.json files/config.json'"
adb shell pm grant $P android.permission.ANSWER_PHONE_CALLS
adb shell pm grant $P android.permission.READ_PHONE_STATE
adb shell cmd role add-role-holder android.app.role.CALL_SCREENING $P 0 || true
home; sleep 3
adb logcat -c
adb emu gsm call 3331234567; sleep 3
adb shell dumpsys telecom | grep -E "^\s+Call TC|state=|CallAudioRouteStateMachine|Current state" | head -12 > "$OUT/answer_before.txt"
sleep 7
adb shell dumpsys window | grep -m1 mCurrentFocus > "$OUT/answer_focus.txt"
adb shell dumpsys telecom | grep -E "^\s+Call TC|state=|Current state" | head -12 > "$OUT/answer_after.txt"
shot answer_call 1
adb shell dumpsys audio | grep -iE "mode|communication device|speaker" | head -15 > "$OUT/answer_audio.txt"
adb emu gsm cancel 3331234567; sleep 2
adb logcat -d -s TextToSpeech:* | tail -5 > "$OUT/answer_tts.txt" || true
# Messaggio: file come lo scrive la sincronizzazione, poi la schermata a tutto schermo
echo '{"device":"*","id":1,"text":"Passo da te alle 16, porto il pane.","from":"Anna","at":1}' > /tmp/m.json
adb push /tmp/m.json /data/local/tmp/m.json >/dev/null
adb shell "run-as $P sh -c 'cp /data/local/tmp/m.json files/family_message.json'"
home; sleep 3; shot message_home 2
adb shell am start -W -n $P/.remote.MessageActivity >/dev/null; shot message_screen 3
python3 tap.py "HO LETTO" >/dev/null || python3 tap.py "GOT IT" >/dev/null; sleep 2
adb shell "run-as $P ls files" > "$OUT/message_files.txt"
home; shot message_after 2

# ---- 0.14.0: messaggi da leggere, radio, pulsante Casa a sinistra senza scritta, contrasto, tenere premuto
cat > /tmp/n.json <<'J'
{"version":1,"pages":[{"cols":2,"rows":5,"tiles":[
 {"id":"clock","col":0,"row":0,"w":2,"h":1,"type":"clock","color":"#FFFFFF"},
 {"id":"msg","col":0,"row":1,"w":2,"h":2,"type":"messages","label":"Messaggi nuovi","color":"#1F4FA8"},
 {"id":"radio","col":0,"row":3,"w":1,"h":1,"type":"radio","label":"Radio","color":"#FFA726","stations":[{"name":"Paradise","url":"https://stream.radioparadise.com/mp3-128"},{"name":"Due","url":"http://example.invalid/x.mp3"}]},
 {"id":"cerca","col":1,"row":3,"w":1,"h":1,"type":"search","label":"Cerca","color":"#6A1B9A"}]},
 {"cols":2,"rows":5,"tiles":[]}],
 "phone":{"homeButton":true,"homeButtonSide":"left","homeButtonLabel":false,"homeButtonColor":"#FFA726","theme":"contrast","holdToOpen":true}}
J
adb push /tmp/n.json /data/local/tmp/n.json >/dev/null
adb shell am force-stop $P
adb shell "run-as $P sh -c 'cp /data/local/tmp/n.json files/config.json'"
adb shell cmd notification allow_listener $P/$P.phone.BadgeListener > "$OUT/v14_notif.txt" 2>&1 || true
adb shell cmd notification post -S messaging --conversation Famiglia --message "Anna:Ciao nonna, stasera passo" anna1 "Ciao" >> "$OUT/v14_notif.txt" 2>&1 || true
sleep 2
adb shell dumpsys notification | grep -E "semplicephone|NotificationRecord.*shell|mEnabledListener|Live notifications" | head -12 >> "$OUT/v14_notif.txt" || true
home; sleep 4; shot v14_1_home 2
grep -o 'text="[^"]\+"' "$OUT/v14_1_home.xml" | tr '\n' ' ' > "$OUT/v14_home.txt"
# Tocco breve con «tenere premuto»: non apre, dice il nome
python3 tap.py "Cerca" >/dev/null; sleep 2; focus > "$OUT/v14_hold_tap.txt"
# Pressione lunga sulla radio: apre la schermata
python3 tap.py --long 1200 "Radio" >/dev/null || true; shot v14_2_radio 3
python3 tap.py "ASCOLTA" >/dev/null || python3 tap.py "PLAY" >/dev/null || true; sleep 8; shot v14_3_radio_play 1
adb shell dumpsys media_session | grep -iE "state|semplice" | head -5 > "$OUT/v14_radio.txt"
adb shell dumpsys activity services $P | grep -E "RadioService|isForeground" | head -4 >> "$OUT/v14_radio.txt"
python3 tap.py "FERMA" >/dev/null || python3 tap.py "STOP" >/dev/null || true; sleep 2
python3 tap.py "Dopo ›" >/dev/null || true; sleep 1
python3 tap.py "ASCOLTA" >/dev/null || true; shot v14_4_radio_bad 25
python3 tap.py "FERMA" >/dev/null || true
# Pulsante Casa a sinistra, arancione, solo icona, sopra un'altra app
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.APP_CONTACTS >/dev/null; shot v14_5_homebutton 4
adb shell dumpsys window windows | grep -E "Window #.*semplice|mFrame" | grep -A1 semplice | head -6 > "$OUT/v14_homebutton.txt"
# Copia di sicurezza: le versioni salvate
adb shell "run-as $P ls files/backups" > "$OUT/v14_backups.txt" 2>&1 || true
adb logcat -d | grep -E "AndroidRuntime|FATAL" -A8 | head -60 > "$OUT/v14_errors.txt" || true
home

# ---- 0.15.0: videomessaggio a tutto schermo, modelli pronti alla prima apertura
H=16080716f27f0cd32a1211351bb818e7d435801a0b0e96874fc38b72f677f94b
adb push test.mp4 /data/local/tmp/v.mp4 >/dev/null
echo '{"device":"*","id":42,"text":"Ciao nonna, guarda!","from":"Anna","at":0,"video":"media:'$H'"}' > /tmp/m.json
adb push /tmp/m.json /data/local/tmp/m.json >/dev/null
adb shell "run-as $P sh -c 'mkdir -p files/video && cp /data/local/tmp/v.mp4 files/video/$H.vid && cp /data/local/tmp/m.json files/family_message.json'"
adb shell am start -W -n $P/.remote.MessageActivity >/dev/null; shot v15_1_video 3
shot v15_2_video_end 6
python3 tap.py "HO VISTO" >/dev/null || true; sleep 2
adb shell "run-as $P ls files/video" > "$OUT/v15_video_after.txt" 2>&1; adb shell "run-as $P ls files" >> "$OUT/v15_video_after.txt" 2>&1
# Prima apertura: nessun PIN e nessuna config salvata → modelli pronti
adb shell am force-stop $P
adb shell "run-as $P sh -c 'rm -f files/config.json shared_prefs/pin.xml'"
home; sleep 4
python3 tap.py --times 5 --regex '^\d\d:\d\d$' >/dev/null; sleep 1
for round in 1 2; do for d in 1 2 3 4; do python3 tap.py "$d" >/dev/null; done; python3 tap.py Conferma >/dev/null; sleep 1; done
shot v15_3_templates 3
python3 tap.py "Memoria fragile" >/dev/null || true; shot v15_4_after_template 3
adb logcat -d | grep -E "AndroidRuntime|FATAL" -A8 | head -60 > "$OUT/v15_errors.txt" || true
home
