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
# Video rimpicciolito dal browser (WebM di MediaRecorder, senza indice): deve partire anche lui
H2=27b5d161cc09a154578c5549b67ae74f76729714104e06b8540ed1495cdcd7eb
adb push test-recorded.webm /data/local/tmp/v2.webm >/dev/null
echo '{"device":"*","id":43,"text":"Video dal portale","from":"Luca","at":0,"video":"media:'$H2'"}' > /tmp/m2.json
adb push /tmp/m2.json /data/local/tmp/m2.json >/dev/null
adb shell "run-as $P sh -c 'mkdir -p files/video && cp /data/local/tmp/v2.webm files/video/$H2.vid && cp /data/local/tmp/m2.json files/family_message.json'"
adb shell am start -W -n $P/.remote.MessageActivity >/dev/null; shot v15_5_webm 4
python3 tap.py "DI NUOVO" >/dev/null || true; shot v15_6_webm_again 3
python3 tap.py "HO VISTO" >/dev/null || true; sleep 1
# Prima apertura: nessun PIN e nessuna config salvata → modelli pronti
adb shell am force-stop $P
adb shell "run-as $P sh -c 'rm -f files/config.json shared_prefs/pin.xml'"
home; sleep 4
python3 tap.py --times 5 --regex '^\d\d:\d\d$' >/dev/null; sleep 1
for round in 1 2; do for d in 1 2 3 4; do python3 tap.py "$d" >/dev/null; done; python3 tap.py Conferma >/dev/null; sleep 1; done
shot v15_3_templates 3
python3 tap.py "Memoria fragile" >/dev/null || true; shot v15_4_after_template 3
# Album: cartella scelta col selettore di Android, foto e video anche nelle sottocartelle
python3 - <<'PY'
import struct, zlib
def png(path, rgb):
    w = h = 64; raw = b''.join(b'\x00' + bytes(rgb) * w for _ in range(h))
    ch = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + ch(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + ch(b'IDAT', zlib.compress(raw)) + ch(b'IEND', b''))
png('/tmp/a.png', (200, 30, 30)); png('/tmp/b.png', (30, 30, 200)); png('/tmp/c.png', (30, 200, 30))
PY
adb shell mkdir -p /sdcard/Ricordi/Natale/2025 /sdcard/Ricordi/Sent /sdcard/Ricordi/.nascosta
adb push /tmp/a.png /sdcard/Ricordi/top.png >/dev/null
adb push /tmp/b.png /sdcard/Ricordi/Natale/2025/deep.png >/dev/null
adb push test.mp4 /sdcard/Ricordi/Natale/clip.mp4 >/dev/null
adb push /tmp/c.png /sdcard/Ricordi/Sent/sent.png >/dev/null
adb push /tmp/c.png /sdcard/Ricordi/.nascosta/hidden.png >/dev/null
python3 tap.py "Album" >/dev/null || true; sleep 2
python3 tap.py --scroll "+ Aggiungi una cartella" >/dev/null || true; shot v15_7_picker 3
python3 tap.py --regex "^(Mostra radici|Show roots)$" >/dev/null || true; sleep 1
python3 tap.py --regex "^(sdk_gphone|Android SDK|Pixel|SDK)" >/dev/null || true; sleep 2
python3 tap.py "Ricordi" >/dev/null || true; sleep 2; shot v15_8_picker_folder 1
python3 tap.py --regex "(?i)^(usa questa cartella|use this folder)$" >/dev/null || true; sleep 2
python3 tap.py --regex "(?i)^(consenti|allow)$" >/dev/null || true; sleep 2
shot v15_9_album_editor 2
adb shell dumpsys activity provider com.android.externalstorage 2>/dev/null | head -0
python3 tap.py --regex "^(Fatto|OK|Salva)$" >/dev/null || true; sleep 1
python3 tap.py "Salva" >/dev/null || true; sleep 2
home; sleep 6; shot v15_10_home_album 1
adb shell "run-as $P cat files/config.json" | grep -A3 '"folders"' > "$OUT/v15_album_folders.txt" || true
adb logcat -d -s Album:* | tail -20 >> "$OUT/v15_album_folders.txt" || true
adb logcat -d | grep -E "AndroidRuntime|FATAL" -A8 | head -60 > "$OUT/v15_errors.txt" || true
home

# ---- 0.17.0: nuovi riquadri (lente, dove sono, notizie, musica, mi chiami, torcia), opzioni di sicurezza
adb shell mkdir -p /sdcard/Musica/Preferite
adb push test-song.mp3 /sdcard/Musica/Preferite/01-Canzone.mp3 >/dev/null
cat > /tmp/c17.json <<'J'
{"version":1,"pages":[{"cols":2,"rows":5,"tiles":[
 {"id":"clock","col":0,"row":0,"w":2,"h":1,"type":"clock","color":"#FFFFFF"},
 {"id":"lente","col":0,"row":1,"w":1,"h":1,"type":"magnifier","label":"Lente","color":"#FFD54F"},
 {"id":"dove","col":1,"row":1,"w":1,"h":1,"type":"where","label":"Dove sono?","color":"#1A1A1A"},
 {"id":"news","col":0,"row":2,"w":1,"h":1,"type":"news","label":"Notizie","target":"https://www.ansa.it/sito/notizie/topnews/topnews_rss.xml","color":"#1A1A1A"},
 {"id":"musica","col":1,"row":2,"w":1,"h":1,"type":"music","label":"Musica","color":"#FFA726"},
 {"id":"chiamami","col":0,"row":3,"w":1,"h":1,"type":"call_me","label":"Anna","target":"+393331234567","color":"#1B7A34"},
 {"id":"torcia","col":1,"row":3,"w":1,"h":1,"type":"torch","label":"Torcia","color":"#FFD54F"},
 {"id":"sos","col":0,"row":4,"w":2,"h":1,"type":"sos","label":"SOS","color":"#C62828"}]}],
 "sos":{"numbers":["+393331234567"]},
 "emergency":{"name":"Maria Rossi","medications":"Cardioaspirina"},
 "phone":{"remoteAppAlert":true,"scamGuard":true,"blockPremium":true,"callVolumePercent":90,"morningBrief":false}}
J
adb push /tmp/c17.json /data/local/tmp/c17.json >/dev/null
adb shell am force-stop $P
adb shell "run-as $P sh -c 'cp /data/local/tmp/c17.json files/config.json'"
adb shell pm grant $P android.permission.CAMERA 2>/dev/null || true
adb shell pm grant $P android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb emu geo fix 12.4964 41.9028 2>/dev/null || true
home; sleep 4; shot v17_1_home 1
python3 tap.py "Lente" >/dev/null; shot v17_2_lens 5
python3 tap.py "+" >/dev/null || true; python3 tap.py "FERMA" >/dev/null || true; shot v17_3_lens_frozen 2
python3 tap.py "CHIUDI" >/dev/null || true; sleep 1
python3 tap.py "Dove sono?" >/dev/null; shot v17_4_where 15
adb shell input keyevent KEYCODE_BACK; sleep 1; home
python3 tap.py "Notizie" >/dev/null; shot v17_5_news 10
adb shell input keyevent KEYCODE_BACK; sleep 1; home
python3 tap.py "Mi chiami?" >/dev/null || python3 tap.py "Anna" >/dev/null; shot v17_6_callme 2
adb shell input keyevent KEYCODE_BACK; sleep 1; home
# Musica: cartella scelta col selettore di Android
adb shell "run-as $P sh -c 'rm -f shared_prefs/pin.xml'"
python3 tap.py --times 5 --regex '^\d\d:\d\d$' >/dev/null; sleep 1
for round in 1 2; do for d in 1 2 3 4; do python3 tap.py "$d" >/dev/null; done; python3 tap.py Conferma >/dev/null; sleep 1; done
python3 tap.py "Musica" >/dev/null || true; sleep 2
python3 tap.py --scroll "+ Aggiungi una cartella" >/dev/null || true; sleep 3
python3 tap.py --regex "^(Mostra radici|Show roots)$" >/dev/null || true; sleep 1
python3 tap.py --regex "^(sdk_gphone|Android SDK|Pixel|SDK)" >/dev/null || true; sleep 2
python3 tap.py "Musica" >/dev/null || true; sleep 2
python3 tap.py --regex "(?i)^(usa questa cartella|use this folder)$" >/dev/null || true; sleep 2
python3 tap.py --regex "(?i)^(consenti|allow)$" >/dev/null || true; sleep 2
python3 tap.py "Fatto" >/dev/null || true; sleep 1; python3 tap.py "Salva" >/dev/null || true; sleep 2
home; sleep 2
python3 tap.py "Musica" >/dev/null; sleep 4; shot v17_7_music 1
python3 tap.py "ASCOLTA" >/dev/null || true; shot v17_8_music_play 4
adb shell dumpsys activity services $P | grep -E "RadioService|isForeground" | head -3 > "$OUT/v17_music.txt"
python3 tap.py "FERMA" >/dev/null || true
adb logcat -d | grep -E "FATAL|AndroidRuntime: Process|Caused by" -A6 | head -60 > "$OUT/v17_errors.txt" || true
adb logcat -d -s Safety:* Magnifier:* News:* Torch:* | tail -20 >> "$OUT/v17_errors.txt" || true
home

# ---- 0.18.0: stile unificato in tre lingue (testi lunghi: tedesco e francese), CHIUDI unico, simboli,
# conferme grandi, fascia degli avvisi, schermate vuote
cat > /tmp/c18.json <<'J'
{"version":1,"pages":[{"cols":2,"rows":6,"tiles":[
 {"id":"clock","col":0,"row":0,"w":2,"h":1,"type":"clock","color":"#FFFFFF"},
 {"id":"lente","col":0,"row":1,"type":"magnifier","label":"","color":"#FFD54F"},
 {"id":"dove","col":1,"row":1,"type":"where","label":"","color":"#1A1A1A"},
 {"id":"news","col":0,"row":2,"type":"news","label":"","target":"https://example.invalid/feed.xml","color":"#1A1A1A"},
 {"id":"radio","col":1,"row":2,"type":"radio","label":"","color":"#FFA726"},
 {"id":"chiamami","col":0,"row":3,"type":"call_me","label":"Anna","target":"+393331234567","color":"#1B7A34"},
 {"id":"cerca","col":1,"row":3,"type":"search","label":"","color":"#1A1A1A"},
 {"id":"pill","col":0,"row":4,"type":"call","target":"3331234567","label":"Luca","color":"#1F4FA8","icon":{"kind":"symbol","ref":"pill"}},
 {"id":"key","col":1,"row":4,"type":"call","target":"3337654321","label":"Badante","color":"#6A1B9A","icon":{"kind":"symbol","ref":"key"}},
 {"id":"sos","col":0,"row":5,"w":2,"h":1,"type":"sos","label":"SOS","color":"#C62828"}]}],
 "sos":{"numbers":["+393331234567"]},
 "emergency":{"name":"Maria Rossi","medications":"Cardioaspirina","allergies":"Penicillina"},
 "reminders":[{"id":"r1","time":"08:00","title":"Pastiglia della pressione","icon":"pill","detail":"Una compressa dopo colazione"}],
 "phone":{"holdToOpen":true,"morningBrief":false}}
J
adb push /tmp/c18.json /data/local/tmp/c18.json >/dev/null
for L in it de fr; do
  adb shell cmd locale set-app-locales $P --locales $L
  adb shell am force-stop $P
  echo '{"device":"*","id":7,"text":"Domenica pranzo da noi alle 13.","from":"Anna","at":1}' > /tmp/m18.json
  adb push /tmp/m18.json /data/local/tmp/m18.json >/dev/null
  adb shell "run-as $P sh -c 'cp /data/local/tmp/c18.json files/config.json; cp /data/local/tmp/m18.json files/family_message.json; rm -f shared_prefs/pin.xml'"
  home; shot "v18_${L}_1_home" 4
  adb shell am start -W -n $P/.phone.FallActivity >/dev/null; shot "v18_${L}_3_fall" 3
  python3 tap.py --regex "^(STO BENE|MIR GEHT'S GUT|ÇA VA|I'M OK)$" >/dev/null || true; adb shell input keyevent BACK; sleep 1
  adb shell am start -W -n $P/.reminders.ReminderActivity --es id r1 >/dev/null; shot "v18_${L}_4_reminder" 3
  adb shell input keyevent BACK; sleep 1
  echo '{"device":"*","id":7,"text":"Domenica pranzo da noi alle 13.","from":"Anna","at":1}' > /tmp/m18.json
  adb push /tmp/m18.json /data/local/tmp/m18.json >/dev/null
  adb shell "run-as $P sh -c 'cp /data/local/tmp/m18.json files/family_message.json'"
  adb shell am start -W -n $P/.remote.MessageActivity >/dev/null; shot "v18_${L}_5_message" 3
  adb shell input keyevent BACK; sleep 1; home
  # Pressione lunga (tenere premuto è acceso) sui riquadri degli strumenti
  # Riquadri senza scritta (vale la scritta predefinita): coordinate dalla griglia 2x6 con la fascia del messaggio
  for t in "lente:283 929" "dove:797 929" "news:283 1236" "radio:797 1236" "chiamami:283 1543" "cerca:797 1543"; do
    XY=${t#*:}; adb shell input swipe $XY $XY 1600; shot "v18_${L}_6_${t%%:*}" 5
    adb shell input keyevent BACK; sleep 1; home; sleep 1
  done
  # Tocco breve con «tenere premuto»: fascia grande in basso invece del toast
  python3 tap.py "Luca" >/dev/null || true; shot "v18_${L}_2_hint" 1
  # Impostazioni: telefono (icone a tinta unita), promemoria (modelli), riquadro (simboli), conferma eliminazione
  python3 tap.py --times 5 --regex '^\d\d:\d\d$' >/dev/null; sleep 1
  for round in 1 2; do for d in 1 2 3 4; do python3 tap.py "$d" >/dev/null; done; python3 tap.py --regex "^(Conferma|Bestätigen|Confirmer)$" >/dev/null; sleep 1; done
  shot "v18_${L}_7_settings" 2
  python3 tap.py Badante >/dev/null; sleep 2
  python3 tap.py --scroll --regex "^(Simbolo|Symbol|Symbole)$" >/dev/null || true; shot "v18_${L}_8_symbols" 2
  python3 tap.py --scroll --regex "^(Elimina riquadro|Kachel löschen|Supprimer la tuile)$" >/dev/null || true; shot "v18_${L}_9_confirm" 2
  adb shell input keyevent BACK; sleep 1; adb shell input keyevent BACK; sleep 1
  home; adb shell input keyevent BACK
done
adb shell cmd locale set-app-locales $P --locales it
adb logcat -d | grep -E "FATAL|AndroidRuntime: Process|Caused by" -A6 | head -60 > "$OUT/v18_errors.txt" || true
home
