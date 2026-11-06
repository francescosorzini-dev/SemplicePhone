<?php
/**
 * SemplicePhone – server della configurazione remota (single file, PHP 8.1+ con ext-sodium).
 *
 * Telefono:  GET  index.php?device=<id>            → busta firmata {v, payload, sig} (ETag / 304)
 *            POST index.php?device=<id>&report=1   → stato (batteria, esiti promemoria, elenco app)
 *            POST index.php?device=<id>&event=1    → eventi urgenti (SOS, batteria, promemoria saltato)
 *            Header X-Device-Secret: segreto del telefono, registrato al primo contatto (TOFU).
 * Gestione:  index.php senza parametri → pannello con login.
 * Cron:      facoltativo, `php index.php cron` ogni 10 minuti (altrimenti i controlli partono
 *            da soli quando arriva una richiesta).
 *
 * Dati in ./data (protetta da .htaccess). La chiave privata Ed25519 non esce mai dal server.
 *
 * Copyright (C) 2026 Francesco Sorzini
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Questo programma è software libero: puoi ridistribuirlo e/o modificarlo secondo i termini della
 * GNU Affero General Public License, versione 3 o successiva. Senza alcuna garanzia.
 * Chi lo offre ad altri via rete deve rendere disponibile il codice sorgente (sezione 13): se lo
 * modifichi, aggiorna SOURCE_URL.
 */
declare(strict_types=1);

const DATA = __DIR__ . '/data';
if (!ini_get('date.timezone')) date_default_timezone_set('Europe/Rome');
const PBKDF2_ITER = 60000;
const MAX_REPORT = 262144;
const HISTORY_KEEP = 30;
const SOURCE_URL = 'https://github.com/francescosorzini-dev/SemplicePhone';
const VERSION = '0.5.1'; // aggiornata in automatico nelle release
const TILE_TYPES = ['app', 'call', 'wa_video', 'sms', 'url', 'shortcut', 'clock', 'sos', 'weather', 'photos', 'missed_calls', 'search'];
const MEDIA_MAX = 8 * 1024 * 1024;
// Versione dell'app che conosce meteo, foto e promemoria in una data: le precedenti li ignorano o scartano.
const APP_FEATURES_090 = '0.9.0';
// Versione che conosce il riquadro chiamate perse e il pulsante «Casa».
const APP_FEATURES_0100 = '0.10.0';
const EVENT_TYPES = ['sos', 'reminder_missed', 'battery', 'offline', 'online', 'test', 'call_blocked', 'location', 'ring_done', 'message_read'];
// Versione che riceve messaggi, «Fai squillare» e la risposta automatica.
const APP_FEATURES_0130 = '0.13.0';
const MESSAGE_MAX = 300;
// Testi delle lingue: nelle release sono incorporati qui (file unico); in sviluppo si leggono da lang/*.json.
const LANG_DATA = null;

// ---------------------------------------------------------------- bootstrap
if (!extension_loaded('sodium')) { http_response_code(500); exit('ext-sodium mancante'); }
foreach ([DATA, DATA . '/devices', DATA . '/reports', DATA . '/events', DATA . '/history', DATA . '/media'] as $d) if (!is_dir($d)) mkdir($d, 0700, true);
if (!is_file(DATA . '/.htaccess')) file_put_contents(DATA . '/.htaccess',
    "<IfModule mod_authz_core.c>\n  Require all denied\n</IfModule>\n<IfModule !mod_authz_core.c>\n  Deny from all\n</IfModule>\n");
if (!is_file(DATA . '/signing.key')) {
    file_put_contents(DATA . '/signing.key', sodium_crypto_sign_keypair());
    chmod(DATA . '/signing.key', 0600);
}
function keypair(): string { return file_get_contents(DATA . '/signing.key'); }
function public_key_b64(): string { return base64_encode(sodium_crypto_sign_publickey(keypair())); }

function valid_id(string $id): bool { return (bool)preg_match('/^[a-z0-9]{6,32}$/', $id); }
function dev_path(string $id, string $ext): string { return DATA . "/devices/$id.$ext"; }
function media_path(string $hash): ?string { return preg_match('/^[0-9a-f]{64}$/', $hash) ? DATA . "/media/$hash.img" : null; }
function serve_media(string $hash): void {
    $f = media_path($hash);
    if (!$f || !is_file($f)) { http_response_code(404); exit; }
    $info = @getimagesize($f);
    header('Content-Type: ' . ($info['mime'] ?? 'application/octet-stream'));
    header('Content-Length: ' . filesize($f));
    header('Cache-Control: private, max-age=31536000, immutable');
    readfile($f);
    exit;
}
function read_json(string $file, $default = null) {
    if (!is_file($file)) return $default;
    $v = json_decode((string)file_get_contents($file), true);
    return $v ?? $default;
}
function write_atomic(string $file, string $data): void {
    $tmp = $file . '.tmp' . bin2hex(random_bytes(4));
    file_put_contents($tmp, $data, LOCK_EX);
    rename($tmp, $file);
}
function append_jsonl(string $file, array $row): void {
    file_put_contents($file, json_encode($row, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . "\n", FILE_APPEND | LOCK_EX);
}
function tail_jsonl(string $file, int $n): array {
    if (!is_file($file)) return [];
    $out = [];
    foreach (array_slice(file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES), -$n) as $l) { $r = json_decode($l, true); if (is_array($r)) $out[] = $r; }
    return $out;
}
function device_ids(): array {
    $ids = [];
    foreach (glob(DATA . '/devices/*.{secret,meta.json,config.json}', GLOB_BRACE) ?: [] as $f) $ids[explode('.', basename($f))[0]] = true;
    ksort($ids);
    return array_keys($ids);
}
function device_name(string $id): string {
    $m = read_json(dev_path($id, 'meta.json'), []);
    return ($m['name'] ?? '') !== '' ? $m['name'] : t('phone_n', $id);
}

// ---------------------------------------------------------------- impostazioni e avvisi
function settings(): array {
    return array_replace_recursive([
        'telegramToken' => '', 'telegramChat' => '', 'emailTo' => '', 'emailFrom' => '', 'panelUrl' => '',
        'notify' => ['sos' => true, 'reminder_missed' => true, 'battery' => true, 'offline' => true, 'call_blocked' => false, 'location' => true, 'ring_done' => true, 'message_read' => true],
        'offlineHours' => 24,
        // Riepilogo settimanale il lunedì mattina (stessi canali degli avvisi).
        'weeklySummary' => true,
        // Fuso orario per date e ore del portale (il server spesso è in UTC).
        'timezone' => 'Europe/Rome',
        // Lingua degli avvisi: quella con cui l'amministratore usa il pannello.
        'lang' => 'it',
    ], read_json(DATA . '/settings.json', []));
}
// ---------------------------------------------------------------- lingue
/** Lingue disponibili: quelle incorporate o i file in lang/ (una lingua nuova non richiede codice). */
function langs(): array {
    static $list = null;
    if ($list !== null) return $list;
    $list = LANG_DATA !== null ? array_keys(LANG_DATA) : array_map(fn($f) => basename($f, '.json'), glob(__DIR__ . '/lang/*.json') ?: []);
    sort($list);
    return $list ?: ['en'];
}
function lang_table(string $l): array {
    static $cache = [];
    if (isset($cache[$l])) return $cache[$l];
    if (LANG_DATA !== null) return $cache[$l] = LANG_DATA[$l] ?? [];
    $f = __DIR__ . "/lang/$l.json";
    return $cache[$l] = is_file($f) ? (json_decode((string)file_get_contents($f), true) ?: []) : [];
}
/** Testo nella lingua corrente ($GLOBALS['LANG']), con {0}, {1}… sostituiti; ripiego sull'inglese. */
function t(string $k, ...$a): string {
    $s = lang_table($GLOBALS['LANG'] ?? 'en')[$k] ?? lang_table('en')[$k] ?? $k;
    foreach ($a as $i => $v) $s = str_replace('{' . $i . '}', (string)$v, $s);
    return $s;
}
function th(string $k, ...$a): string { return h(t($k, ...$a)); }
function ev_title(string $type): string { return in_array($type, EVENT_TYPES, true) ? t('ev_' . $type) : $type; }
/** Lingua del pannello: ?lang=, poi cookie, poi lingua del browser, altrimenti inglese. */
function pick_lang(): string {
    $q = $_GET['lang'] ?? null;
    if (is_string($q) && in_array($q, langs(), true)) {
        setcookie('lang', $q, ['expires' => time() + 31536000, 'samesite' => 'Lax', 'httponly' => true, 'secure' => !empty($_SERVER['HTTPS'])]);
        return $q;
    }
    $c = (string)($_COOKIE['lang'] ?? '');
    if (in_array($c, langs(), true)) return $c;
    foreach (explode(',', (string)($_SERVER['HTTP_ACCEPT_LANGUAGE'] ?? '')) as $part) {
        $l = strtolower(substr(trim($part), 0, 2));
        if (in_array($l, langs(), true)) return $l;
    }
    return 'en';
}

function save_settings(array $s): void { write_atomic(DATA . '/settings.json', json_encode($s, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE)); }

function http_post(string $url, array $fields): array {
    $body = http_build_query($fields);
    if (function_exists('curl_init')) {
        $c = curl_init($url);
        curl_setopt_array($c, [CURLOPT_POST => true, CURLOPT_POSTFIELDS => $body, CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 10]);
        $res = curl_exec($c); $code = (int)curl_getinfo($c, CURLINFO_HTTP_CODE); $err = curl_error($c); curl_close($c);
        return [$code >= 200 && $code < 300, $code ? "HTTP $code" : $err];
    }
    $ctx = stream_context_create(['http' => ['method' => 'POST', 'header' => "Content-Type: application/x-www-form-urlencoded\r\n", 'content' => $body, 'timeout' => 10, 'ignore_errors' => true]]);
    $res = @file_get_contents($url, false, $ctx);
    $code = isset($http_response_header[0]) && preg_match('/\s(\d{3})\s/', $http_response_header[0], $m) ? (int)$m[1] : 0;
    return [$code >= 200 && $code < 300, $code ? "HTTP $code" : t('conn_failed')];
}

/** Invia un avviso su tutti i canali configurati. Restituisce l'esito per canale. */
function send_alert(string $title, string $text, ?string $deviceId = null): array {
    $s = settings();
    $who = $deviceId ? device_name($deviceId) : 'SemplicePhone';
    $link = $s['panelUrl'] !== '' ? "\n" . $s['panelUrl'] . ($deviceId ? '?edit=' . $deviceId : '') : '';
    $msg = "$title — $who\n$text$link";
    $out = [];
    if ($s['telegramToken'] !== '' && $s['telegramChat'] !== '') {
        foreach (array_filter(array_map('trim', explode(',', $s['telegramChat']))) as $chat) {
            [$ok, $info] = http_post('https://api.telegram.org/bot' . $s['telegramToken'] . '/sendMessage',
                ['chat_id' => $chat, 'text' => $msg, 'disable_web_page_preview' => 'true']);
            $out[] = ['canale' => "Telegram $chat", 'ok' => $ok, 'info' => $info];
        }
    }
    if ($s['emailTo'] !== '') {
        $from = $s['emailFrom'] !== '' ? $s['emailFrom'] : 'semplicephone@' . preg_replace('/[^a-z0-9.\-]/i', '', $_SERVER['HTTP_HOST'] ?? 'localhost');
        $headers = "From: $from\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit";
        $ok = @mail($s['emailTo'], '=?UTF-8?B?' . base64_encode("$title — $who") . '?=', $msg, $headers);
        $out[] = ['canale' => 'Email ' . $s['emailTo'], 'ok' => (bool)$ok, 'info' => $ok ? t('email_sent') : t('mail_failed')];
    }
    append_jsonl(DATA . '/alerts.jsonl', ['at' => time(), 'device' => $deviceId, 'title' => $title, 'text' => $text, 'result' => $out]);
    return $out;
}

/**
 * Controlli periodici, al massimo ogni 10 minuti (a ogni contatto di un telefono o dal cron):
 * telefoni che non si fanno sentire da troppo (un avviso per ogni sparizione) e riepilogo
 * settimanale del lunedì mattina.
 */
function run_checks(bool $force = false): void {
    $stamp = DATA . '/checks.json';
    $st = read_json($stamp, []);
    if (!$force && time() - (int)($st['at'] ?? 0) < 600) return;
    $st['at'] = time();
    $s = settings();
    $week = date('o-\\WW');
    // Prima esecuzione: si parte dalla settimana in corso, senza mandare subito un riepilogo.
    if (!isset($st['weekly'])) $st['weekly'] = $week;
    $sendWeekly = !empty($s['weeklySummary']) && $st['weekly'] !== $week && ((int)date('N') > 1 || (int)date('G') >= 9);
    if ($sendWeekly) $st['weekly'] = $week;
    write_atomic($stamp, json_encode($st));

    $limit = max(1, (int)$s['offlineHours']) * 3600;
    foreach (device_ids() as $id) {
        $m = read_json(dev_path($id, 'meta.json'), []);
        $seen = (int)($m['lastSeen'] ?? 0);
        if (!empty($s['notify']['offline']) && $seen && empty($m['offlineAlerted']) && empty($m['ignoreOffline']) && time() - $seen >= $limit) {
            $m['offlineAlerted'] = true;
            write_atomic(dev_path($id, 'meta.json'), json_encode($m));
            $text = t('offline_text', intdiv(time() - $seen, 3600));
            // Anche tra gli avvisi del telefono, così compare nel riepilogo e nello storico.
            append_jsonl(DATA . "/events/$id.jsonl", ['type' => 'offline', 'at' => time() * 1000, 'text' => $text, 'receivedAt' => time(), 'server' => true]);
            send_alert(ev_title('offline'), $text, $id);
        }
        if ($sendWeekly && $seen) send_alert(t('weekly_title'), implode("\n", weekly_lines($id)), $id);
    }
}

/** Numeri degli ultimi [days] giorni di un telefono, da report ed eventi già ricevuti. */
function weekly_stats(string $id, int $days = 7): array {
    $since = time() - $days * 86400;
    $out = ['done' => 0, 'missed' => 0, 'snoozed' => 0, 'sos' => 0, 'call_blocked' => 0, 'battery' => 0, 'offline' => 0, 'batteryMin' => null];
    foreach (tail_jsonl(DATA . "/reports/$id.jsonl", 1500) as $r) {
        if ((int)($r['receivedAt'] ?? 0) < $since) continue;
        if (isset($r['battery']) && (int)$r['battery'] >= 0 && empty($r['charging'])) {
            $out['batteryMin'] = $out['batteryMin'] === null ? (int)$r['battery'] : min($out['batteryMin'], (int)$r['battery']);
        }
        foreach ((array)($r['reminderEvents'] ?? []) as $ev) {
            if ((int)(($ev['at'] ?? 0) / 1000) < $since) continue;
            $o = (string)($ev['outcome'] ?? '');
            if ($o === 'preso') $out['done']++; elseif ($o === 'non_confermato') $out['missed']++; elseif ($o === 'rinviato') $out['snoozed']++;
        }
    }
    foreach (tail_jsonl(DATA . "/events/$id.jsonl", 1000) as $ev) {
        if ((int)($ev['receivedAt'] ?? 0) < $since) continue;
        $type = (string)($ev['type'] ?? '');
        if (isset($out[$type]) && $type !== 'batteryMin') $out[$type]++;
    }
    return $out;
}

/** Righe del riepilogo, testo semplice: per il messaggio settimanale e per la scheda del telefono. */
function weekly_lines(string $id): array {
    $w = weekly_stats($id);
    $m = read_json(dev_path($id, 'meta.json'), []);
    return [
        t('weekly_reminders', $w['done'], $w['missed'], $w['snoozed']),
        t('weekly_sos', $w['sos']),
        t('weekly_blocked', $w['call_blocked']),
        t('weekly_battery', $w['battery'], $w['batteryMin'] === null ? t('weekly_none') : $w['batteryMin'] . '%'),
        t('weekly_offline', $w['offline']),
        t('weekly_last_seen', ago(isset($m['lastSeen']) ? (int)$m['lastSeen'] : null)),
    ];
}

/** Hash delle foto usate da una config (riquadri foto e promemoria). */
function phone_media_refs($cfg): array {
    $out = [];
    foreach ((array)($cfg['pages'] ?? []) as $p) foreach ((array)($p['tiles'] ?? []) as $t) foreach ((array)($t['photos'] ?? []) as $ref) $out[] = (string)$ref;
    foreach ((array)($cfg['reminders'] ?? []) as $r) $out[] = (string)($r['photo'] ?? '');
    foreach ((array)($cfg['pages'] ?? []) as $p) foreach ((array)($p['tiles'] ?? []) as $t) $out[] = (string)($t['icon']['ref'] ?? '');
    $out = array_map(fn($r) => substr($r, 6), array_filter($out, fn($r) => preg_match('/^media:[0-9a-f]{64}$/', $r)));
    return array_values(array_unique($out));
}

/**
 * Differenze tra due configurazioni, a parole («riquadro Maria aggiunto»…), al massimo 5.
 * Si confrontano solo i campi che contano: i valori predefiniti aggiunti dall'app non sono differenze.
 */
function config_diff(array $a, array $b): array {
    $tiles = function (array $c): array {
        $out = [];
        foreach ((array)($c['pages'] ?? []) as $pi => $p) foreach ((array)($p['tiles'] ?? []) as $t) {
            $icon = (array)($t['icon'] ?? []);
            $out[(string)($t['id'] ?? '')] = [
                'label' => (string)($t['label'] ?? ''), 'type' => (string)($t['type'] ?? ''), 'target' => (string)($t['target'] ?? ''),
                'pos' => $pi . ':' . (int)($t['col'] ?? 0) . ',' . (int)($t['row'] ?? 0) . ',' . (int)($t['w'] ?? 1) . ',' . (int)($t['h'] ?? 1),
                'look' => strtoupper((string)($t['color'] ?? '')) . '|' . ($icon['kind'] ?? 'auto') . '|' . ($icon['ref'] ?? '') . '|' . json_encode($t['photos'] ?? []),
            ];
        }
        return $out;
    };
    $name = fn($t) => $t['label'] !== '' ? $t['label'] : $t['type'];
    $out = [];
    $ta = $tiles($a); $tb = $tiles($b);
    foreach ($tb as $id => $t) if (!isset($ta[$id])) $out[] = t('chg_tile_added', $name($t));
    foreach ($ta as $id => $t) if (!isset($tb[$id])) $out[] = t('chg_tile_removed', $name($t));
    foreach ($tb as $id => $t) if (isset($ta[$id]) && $ta[$id] !== $t) {
        $o = $ta[$id];
        $out[] = $o['pos'] !== $t['pos'] && array_diff_key($o, ['pos' => 1]) === array_diff_key($t, ['pos' => 1])
            ? t('chg_tile_moved', $name($t)) : t('chg_tile_changed', $name($t));
    }
    if (count((array)($a['pages'] ?? [])) !== count((array)($b['pages'] ?? []))) $out[] = t('chg_pages', count((array)($b['pages'] ?? [])));
    $rem = fn($c) => array_column(array_map(fn($r) => ['id' => (string)($r['id'] ?? ''), 'v' => json_encode([$r['time'] ?? '', $r['title'] ?? '', $r['days'] ?? [], $r['date'] ?? '', $r['enabled'] ?? true, $r['photo'] ?? '', $r['icon'] ?? 'pill'])], (array)($c['reminders'] ?? [])), 'v', 'id');
    $ra = $rem($a); $rb = $rem($b);
    if ($ra != $rb) $out[] = t('chg_reminders', count($rb));
    $sos = fn($c) => json_encode([(array)($c['sos']['numbers'] ?? []), (string)($c['sos']['message'] ?? '')]);
    if ($sos($a) !== $sos($b)) $out[] = t('chg_sos');
    $pa = (array)($a['phone'] ?? []); $pb = (array)($b['phone'] ?? []);
    foreach (array_intersect_key($pb, $pa) as $k => $v) if ($pa[$k] != $v) { $out[] = t('chg_phone'); break; }
    return array_slice($out, 0, 5);
}

/** Accessi mancanti sul telefono per le funzioni attive (dal report del telefono). */
function access_warnings(array $meta): array {
    $keys = ['overlay' => 'acc_overlay', 'callLog' => 'acc_call_log', 'screening' => 'acc_screening', 'contacts' => 'acc_contacts'];
    $out = [];
    foreach ((array)($meta['access'] ?? []) as $k => $ok) if ($ok === false && isset($keys[$k])) $out[] = t($keys[$k]);
    return $out;
}

$LANG = in_array(settings()['lang'], langs(), true) ? settings()['lang'] : 'en';
// Date e ore nel fuso scelto (predefinito Europe/Rome), non in quello del server.
if (in_array(settings()['timezone'], timezone_identifiers_list(), true)) date_default_timezone_set(settings()['timezone']);

if (PHP_SAPI === 'cli') {
    if (($argv[1] ?? '') === 'cron') { run_checks(true); exit(0); }
    fwrite(STDERR, "Uso: php index.php cron\n"); exit(1);
}

// ---------------------------------------------------------------- API telefono
if (isset($_GET['device'])) {
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    $id = (string)$_GET['device'];
    if (!valid_id($id)) { http_response_code(400); exit('{"error":"device"}'); }

    $secret = (string)($_SERVER['HTTP_X_DEVICE_SECRET'] ?? '');
    if (strlen($secret) < 32 || strlen($secret) > 128) { http_response_code(401); exit('{"error":"secret"}'); }
    $secretFile = dev_path($id, 'secret');
    if (!is_file($secretFile)) {
        // Primo contatto: si registra il segreto (trust on first use).
        write_atomic($secretFile, password_hash($secret, PASSWORD_DEFAULT));
    } elseif (!password_verify($secret, (string)file_get_contents($secretFile))) {
        http_response_code(403); exit('{"error":"secret mismatch"}');
    }

    $meta = read_json(dev_path($id, 'meta.json'), []);
    $meta['lastSeen'] = time();
    $meta['lastIp'] = $_SERVER['REMOTE_ADDR'] ?? '';
    $backOnline = !empty($meta['offlineAlerted']);
    unset($meta['offlineAlerted']);

    $readBody = function (): array {
        $body = file_get_contents('php://input', false, null, 0, MAX_REPORT + 1);
        if ($body === false || strlen($body) > MAX_REPORT) { http_response_code(413); exit('{"error":"size"}'); }
        $r = json_decode($body, true);
        if (!is_array($r)) { http_response_code(400); exit('{"error":"json"}'); }
        return $r;
    };

    // Immagini della config (foto di famiglia, foto dei promemoria): il telefono ne verifica l'hash.
    if (isset($_GET['media'])) {
        write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
        $hash = (string)$_GET['media'];
        // Foto scelte sul telefono: le carica il telefono stesso, solo se la sua config le usa.
        if ($_SERVER['REQUEST_METHOD'] === 'POST') {
            $wanted = phone_media_refs(read_json(dev_path($id, 'phone.json'), [])['config'] ?? []);
            if (!media_path($hash) || !in_array($hash, $wanted, true)) { http_response_code(403); exit('{"error":"media"}'); }
            $body = file_get_contents('php://input', false, null, 0, MEDIA_MAX + 1);
            $info = $body !== false && strlen($body) <= MEDIA_MAX ? @getimagesizefromstring($body) : false;
            if (!$info || !in_array($info['mime'], ['image/jpeg', 'image/png', 'image/webp'], true) || hash('sha256', $body) !== $hash) {
                http_response_code(400); exit('{"error":"image"}');
            }
            if (!is_file(media_path($hash))) write_atomic(media_path($hash), $body);
            http_response_code(204);
            exit;
        }
        serve_media($hash);
    }

    // Messaggio della famiglia, firmato come la configurazione.
    if (isset($_GET['message'])) {
        write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
        $mf = dev_path($id, 'message.json');
        if (!is_file($mf)) { http_response_code(404); exit('{"error":"none"}'); }
        readfile($mf);
        exit;
    }

    if (isset($_GET['report']) && $_SERVER['REQUEST_METHOD'] === 'POST') {
        $r = $readBody();
        if (isset($r['apps']) && is_array($r['apps'])) {
            $apps = [];
            foreach (array_slice($r['apps'], 0, 800) as $a) {
                $pkg = (string)($a['pkg'] ?? ''); $label = trim(strip_tags((string)($a['label'] ?? '')));
                if (preg_match('/^[A-Za-z][\w]*(\.[\w]+)+$/', $pkg) && $label !== '') $apps[] = ['pkg' => $pkg, 'label' => mb_substr($label, 0, 60)];
            }
            usort($apps, fn($a, $b) => strcasecmp($a['label'], $b['label']));
            write_atomic(dev_path($id, 'apps.json'), json_encode($apps, JSON_UNESCAPED_UNICODE));
            unset($r['apps']);
        }
        $r['receivedAt'] = time();
        append_jsonl(DATA . "/reports/$id.jsonl", $r);
        foreach (['battery', 'charging', 'appVersion', 'lastResult', 'badgesAccess', 'edition'] as $k) $meta[$k] = $r[$k] ?? null;
        $meta['access'] = isset($r['access']) && is_array($r['access'])
            ? array_map('boolval', array_intersect_key($r['access'], array_flip(['overlay', 'callLog', 'screening', 'contacts']))) : null;
        $meta['appliedSeq'] = $r['seq'] ?? null;
        // Config attuale del telefono (anche quella cambiata dalle sue impostazioni): l'editor web
        // la carica se è più recente dell'ultima pubblicazione.
        $needMedia = [];
        if (isset($r['config']) && is_array($r['config']) && isset($r['config']['pages'])) {
            write_atomic(dev_path($id, 'phone.json'), json_encode([
                'config' => $r['config'],
                'source' => ($r['configSource'] ?? '') === 'remote' ? 'remote' : 'local',
                'at' => (int)(((int)($r['configAt'] ?? 0)) / 1000) ?: time(),
                'receivedAt' => time(),
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
            $needMedia = array_values(array_filter(phone_media_refs($r['config']), fn($h) => !is_file(media_path($h))));
        }
        write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
        // Modifica fatta sul telefono (o portale mai usato per questo telefono): diventa la versione
        // attuale anche qui, con la voce «dal telefono» nello storico. Solo se c'è davvero qualcosa di diverso.
        if (isset($r['config']) && is_array($r['config']) && isset($r['config']['pages'])) {
            $curFile = dev_path($id, 'config.json');
            $cur = is_file($curFile) ? json_decode((string)file_get_contents($curFile), true) : null;
            $changes = is_array($cur) ? config_diff($cur, $r['config']) : [t('chg_first')];
            if ((($r['configSource'] ?? '') === 'local' || !is_array($cur)) && $changes) {
                write_atomic($curFile, json_encode($r['config'], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
                publish($id, $r['config'], null, ['source' => 'phone', 'changes' => $changes]);
            }
        }
        if ($backOnline) send_alert(ev_title('online'), t('back_online_text'), $id);
        // Il telefono rimanda l'elenco app solo se cambia: se qui manca (portale nuovo, telefono
        // rimosso e ricollegato…) glielo si chiede esplicitamente.
        echo json_encode(['ok' => true, 'needApps' => !is_file(dev_path($id, 'apps.json')), 'needMedia' => $needMedia]);
        exit;
    }

    if (isset($_GET['event']) && $_SERVER['REQUEST_METHOD'] === 'POST') {
        $r = $readBody();
        $s = settings();
        $seenKeys = $meta['eventKeys'] ?? [];
        foreach (array_slice((array)($r['events'] ?? []), 0, 200) as $ev) {
            if (!is_array($ev)) continue;
            $type = (string)($ev['type'] ?? '');
            $key = $type . '@' . (string)($ev['at'] ?? '');
            if (in_array($key, $seenKeys, true)) continue; // reinvio dopo una risposta persa
            $seenKeys[] = $key;
            $ev['receivedAt'] = time();
            append_jsonl(DATA . "/events/$id.jsonl", $ev);
            if ($type === 'location') unset($meta['locateRequest']);
            if ($type === 'ring_done') unset($meta['ringRequest']);
            if ($type === 'message_read' && isset($meta['message']) && (string)($ev['message'] ?? '') === (string)$meta['message']['id']) $meta['message']['readAt'] = time();
            if (in_array($type, EVENT_TYPES, true) && !empty($s['notify'][$type])) {
                send_alert(ev_title($type), mb_substr((string)($ev['text'] ?? ''), 0, 600), $id);
            }
        }
        $meta['eventKeys'] = array_slice($seenKeys, -100);
        write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
        if ($backOnline) send_alert(ev_title('online'), t('back_online_text'), $id);
        http_response_code(204);
        exit;
    }

    write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
    if ($backOnline) send_alert(ev_title('online'), t('back_online_text'), $id);
    run_checks();
    // «Dov'è?»: richiesta valida per un'ora, il telefono risponde con un evento «location».
    if (time() - (int)($meta['locateRequest'] ?? 0) < 3600) header('X-Locate: 1');
    // «Fai squillare» (valido un'ora) e messaggio non ancora letto: il telefono li riconosce dall'id.
    if (time() - (int)($meta['ringRequest'] ?? 0) < 3600) header('X-Ring: ' . (int)$meta['ringRequest']);
    if (isset($meta['message']) && empty($meta['message']['readAt'])) header('X-Message: ' . (int)$meta['message']['id']);
    $env = dev_path($id, 'envelope.json');
    if (!is_file($env)) { http_response_code(404); exit('{"error":"none"}'); }
    // ETag = versione pubblicata. Tollerante al suffisso "-gzip" che Apache aggiunge con mod_deflate.
    $etag = '"' . (int)($meta['seq'] ?? 0) . '"';
    header("ETag: $etag");
    $norm = fn(string $t) => str_replace(['W/', '"', '-gzip'], '', trim($t));
    if (isset($_SERVER['HTTP_IF_NONE_MATCH']) && $norm((string)$_SERVER['HTTP_IF_NONE_MATCH']) === $norm($etag)) {
        http_response_code(304);
        exit;
    }
    readfile($env);
    exit;
}

// ---------------------------------------------------------------- validazione config (come l'app)
function validate_config($cfg): array {
    $err = [];
    if (!is_object($cfg)) return [t('v_not_object')];
    $pages = $cfg->pages ?? null;
    if (!is_array($pages) || !$pages) return [t('v_no_pages')];
    $ids = [];
    foreach ($pages as $pi => $p) {
        $cols = (int)($p->cols ?? 2); $rows = (int)($p->rows ?? 5);
        if ($cols < 1 || $cols > 4 || $rows < 1 || $rows > 8) { $err[] = t('v_grid', $pi + 1, $cols, $rows); continue; }
        $grid = [];
        foreach (($p->tiles ?? []) as $t) {
            $id = (string)($t->id ?? '');
            if ($id === '') { $err[] = t('v_no_id', $pi + 1); continue; }
            if (isset($ids[$id])) $err[] = t('v_dup_id', $id);
            $ids[$id] = true;
            if (!in_array($t->type ?? '', TILE_TYPES, true)) $err[] = t('v_bad_type', $id);
            $c = (int)($t->col ?? -1); $r = (int)($t->row ?? -1); $w = (int)($t->w ?? 1); $h = (int)($t->h ?? 1);
            if ($c < 0 || $r < 0 || $w < 1 || $h < 1 || $c + $w > $cols || $r + $h > $rows) { $err[] = t('v_out', $pi + 1, $id); continue; }
            for ($y = $r; $y < $r + $h; $y++) for ($x = $c; $x < $c + $w; $x++) {
                if (isset($grid[$y][$x])) $err[] = t('v_overlap', $pi + 1, $id, $grid[$y][$x]);
                $grid[$y][$x] = $id;
            }
        }
    }
    foreach (($cfg->sos->numbers ?? []) as $n) {
        if (!preg_match('/^\+?\d[\d ]{2,19}$/', (string)$n)) $err[] = t('v_sos_number', $n);
    }
    foreach (($cfg->reminders ?? []) as $rem) {
        if (!preg_match('/^([01]?\d|2[0-3]):[0-5]\d$/', (string)($rem->time ?? ''))) $err[] = t('v_rem_time', $rem->title ?? '?');
        $date = (string)($rem->date ?? '');
        if ($date !== '' && !(preg_match('/^\d{4}-\d{2}-\d{2}$/', $date) && checkdate((int)substr($date, 5, 2), (int)substr($date, 8, 2), (int)substr($date, 0, 4)))) $err[] = t('v_rem_date', $rem->title ?? '?');
    }
    return $err;
}

function pin_hash(string $pin): string {
    $salt = random_bytes(16);
    $hash = hash_pbkdf2('sha256', $pin, $salt, PBKDF2_ITER, 32, true);
    return 'pbkdf2-sha256$' . PBKDF2_ITER . '$' . base64_encode($salt) . '$' . base64_encode($hash);
}

/** Firma e pubblica: seq monotono, payload = byte esatti firmati. Tiene le ultime versioni nello storico. */
function publish(string $id, $cfg, ?string $pin, array $note = []): int {
    $meta = read_json(dev_path($id, 'meta.json'), []);
    $seq = max((int)($meta['seq'] ?? 0) + 1, time());
    $payload = ['seq' => $seq, 'device' => $id, 'issuedAt' => (int)(microtime(true) * 1000), 'config' => $cfg];
    if ($pin !== null && $pin !== '') $payload['pinHash'] = pin_hash($pin);
    $bytes = json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    $sig = sodium_crypto_sign_detached($bytes, sodium_crypto_sign_secretkey(keypair()));
    write_atomic(dev_path($id, 'envelope.json'), json_encode(['v' => 1, 'payload' => base64_encode($bytes), 'sig' => base64_encode($sig)]));
    $meta['seq'] = $seq;
    $meta['publishedAt'] = time();
    write_atomic(dev_path($id, 'meta.json'), json_encode($meta));

    $dir = DATA . "/history/$id";
    if (!is_dir($dir)) mkdir($dir, 0700, true);
    write_atomic("$dir/$seq.json", json_encode(['seq' => $seq, 'at' => time(), 'pin' => $pin !== null && $pin !== '', 'config' => $cfg] + $note, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
    $all = glob("$dir/*.json") ?: [];
    rsort($all, SORT_NATURAL);
    foreach (array_slice($all, HISTORY_KEEP) as $old) @unlink($old);
    return $seq;
}

function history(string $id): array {
    $all = glob(DATA . "/history/$id/*.json") ?: [];
    rsort($all, SORT_NATURAL);
    return array_map(fn($f) => (int)basename($f, '.json'), $all);
}

const DEFAULT_CONFIG = <<<'JSON'
{
  "version": 1,
  "pages": [
    {
      "cols": 2,
      "rows": 5,
      "tiles": [
        { "id": "clock", "col": 0, "row": 0, "w": 2, "h": 1, "type": "clock", "color": "#FFFFFF" },
        { "id": "dialer", "col": 0, "row": 1, "type": "app", "target": "@dialer", "label": "Telefono", "icon": { "kind": "symbol", "ref": "phone" }, "color": "#1F4FA8" },
        { "id": "sms", "col": 1, "row": 1, "type": "app", "target": "@sms", "label": "Messaggi", "icon": { "kind": "symbol", "ref": "message" }, "color": "#1F4FA8" },
        { "id": "camera", "col": 0, "row": 2, "type": "app", "target": "@camera", "label": "Fotocamera", "icon": { "kind": "symbol", "ref": "camera" }, "color": "#6A1B9A" },
        { "id": "gallery", "col": 1, "row": 2, "type": "app", "target": "@gallery", "label": "Foto", "icon": { "kind": "symbol", "ref": "photo" }, "color": "#6A1B9A" },
        { "id": "whatsapp", "col": 0, "row": 3, "type": "app", "target": "com.whatsapp", "label": "WhatsApp", "color": "#1B7A34" },
        { "id": "browser", "col": 1, "row": 3, "type": "app", "target": "@browser", "label": "Internet", "icon": { "kind": "symbol", "ref": "web" }, "color": "#1A1A1A" },
        { "id": "sos", "col": 0, "row": 4, "w": 2, "h": 1, "type": "sos", "label": "SOS", "color": "#B71C1C" }
      ]
    }
  ],
  "sos": { "numbers": [], "holdSeconds": 3, "countdownSeconds": 5, "message": "Ho bisogno di aiuto." },
  "reminders": [],
  "phone": { "ringerLock": false, "ringVolumePercent": 100, "batteryAlertPercent": 15, "badges": true, "smsUpdateCommand": false, "freshStart": true }
}
JSON;

/** Configurazione predefinita con etichette e messaggio SOS nella lingua corrente. */
function default_config(): string {
    $c = json_decode(DEFAULT_CONFIG, true);
    $labels = ['dialer' => 'def_phone', 'sms' => 'def_messages', 'camera' => 'def_camera', 'gallery' => 'def_photos', 'browser' => 'def_internet'];
    foreach ($c['pages'][0]['tiles'] as &$tile) if (isset($labels[$tile['id']])) $tile['label'] = t($labels[$tile['id']]);
    unset($tile);
    $c['sos']['message'] = t('def_sos_message');
    return json_encode($c, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}

// ---------------------------------------------------------------- pannello di gestione
$LANG = pick_lang();
// File del repository senza testi (né incorporati né in lang/): meglio un errore chiaro che le chiavi a video.
if (!lang_table('en')) {
    http_response_code(500);
    header('Content-Type: text/html; charset=utf-8');
    $rel = SOURCE_URL . '/releases/latest/download/index.php';
    exit('<!doctype html><meta charset="utf-8"><title>SemplicePhone</title><body style="font:16px/1.5 system-ui,sans-serif;max-width:640px;margin:40px auto;padding:0 16px">'
        . '<h1>index.php incompleto / incomplete index.php</h1>'
        . '<p>Questo è il file sorgente del repository: i testi delle lingue non sono inclusi. Sostituiscilo con quello della release (i dati in <code>data/</code> restano):</p>'
        . '<p>This is the repository source file and does not include the language texts. Replace it with the release file (your <code>data/</code> folder is kept):</p>'
        . '<p><a href="' . htmlspecialchars($rel) . '">' . htmlspecialchars($rel) . '</a></p>'
        . '<p>Oppure copia accanto a index.php la cartella <code>lang/</code> del repository. / Or copy the repository <code>lang/</code> folder next to index.php.</p>');
}
session_set_cookie_params(['httponly' => true, 'samesite' => 'Strict', 'secure' => !empty($_SERVER['HTTPS'])]);
session_start();
$nonce = base64_encode(random_bytes(16));
header('X-Frame-Options: DENY');
header("Content-Security-Policy: default-src 'self'; style-src 'unsafe-inline'; script-src 'nonce-$nonce'; connect-src 'self' https://geocoding-api.open-meteo.com; img-src 'self' blob:");
$adminFile = DATA . '/admin.json';
$admin = read_json($adminFile);
$csrf = $_SESSION['csrf'] ??= bin2hex(random_bytes(16));
$msg = ''; $err = [];

function h($s): string { return htmlspecialchars((string)$s, ENT_QUOTES); }
function check_csrf(string $csrf): void { if (!hash_equals($csrf, (string)($_POST['csrf'] ?? ''))) { http_response_code(400); exit(t('session_expired')); } }
function ago(?int $t): string {
    if (!$t) return t('never');
    $d = time() - $t;
    if ($d < 90) return t('now');
    if ($d < 3600) return t('min_ago', intdiv($d, 60));
    if ($d < 86400) return t('hours_ago', intdiv($d, 3600));
    return t('days_ago', intdiv($d, 86400));
}
function base_url(): string {
    return (!empty($_SERVER['HTTPS']) ? 'https' : 'http') . '://' . ($_SERVER['HTTP_HOST'] ?? 'host') . strtok($_SERVER['REQUEST_URI'] ?? '/', '?');
}

// Immagini per l'editor (anteprime) e caricamento: solo con il pannello aperto.
if (!empty($_SESSION['ok']) && isset($_GET['thumb'])) serve_media((string)$_GET['thumb']);
if (!empty($_SESSION['ok']) && isset($_GET['upload']) && $_SERVER['REQUEST_METHOD'] === 'POST') {
    check_csrf($csrf);
    header('Content-Type: application/json; charset=utf-8');
    $f = $_FILES['file'] ?? null;
    $info = $f && $f['error'] === UPLOAD_ERR_OK && $f['size'] <= MEDIA_MAX ? @getimagesize($f['tmp_name']) : false;
    if (!$info || !in_array($info['mime'], ['image/jpeg', 'image/png', 'image/webp'], true)) {
        http_response_code(400); exit(json_encode(['error' => t('upload_failed')]));
    }
    $hash = hash_file('sha256', $f['tmp_name']);
    if (!is_file(media_path($hash))) move_uploaded_file($f['tmp_name'], media_path($hash));
    exit(json_encode(['ref' => 'media:' . $hash]));
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    check_csrf($csrf);
    $act = $_POST['act'] ?? '';
    if ($act === 'setup' && !$admin) {
        $pw = (string)($_POST['password'] ?? '');
        if (strlen($pw) < 10) $err[] = t('e_pw_short');
        elseif ($pw !== (string)($_POST['password2'] ?? '')) $err[] = t('e_pw_mismatch');
        else { write_atomic($adminFile, json_encode(['hash' => password_hash($pw, PASSWORD_DEFAULT)])); $_SESSION['ok'] = true; header('Location: ?'); exit; }
    } elseif ($act === 'login' && $admin) {
        usleep(300000);
        if (password_verify((string)($_POST['password'] ?? ''), $admin['hash'])) { session_regenerate_id(true); $_SESSION['ok'] = true; header('Location: ?'); exit; }
        $err[] = t('e_pw_wrong');
    } elseif ($act === 'logout') {
        session_destroy(); header('Location: ?'); exit;
    } elseif (!empty($_SESSION['ok']) && $act === 'settings') {
        $s = settings();
        foreach (['telegramToken', 'telegramChat', 'emailTo', 'emailFrom'] as $k) $s[$k] = trim((string)($_POST[$k] ?? ''));
        foreach (array_keys($s['notify']) as $k) $s['notify'][$k] = !empty($_POST['notify_' . $k]);
        $s['offlineHours'] = max(1, min(168, (int)($_POST['offlineHours'] ?? 24)));
        if (in_array($_POST['timezone'] ?? '', timezone_identifiers_list(), true)) $s['timezone'] = $_POST['timezone'];
        $s['weeklySummary'] = !empty($_POST['weeklySummary']);
        if ($s['emailTo'] !== '' && !filter_var(explode(',', $s['emailTo'])[0], FILTER_VALIDATE_EMAIL)) $err[] = t('e_email');
        if ($s['telegramToken'] !== '' && !preg_match('/^\d+:[\w-]{20,}$/', $s['telegramToken'])) $err[] = t('e_telegram');
        if (!$err) { save_settings($s); $msg = t('m_settings_saved'); }
    } elseif (!empty($_SESSION['ok']) && $act === 'test') {
        $res = send_alert(ev_title('test'), t('test_text'));
        if (!$res) $err[] = t('e_no_channel');
        foreach ($res as $r) { if ($r['ok']) $msg .= ($msg ? ' ' : '') . t('m_channel_sent', $r['canale']); else $err[] = $r['canale'] . ': ' . $r['info']; }
    } elseif (!empty($_SESSION['ok'])) {
        $id = strtolower(trim((string)($_POST['id'] ?? '')));
        if (!valid_id($id)) $err[] = t('e_bad_code');
        elseif ($act === 'publish') {
            $cfg = json_decode((string)($_POST['config'] ?? ''));
            if (json_last_error() !== JSON_ERROR_NONE) $err[] = t('e_bad_json', json_last_error_msg());
            else $err = validate_config($cfg);
            $pin = trim((string)($_POST['pin'] ?? ''));
            if ($pin !== '' && !preg_match('/^\d{4,8}$/', $pin)) $err[] = t('e_pin');
            if (!$err) {
                write_atomic(dev_path($id, 'config.json'), json_encode($cfg, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
                $meta = read_json(dev_path($id, 'meta.json'), []);
                $meta['name'] = trim((string)($_POST['name'] ?? ($meta['name'] ?? '')));
                write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
                publish($id, $cfg, $pin !== '' ? $pin : null);
                $msg = t('m_published');
            }
        } elseif ($act === 'rename') {
            $meta = read_json(dev_path($id, 'meta.json'), []);
            $meta['name'] = trim((string)($_POST['name'] ?? ''));
            write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
            header('Location: ?edit=' . urlencode($id)); exit;
        } elseif ($act === 'forget') {
            @unlink(dev_path($id, 'secret'));
            $msg = t('m_forgot');
        } elseif ($act === 'locate') {
            $meta = read_json(dev_path($id, 'meta.json'), []);
            $meta['locateRequest'] = time();
            write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
            header('Location: ?edit=' . urlencode($id) . '#where'); exit;
        } elseif ($act === 'ring') {
            $meta = read_json(dev_path($id, 'meta.json'), []);
            $meta['ringRequest'] = time();
            write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
            header('Location: ?edit=' . urlencode($id) . '#family'); exit;
        } elseif ($act === 'message') {
            $text = trim(preg_replace('/\s+/u', ' ', (string)($_POST['text'] ?? '')));
            $from = mb_substr(trim((string)($_POST['from'] ?? '')), 0, 40);
            if ($text === '' || mb_strlen($text) > MESSAGE_MAX) { $err[] = t('e_message', MESSAGE_MAX); }
            else {
                $meta = read_json(dev_path($id, 'meta.json'), []);
                $mid = (int)(microtime(true) * 1000);
                $payload = json_encode(['device' => $id, 'id' => $mid, 'text' => $text, 'from' => $from, 'at' => $mid], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
                $sig = sodium_crypto_sign_detached($payload, sodium_crypto_sign_secretkey(keypair()));
                write_atomic(dev_path($id, 'message.json'), json_encode(['v' => 1, 'payload' => base64_encode($payload), 'sig' => base64_encode($sig)]));
                $meta['message'] = ['id' => $mid, 'text' => $text, 'from' => $from, 'at' => time()];
                $meta['messageFrom'] = $from;
                write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
                header('Location: ?edit=' . urlencode($id) . '#family'); exit;
            }
        } elseif ($act === 'ignore') {
            $meta = read_json(dev_path($id, 'meta.json'), []);
            $meta['ignoreOffline'] = empty($meta['ignoreOffline']);
            write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
            header('Location: ?edit=' . urlencode($id)); exit;
        } elseif ($act === 'delete') {
            foreach (glob(DATA . "/devices/$id.*") ?: [] as $f) @unlink($f);
            foreach ([DATA . "/reports/$id.jsonl", DATA . "/events/$id.jsonl"] as $f) @unlink($f);
            foreach (glob(DATA . "/history/$id/*.json") ?: [] as $f) @unlink($f);
            @rmdir(DATA . "/history/$id");
            header('Location: ?'); exit;
        }
    }
}

$logged = !empty($_SESSION['ok']);
$edit = $logged && isset($_GET['edit']) && valid_id(strtolower((string)$_GET['edit'])) ? strtolower((string)$_GET['edit']) : null;
$view = $logged ? ($edit ? 'device' : (isset($_GET['avvisi']) ? 'alerts' : 'list')) : 'login';
if ($logged) {
    // Indirizzo del pannello per i link negli avvisi.
    $s = settings();
    if ($s['panelUrl'] !== base_url() || $s['lang'] !== $LANG) { $s['panelUrl'] = base_url(); $s['lang'] = $LANG; save_settings($s); }
    run_checks();
}
?><!doctype html>
<html lang="<?= h($LANG) ?>"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title><?= th('title') ?></title>
<style>
:root{--ink:#1A1A1A;--mute:#5a5650;--line:#d9d3c7;--bg:#F7F5F0;--card:#fff;--accent:#1F4FA8;--ok:#1B6E34;--warn:#A84300;--bad:#B71C1C}
*{box-sizing:border-box}
body{font:16px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif;margin:0;background:var(--bg);color:var(--ink)}
header.top{background:var(--ink);color:#fff}
header.top .in{max-width:1080px;margin:0 auto;padding:12px 16px;display:flex;flex-wrap:wrap;align-items:center;gap:8px 12px}
header.top .brand{font-weight:700;font-size:19px;flex:1 1 auto;min-width:0}
/* Schermi stretti (o carattere grande): il nome sopra, menu ed «Esci» sotto, mai oltre il bordo. */
@media (max-width:560px){header.top .brand{flex-basis:100%}header.top .nav{flex:1}}
.card{overflow-x:auto}img,textarea,input,select{max-width:100%}code{overflow-wrap:anywhere;word-break:break-all}
header.top a{color:#fff}
.nav{display:flex;gap:4px}.nav a{padding:8px 12px;border-radius:8px;text-decoration:none;font-weight:600}.nav a[aria-current=page]{background:#ffffff26}
main{max-width:1080px;margin:0 auto;padding:16px}
h1{font-size:26px;margin:8px 0 4px}h2{font-size:20px;margin:26px 0 8px}h3{font-size:17px;margin:14px 0 6px}
p.lead{color:var(--mute);margin-top:0}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;margin:12px 0}
.msg{border-radius:12px;padding:12px 14px;margin:12px 0;font-weight:600}
.msg.ok{background:#E3F1E7;color:#124a24}.msg.err{background:#FCE4E4;color:#7a1010}.msg.warn{background:#FFF0DF;color:#6b2f00}
label{display:block;font-weight:600;margin:10px 0 4px}
.hint{font-weight:400;color:var(--mute);font-size:14px}
input[type=text],input[type=password],input[type=tel],input[type=url],input[type=time],input[type=number],select,textarea{font:inherit;padding:10px 12px;border:2px solid #bdb6a8;border-radius:10px;width:100%;background:#fff;color:var(--ink)}
input:focus,select:focus,textarea:focus{outline:3px solid #9db7e8;border-color:var(--accent)}
textarea.json{min-height:420px;font:13px/1.4 ui-monospace,Consolas,monospace}
button,.btn{font:inherit;font-weight:600;border:0;border-radius:10px;padding:10px 16px;min-height:44px;cursor:pointer;background:var(--ink);color:#fff;text-decoration:none;display:inline-flex;align-items:center;gap:6px}
button.sec,.btn.sec{background:#fff;color:var(--ink);border:2px solid var(--ink)}
button.danger{background:#fff;color:var(--bad);border:2px solid var(--bad)}
button.small{min-height:36px;padding:6px 12px;font-size:14px}
button:disabled{opacity:.45;cursor:not-allowed}
.row{display:flex;gap:12px;flex-wrap:wrap}.row>*{flex:1 1 200px}
.grid-devices{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:12px}
.dev{display:block;color:inherit;text-decoration:none;background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px}
.dev:hover{border-color:var(--ink)}
.dev .name{font-weight:700;font-size:18px}
.pill{display:inline-block;border-radius:999px;padding:2px 10px;font-size:13px;font-weight:600;margin:6px 6px 0 0}
.pill.ok{background:#E3F1E7;color:#124a24}.pill.warn{background:#FFF0DF;color:#7a3500}.pill.bad{background:#FCE4E4;color:#7a1010}.pill.mute{background:#eee;color:#444}
code.k{display:block;background:#f1eee7;border-radius:8px;padding:10px;word-break:break-all;font:14px ui-monospace,Consolas,monospace;margin:4px 0}
ol.steps{padding-left:22px}ol.steps li{margin:6px 0}
.tabs{display:flex;gap:6px;flex-wrap:wrap;border-bottom:2px solid var(--line);margin:16px 0 0}
.tabs button{background:transparent;color:var(--ink);border-radius:10px 10px 0 0;border:2px solid transparent;border-bottom:0;margin-bottom:-2px}
.tabs button[aria-selected=true]{background:var(--card);border-color:var(--line)}
.tabpanel{background:var(--card);border:1px solid var(--line);border-top:0;border-radius:0 0 14px 14px;padding:16px}
.editor{display:flex;gap:20px;flex-wrap:wrap;align-items:flex-start}
.phone{flex:0 0 300px;background:#F7F5F0;border:8px solid var(--ink);border-radius:28px;padding:12px}
.phonegrid{display:grid;gap:8px}
.tile{border-radius:12px;padding:6px;display:flex;align-items:center;justify-content:center;text-align:center;font-weight:700;font-size:14px;cursor:pointer;border:2px solid transparent;min-height:56px;overflow:hidden}
.tile.sel{outline:4px solid #E8A33D;outline-offset:1px}
.tile.light{border-color:var(--ink)}
.cell{border:2px dashed #b9b2a4;border-radius:12px;background:transparent;color:var(--mute);font-size:22px;min-height:56px;padding:0}
.cell:hover{border-color:var(--ink);color:var(--ink)}
.side{flex:1 1 340px;min-width:280px}
.swatches{display:flex;gap:8px;flex-wrap:wrap}
.sw{width:40px;height:40px;border-radius:50%;border:2px solid var(--ink);padding:0;min-height:0}
.sw[aria-pressed=true]{outline:4px solid #E8A33D;outline-offset:2px}
.days{display:flex;gap:6px}
.days label{margin:0;display:flex;flex-direction:column;align-items:center;font-size:13px}
.issues{background:#FCE4E4;color:#7a1010;border-radius:10px;padding:10px 14px;margin:10px 0}
.issues:empty{display:none}
.rem{border:1px solid var(--line);border-radius:12px;padding:12px;margin:10px 0}
.pagebar{display:flex;gap:6px;flex-wrap:wrap;align-items:center;margin-bottom:10px}
.pagebar button[aria-pressed=true]{background:var(--accent);color:#fff;border-color:var(--accent)}
.sticky{position:sticky;bottom:0;background:var(--bg);padding:12px 0;border-top:1px solid var(--line);display:flex;gap:12px;align-items:center;flex-wrap:wrap}
table{border-collapse:collapse;width:100%}td,th{text-align:left;padding:8px;border-bottom:1px solid #ece7dd}
details summary{cursor:pointer;font-weight:600}
@media (max-width:640px){.phone{flex-basis:100%}}
</style></head><body>
<header class="top"><div class="in"><div class="brand"><?= th('brand') ?></div>
<?php if ($logged): ?><nav class="nav"><a href="?" <?= $view !== 'alerts' ? 'aria-current="page"' : '' ?>><?= th('nav_phones') ?></a><a href="?avvisi" <?= $view === 'alerts' ? 'aria-current="page"' : '' ?>><?= th('nav_alerts') ?></a></nav><?php endif; ?>
<?php if ($logged): ?><form method="post" style="margin:0"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="logout"><button class="small sec"><?= th('logout') ?></button></form><?php endif; ?>
</div></header>
<main>
<script nonce="<?= h($nonce) ?>">
  window.T_ = <?= json_encode(array_merge(lang_table('en'), lang_table($LANG)), JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT | JSON_UNESCAPED_UNICODE) ?>;
  window.T = (k, ...a) => a.reduce((s, v, i) => s.split('{' + i + '}').join(v), window.T_[k] ?? k);
</script>
<?php if ($msg): ?><div class="msg ok" role="status"><?= h($msg) ?></div><?php endif; ?>
<?php foreach ($err as $e): ?><div class="msg err" role="alert"><?= h($e) ?></div><?php endforeach; ?>

<?php if (!$admin): ?>
  <h1><?= th('welcome') ?></h1>
  <p class="lead"><?= th('welcome_lead') ?></p>
  <form method="post" class="card" style="max-width:480px"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="setup">
    <label for="p1"><?= th('password') ?> <span class="hint"><?= th('pw_min_hint') ?></span></label><input id="p1" type="password" name="password" autocomplete="new-password" required minlength="10">
    <label for="p2"><?= th('pw_repeat') ?></label><input id="p2" type="password" name="password2" autocomplete="new-password" required minlength="10">
    <p><button><?= th('start') ?></button></p>
  </form>

<?php elseif (!$logged): ?>
  <h1><?= th('login') ?></h1>
  <form method="post" class="card" style="max-width:420px"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="login">
    <label for="pw"><?= th('password') ?></label><input id="pw" type="password" name="password" autocomplete="current-password" required autofocus>
    <p><button><?= th('enter') ?></button></p>
  </form>

<?php elseif ($view === 'alerts'):
    $s = settings();
    $log = array_reverse(tail_jsonl(DATA . '/alerts.jsonl', 30));
?>
  <h1><?= th('nav_alerts') ?></h1>
  <p class="lead"><?= th('alerts_lead') ?></p>
  <form method="post" class="card"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="settings">
    <h3><?= th('when_alert') ?></h3>
    <?php foreach (['sos', 'reminder_missed', 'battery', 'offline', 'call_blocked', 'location', 'ring_done', 'message_read'] as $k): $label = t('n_' . $k); ?>
      <label style="font-weight:400"><input type="checkbox" name="notify_<?= $k ?>" <?= !empty($s['notify'][$k]) ? 'checked' : '' ?>> <?= h($label) ?></label>
    <?php endforeach; ?>
    <label style="font-weight:400"><input type="checkbox" name="weeklySummary" <?= !empty($s['weeklySummary']) ? 'checked' : '' ?>> <?= th('weekly_enable') ?></label>
    <div class="hint"><?= th('weekly_hint') ?></div>
    <div class="row"><div><label for="oh"><?= th('offline_hours') ?></label><input id="oh" type="number" name="offlineHours" min="1" max="168" value="<?= (int)$s['offlineHours'] ?>"></div>
      <div><label for="tz"><?= th('timezone') ?></label><select id="tz" name="timezone"><?php foreach (timezone_identifiers_list() as $tz): ?><option <?= $tz === $s['timezone'] ? 'selected' : '' ?>><?= h($tz) ?></option><?php endforeach; ?></select></div></div>

    <h3>Telegram <span class="hint"><?= th('telegram_hint') ?></span></h3>
    <details><summary><?= th('how_setup') ?></summary>
      <ol class="steps">
        <li><?= t('tg_step1_html') ?></li>
        <li><?= t('tg_step2_html') ?></li>
        <li><?= t('tg_step3_html') ?></li>
      </ol>
    </details>
    <div class="row">
      <div><label for="tt"><?= th('bot_token') ?></label><input id="tt" type="password" name="telegramToken" value="<?= h($s['telegramToken']) ?>" autocomplete="off" placeholder="123456:ABC…"></div>
      <div><label for="tc"><?= th('chat_id') ?> <span class="hint"><?= th('comma_more') ?></span></label><input id="tc" type="text" name="telegramChat" value="<?= h($s['telegramChat']) ?>"></div>
    </div>
    <h3><?= th('email') ?></h3>
    <div class="row">
      <div><label for="et"><?= th('recipients') ?> <span class="hint"><?= th('comma_sep') ?></span></label><input id="et" type="text" name="emailTo" value="<?= h($s['emailTo']) ?>"></div>
      <div><label for="ef"><?= th('sender') ?> <span class="hint"><?= th('optional') ?></span></label><input id="ef" type="text" name="emailFrom" value="<?= h($s['emailFrom']) ?>" placeholder="semplicephone@<?= h($_SERVER['HTTP_HOST'] ?? t('yoursite')) ?>"></div>
    </div>
    <p class="hint"><?= th('mail_hint') ?></p>
    <p class="row" style="gap:8px"><button><?= th('save') ?></button></p>
  </form>
  <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="test"><button class="sec"><?= th('send_test') ?></button></form>
  <p class="hint"><?= t('cron_hint_html', '<code>php ' . h(__FILE__) . ' cron</code>') ?></p>

  <h2><?= th('last_alerts') ?></h2>
  <div class="card">
  <?php if (!$log): ?><p class="hint"><?= th('no_alerts_sent') ?></p><?php else: ?>
    <table><tr><th><?= th('col_when') ?></th><th><?= th('col_alert') ?></th><th><?= th('col_result') ?></th></tr>
    <?php foreach ($log as $a): $okAll = $a['result'] && !in_array(false, array_column($a['result'], 'ok'), true); ?>
      <tr><td><?= h(date('d/m H:i', (int)$a['at'])) ?></td><td><b><?= h($a['title']) ?></b><?= $a['device'] ? ' · ' . h(device_name($a['device'])) : '' ?><br><span class="hint"><?= h($a['text']) ?></span></td>
      <td><span class="pill <?= !$a['result'] ? 'mute' : ($okAll ? 'ok' : 'bad') ?>"><?= th(!$a['result'] ? 'no_channel' : ($okAll ? 'sent' : 'error')) ?></span></td></tr>
    <?php endforeach; ?></table>
  <?php endif; ?>
  </div>

<?php elseif ($view === 'list'):
    $ids = array_fill_keys(device_ids(), true);
?>
  <h1><?= th('your_phones') ?></h1>
  <p class="lead"><?= th('phones_lead') ?></p>
  <?php $st = settings(); if ($st['telegramToken'] === '' && $st['emailTo'] === ''): ?>
    <div class="msg warn"><?= t('alerts_off_html') ?></div>
  <?php endif; ?>
  <?php if (!$ids): ?><div class="card"><?= th('no_phones') ?></div><?php endif; ?>
  <div class="grid-devices">
  <?php foreach (array_keys($ids) as $id): $m = read_json(dev_path($id, 'meta.json'), []);
      $seen = (int)($m['lastSeen'] ?? 0);
      $online = $seen && time() - $seen < 8 * 3600;
      $synced = isset($m['seq'], $m['appliedSeq']) && (int)$m['appliedSeq'] >= (int)$m['seq'];
  ?>
    <a class="dev" href="?edit=<?= h($id) ?>">
      <div class="name"><?= h(($m['name'] ?? '') !== '' ? $m['name'] : t('unnamed')) ?></div>
      <div class="hint"><?= th('code_last', $id, ago($seen ?: null)) ?></div>
      <span class="pill <?= $seen ? ($online ? 'ok' : 'warn') : 'mute' ?>"><?= th($seen ? ($online ? 'connected' : 'silent') : 'never_connected') ?></span>
      <?php if (isset($m['battery']) && $m['battery'] >= 0): ?><span class="pill <?= $m['battery'] < 20 && empty($m['charging']) ? 'bad' : 'mute' ?>"><?= th('battery_pct', (int)$m['battery']) ?><?= !empty($m['charging']) ? th('charging') : '' ?></span><?php endif; ?>
      <?php if (access_warnings($m)): ?><span class="pill bad"><?= th('acc_missing') ?></span><?php endif; ?>
      <?php if (isset($m['seq'])): ?><span class="pill <?= $synced ? 'ok' : 'warn' ?>"><?= th($synced ? 'up_to_date' : 'changes_pending') ?></span><?php else: ?><span class="pill mute"><?= th('to_configure') ?></span><?php endif; ?>
    </a>
  <?php endforeach; ?>
  </div>

  <h2><?= th('connect_phone') ?></h2>
  <div class="card">
    <ol class="steps">
      <li><?= t('conn_step1_html') ?></li>
      <li><?= th('conn_step2') ?>
        <label><?= th('address') ?></label><code class="k" id="u"><?= h(base_url()) ?>?device={device}</code><button class="small sec" data-copy="u"><?= th('copy_address') ?></button>
        <label><?= th('public_key') ?></label><code class="k" id="k"><?= h(public_key_b64()) ?></code><button class="small sec" data-copy="k"><?= th('copy_key') ?></button>
      </li>
      <li><?= t('conn_step3_html') ?></li>
    </ol>
    <details><summary><?= th('prepare_before') ?></summary>
      <form method="get" class="row" style="align-items:flex-end;margin-top:8px">
        <div><label for="nid"><?= th('phone_code') ?> <span class="hint"><?= th('phone_code_hint') ?></span></label><input id="nid" type="text" name="edit" pattern="[a-z0-9]{6,32}" required></div>
        <div style="flex:0 0 auto"><button><?= th('open') ?></button></div>
      </form>
    </details>
  </div>

<?php else:
    $meta = read_json(dev_path($edit, 'meta.json'), []);
    $src = $_POST['config'] ?? (is_file(dev_path($edit, 'config.json')) ? file_get_contents(dev_path($edit, 'config.json')) : default_config());
    $loaded = '';
    if (!isset($_POST['config']) && isset($_GET['from']) && is_file($hf = DATA . "/history/$edit/" . (int)$_GET['from'] . '.json')) {
        $hv = read_json($hf, []);
        $src = json_encode($hv['config'] ?? [], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        $loaded = t('loaded_version', date('d/m/Y H:i', (int)($hv['at'] ?? 0)));
    } elseif (!isset($_POST['config']) && isset($_GET['copy']) && valid_id($cp = strtolower((string)$_GET['copy'])) && is_file(dev_path($cp, 'config.json'))) {
        $src = file_get_contents(dev_path($cp, 'config.json'));
        $loaded = t('copied_from', device_name($cp));
    }
    $devApps = read_json(dev_path($edit, 'apps.json'), []);
    $urgent = array_reverse(tail_jsonl(DATA . "/events/$edit.jsonl", 20));
    $versions = array_slice(history($edit), 0, 15);
    $others = array_values(array_filter(device_ids(), fn($o) => $o !== $edit && is_file(dev_path($o, 'config.json'))));
    $lines = is_file(DATA . "/reports/$edit.jsonl") ? array_slice(file(DATA . "/reports/$edit.jsonl", FILE_IGNORE_NEW_LINES), -40) : [];
    $events = [];
    foreach ($lines as $l) foreach ((json_decode($l, true)['reminderEvents'] ?? []) as $ev) $events[] = $ev;
    $events = array_slice(array_reverse($events), 0, 30);
?>
  <p><a href="?"><?= th('all_phones') ?></a></p>
  <h1><?= h(($meta['name'] ?? '') !== '' ? $meta['name'] : t('unnamed')) ?></h1>
  <p class="lead"><?= th('code_last', $edit, ago(isset($meta['lastSeen']) ? (int)$meta['lastSeen'] : null)) ?>
    <?php if (isset($meta['battery']) && $meta['battery'] >= 0): ?><?= th('battery_lower', (int)$meta['battery']) ?><?php endif; ?>
    <?php if (!empty($meta['appVersion'])): ?><?= th('app_version', $meta['appVersion']) ?><?php endif; ?></p>

  <?php if ($loaded): ?><div class="msg warn"><?= h($loaded) ?></div><?php endif; ?>
  <?php $lastV = history($edit)[0] ?? null; $lastH = $lastV ? read_json(DATA . "/history/$edit/$lastV.json", []) : [];
    if (!$loaded && ($lastH['source'] ?? '') === 'phone'): ?><p class="hint">📱 <?= th('phone_changed_info', date('d/m/Y H:i', (int)($lastH['at'] ?? 0)), implode('; ', (array)($lastH['changes'] ?? []))) ?></p><?php endif; ?>
  <?php if (isset($meta['badgesAccess']) && !$meta['badgesAccess']): ?><div class="msg warn"><?= th('no_notif_access') ?></div><?php endif; ?>
  <?php foreach (access_warnings($meta) as $w): ?><div class="msg warn"><?= h($w) ?></div><?php endforeach; ?>
  <form method="post" id="pubform" data-dirty="<?= $loaded ? '1' : '0' ?>" data-edition="<?= h($meta['edition'] ?? '') ?>" data-app="<?= h($meta['appVersion'] ?? '') ?>" data-csrf="<?= h($csrf) ?>">
    <input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="publish"><input type="hidden" name="id" value="<?= h($edit) ?>">
    <div class="row">
      <div><label for="name"><?= th('name') ?> <span class="hint"><?= th('name_hint') ?></span></label><input id="name" type="text" name="name" value="<?= h($meta['name'] ?? '') ?>"></div>
      <div><label for="pin"><?= th('new_pin') ?> <span class="hint"><?= th('new_pin_hint') ?></span></label><input id="pin" type="text" name="pin" inputmode="numeric" pattern="\d{4,8}" autocomplete="off" placeholder="<?= th('pin_placeholder') ?>"></div>
    </div>

    <details class="card" id="wizard" <?= is_file(dev_path($edit, 'config.json')) ? '' : 'open' ?>><summary><?= th('wiz_title') ?></summary><div id="wizbody"></div></details>
    <div class="tabs" role="tablist">
      <button type="button" role="tab" aria-selected="true" data-tab="t-screen"><?= th('tab_screen') ?></button>
      <button type="button" role="tab" aria-selected="false" data-tab="t-sos">SOS</button>
      <button type="button" role="tab" aria-selected="false" data-tab="t-rem"><?= th('tab_reminders') ?></button>
      <button type="button" role="tab" aria-selected="false" data-tab="t-phone"><?= th('tab_phone') ?></button>
      <button type="button" role="tab" aria-selected="false" data-tab="t-json"><?= th('tab_advanced') ?></button>
    </div>

    <section class="tabpanel" id="t-screen" role="tabpanel">
      <div class="pagebar" id="pagebar"></div>
      <div class="editor">
        <div class="phone" aria-label="<?= th('preview') ?>"><div class="phonegrid" id="phonegrid"></div></div>
        <div class="side" id="side"></div>
      </div>
    </section>
    <section class="tabpanel" id="t-sos" role="tabpanel" hidden></section>
    <section class="tabpanel" id="t-rem" role="tabpanel" hidden></section>
    <section class="tabpanel" id="t-phone" role="tabpanel" hidden></section>
    <section class="tabpanel" id="t-json" role="tabpanel" hidden>
      <p class="hint"><?= th('json_hint') ?></p>
      <textarea class="json" name="config" id="config" spellcheck="false"><?= h($src) ?></textarea>
      <div class="issues" id="jsonerr"></div>
    </section>

    <div class="issues" id="issues" role="alert"></div>
    <div class="msg warn" id="warns" hidden></div>
    <div class="sticky">
      <button id="pubbtn"><?= th('publish') ?></button>
      <span class="hint" id="dirty"></span>
    </div>
  </form>

  <?php
    $lastLoc = null;
    foreach ($urgent as $ev) if (($ev['type'] ?? '') === 'location') { $lastLoc = $ev; break; }
    $pending = time() - (int)($meta['locateRequest'] ?? 0) < 3600;
  ?>
  <h2 id="where"><?= th('locate_title') ?></h2>
  <div class="card">
    <?php if ($lastLoc): $la = $lastLoc['lat'] ?? null; $lo = $lastLoc['lon'] ?? null; ?>
      <p><b><?= th('locate_last', date('d/m H:i', (int)(($lastLoc['at'] ?? 0) / 1000))) ?></b>
      <?php if ($la !== null && $lo !== null): ?> · <a href="https://www.openstreetmap.org/?mlat=<?= (float)$la ?>&amp;mlon=<?= (float)$lo ?>#map=17/<?= (float)$la ?>/<?= (float)$lo ?>" target="_blank" rel="noopener"><?= th('locate_map') ?></a><?php endif; ?></p>
      <p class="hint"><?= h($lastLoc['text'] ?? '') ?></p>
    <?php else: ?><p class="hint"><?= th('locate_none') ?></p><?php endif; ?>
    <?php if ($pending): ?><p class="msg warn"><?= th('locate_pending') ?></p><?php endif; ?>
    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="locate"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="sec"><?= th('locate_button') ?></button></form>
    <p class="hint"><?= th('locate_hint') ?></p>
  </div>

  <?php $fm = $meta['message'] ?? null; $ringing = time() - (int)($meta['ringRequest'] ?? 0) < 3600;
    $oldApp = !empty($meta['appVersion']) && version_compare((string)$meta['appVersion'], APP_FEATURES_0130, '<'); ?>
  <h2 id="family"><?= th('family_title') ?></h2>
  <div class="card">
    <?php if ($oldApp): ?><p class="msg warn"><?= th('family_old_app', $meta['appVersion']) ?></p><?php endif; ?>
    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="message"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <label for="mtext"><?= th('message_label') ?> <span class="hint"><?= th('message_hint', MESSAGE_MAX) ?></span></label>
      <textarea id="mtext" name="text" maxlength="<?= MESSAGE_MAX ?>" rows="3" required placeholder="<?= th('message_placeholder') ?>"></textarea>
      <div class="row" style="align-items:flex-end">
        <div><label for="mfrom"><?= th('message_from_label') ?></label><input id="mfrom" type="text" name="from" maxlength="40" value="<?= h($meta['messageFrom'] ?? '') ?>" placeholder="<?= th('message_from_placeholder') ?>"></div>
        <div style="flex:0 0 auto"><button><?= th('message_send') ?></button></div>
      </div>
    </form>
    <?php if ($fm): ?><p class="hint"><?= th('message_last', date('d/m H:i', (int)$fm['at']), $fm['text']) ?> —
      <b><?= !empty($fm['readAt']) ? th('message_read_at', date('d/m H:i', (int)$fm['readAt'])) : th('message_waiting') ?></b></p><?php endif; ?>
    <hr style="border:0;border-top:1px solid #ddd;margin:16px 0">
    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="ring"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="sec"><?= th('ring_button') ?></button></form>
    <?php if ($ringing): ?><p class="msg warn"><?= th('ring_pending') ?></p><?php endif; ?>
    <p class="hint"><?= th('family_hint') ?></p>
  </div>

  <h2><?= th('weekly_card') ?></h2>
  <div class="card"><ul style="margin:0;padding-left:20px"><?php foreach (weekly_lines($edit) as $l): ?><li><?= h($l) ?></li><?php endforeach; ?></ul></div>

  <h2><?= th('alerts_from_phone') ?></h2>
  <div class="card">
  <?php if (!$urgent): ?><p class="hint"><?= th('no_alerts_received') ?></p><?php else: ?>
    <table><tr><th><?= th('col_when') ?></th><th><?= th('col_what') ?></th></tr>
    <?php foreach ($urgent as $ev): ?><tr><td><?= h(date('d/m H:i', (int)(($ev['at'] ?? 0) / 1000))) ?></td><td><b><?= h(ev_title((string)($ev['type'] ?? ''))) ?></b><br><span class="hint"><?= h($ev['text'] ?? '') ?></span></td></tr><?php endforeach; ?>
    </table>
  <?php endif; ?>
  </div>

  <h2><?= th('prev_versions') ?></h2>
  <div class="card">
  <?php if (!$versions): ?><p class="hint"><?= th('no_publications') ?></p><?php else: ?>
    <table><tr><th><?= th('col_published') ?></th><th></th></tr>
    <?php foreach ($versions as $i => $v): $hv = read_json(DATA . "/history/$edit/$v.json", []); ?>
      <tr><td><?= h(date('d/m/Y H:i', (int)($hv['at'] ?? 0))) ?><?= $i === 0 ? ' <span class="pill ok">' . th('current') . '</span>' : '' ?><?= !empty($hv['pin']) ? ' <span class="pill mute">' . th('pin_changed') . '</span>' : '' ?><?= ($hv['source'] ?? '') === 'phone' ? ' <span class="pill warn">' . th('from_phone') . '</span>' : '' ?>
        <?php if (!empty($hv['changes'])): ?><div class="hint"><?= h(implode('; ', (array)$hv['changes'])) ?></div><?php endif; ?></td>
      <td style="text-align:right"><?php if ($i > 0): ?><a class="btn sec" href="?edit=<?= h($edit) ?>&amp;from=<?= (int)$v ?>"><?= th('load_in_editor') ?></a><?php endif; ?></td></tr>
    <?php endforeach; ?></table>
  <?php endif; ?>
  <?php if ($others): ?>
    <form method="get" class="row" style="align-items:flex-end;margin-top:12px"><input type="hidden" name="edit" value="<?= h($edit) ?>">
      <div><label for="cp"><?= th('copy_from_other') ?></label>
        <select id="cp" name="copy"><?php foreach ($others as $o): ?><option value="<?= h($o) ?>"><?= h(device_name($o)) ?></option><?php endforeach; ?></select></div>
      <div style="flex:0 0 auto"><button class="sec"><?= th('copy_in_editor') ?></button></div>
    </form>
  <?php endif; ?>
  </div>

  <h2><?= th('installed_apps') ?></h2>
  <div class="card">
  <?php if (!$devApps): ?>
    <p class="hint"><?= th('apps_not_received') ?></p>
  <?php else: ?>
    <details><summary><?= th('apps_count', count($devApps), ago(filemtime(dev_path($edit, 'apps.json')) ?: null)) ?></summary>
      <table style="margin-top:8px"><tr><th><?= th('col_name') ?></th><th><?= th('col_package') ?></th></tr>
      <?php foreach ($devApps as $a): ?><tr><td><?= h($a['label']) ?></td><td class="hint"><?= h($a['pkg']) ?></td></tr><?php endforeach; ?>
      </table>
    </details>
  <?php endif; ?>
  </div>

  <h2><?= th('rem_what_happened') ?></h2>
  <div class="card">
  <?php if (!$events): ?><p class="hint"><?= th('no_news') ?></p><?php else: ?>
    <table><tr><th><?= th('col_when') ?></th><th><?= th('col_reminder') ?></th><th><?= th('col_result') ?></th></tr>
    <?php foreach ($events as $ev): $o = $ev['outcome'] ?? ''; ?><tr><td><?= h(date('d/m H:i', (int)(($ev['at'] ?? 0) / 1000))) ?></td><td data-rem="<?= h($ev['id'] ?? '') ?>"><?= h($ev['id'] ?? '') ?></td>
      <td><span class="pill <?= $o === 'preso' ? 'ok' : ($o === 'rinviato' ? 'warn' : 'bad') ?>"><?= h(['preso' => t('out_taken'), 'rinviato' => t('out_snoozed'), 'non_confermato' => t('out_missed')][$o] ?? $o) ?></span></td></tr><?php endforeach; ?>
    </table>
  <?php endif; ?>
  </div>

  <details class="card"><summary><?= th('reinstalled') ?></summary>
    <p><?= th('reinstalled_text') ?></p>
    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="forget"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="danger"><?= th('accept_next') ?></button></form>
  </details>
  <details class="card"><summary><?= th('other_actions') ?></summary>
    <form method="post" style="margin:10px 0"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="ignore"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="sec"><?= th(empty($meta['ignoreOffline']) ? 'ignore_offline' : 'unignore_offline') ?></button></form>
    <form method="post" id="delform"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="delete"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="danger"><?= th('remove_phone') ?></button></form>
  </details>
  <script nonce="<?= h($nonce) ?>">
    window.DEVICE_APPS = <?= json_encode($devApps, JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT | JSON_UNESCAPED_UNICODE) ?>;
    document.getElementById('delform').addEventListener('submit', e => { if (!confirm(T('remove_phone_confirm'))) e.preventDefault(); });
  </script>
<?php endif; ?>
<footer class="hint" style="margin:32px 0 8px;text-align:center"><?= t('footer_html', h(VERSION), h(SOURCE_URL)) ?>
  <nav aria-label="<?= th('language') ?>" style="margin-top:6px"><?php foreach (langs() as $l): $q = $_GET; $q['lang'] = $l; ?>
    <a href="?<?= h(http_build_query($q)) ?>" lang="<?= $l ?>" <?= $l === $LANG ? 'aria-current="true" style="font-weight:700"' : '' ?>><?= h(lang_table($l)['lang_name'] ?? $l) ?></a><?= $l !== langs()[count(langs()) - 1] ? ' · ' : '' ?>
  <?php endforeach; ?></nav></footer>
</main>

<script nonce="<?= h($nonce) ?>">
// Copia negli appunti (pagina elenco)
document.querySelectorAll('[data-copy]').forEach(b => b.addEventListener('click', e => {
  e.preventDefault();
  const t = document.getElementById(b.dataset.copy).textContent.trim();
  (navigator.clipboard ? navigator.clipboard.writeText(t) : Promise.reject()).then(
    () => { const o = b.textContent; b.textContent = T('js_copied'); setTimeout(() => b.textContent = o, 1500); },
    () => { prompt(T('js_copy_prompt'), t); });
}));

(function () {
  const ta = document.getElementById('config');
  if (!ta) return;

  const TYPES = Object.fromEntries(['app', 'call', 'wa_video', 'sms', 'url', 'shortcut', 'clock', 'weather', 'photos', 'missed_calls', 'search', 'sos'].map(k => [k, T('type_' + k)]));
  const FORM = document.getElementById('pubform');
  // Caricamento di un'immagine nel portale: restituisce «media:<sha256>».
  async function upload(file) {
    const fd = new FormData(); fd.append('file', file); fd.append('csrf', FORM.dataset.csrf);
    const res = await fetch('?upload=1', {method: 'POST', body: fd, credentials: 'same-origin'});
    const j = await res.json().catch(() => ({}));
    if (!res.ok || !j.ref) { alert(j.error || T('upload_failed')); return null; }
    return j.ref;
  }
  function thumb(ref, onRemove) {
    const h = (ref || '').replace('media:', '');
    return el('div', {style: 'position:relative;display:inline-block;margin:0 8px 8px 0'},
      el('img', {src: '?thumb=' + h, alt: '', style: 'width:84px;height:84px;object-fit:cover;border-radius:10px;border:2px solid #1A1A1A'}),
      onRemove ? el('button', {type: 'button', class: 'small danger', style: 'position:absolute;top:-8px;right:-8px;min-height:28px;padding:2px 8px', 'aria-label': T('remove'), onclick: onRemove}, '✕') : '');
  }
  function fileInput(multiple, onRefs) {
    return el('input', {type: 'file', accept: 'image/jpeg,image/png,image/webp', multiple, onchange: async e => {
      const refs = [];
      for (const f of e.target.files) { const r = await upload(f); if (r) refs.push(r); }
      if (refs.length) onRefs(refs);
    }});
  }
  // Ricerca località (Open-Meteo) per il riquadro meteo.
  async function searchPlace(q) {
    const lang = (document.documentElement.lang || 'en').slice(0, 2);
    const res = await fetch('https://geocoding-api.open-meteo.com/v1/search?count=6&format=json&language=' + lang + '&name=' + encodeURIComponent(q));
    const j = await res.json().catch(() => ({}));
    return (j.results || []).map(o => ({name: o.name, area: [o.admin1, o.country].filter(Boolean).join(', '), target: o.latitude.toFixed(4) + ',' + o.longitude.toFixed(4)}));
  }
  const APPS = [['@dialer', T('role_dialer')], ['@sms', T('role_sms')], ['@camera', T('role_camera')],
    ['@gallery', T('role_gallery')], ['@browser', T('role_browser')], ['com.whatsapp', 'WhatsApp'],
    ['org.telegram.messenger', 'Telegram'], ['com.google.android.youtube', 'YouTube'], ['com.google.android.apps.maps', 'Google Maps'],
    ['com.google.android.gm', 'Gmail'], ['com.android.chrome', 'Chrome']];
  const APP_LABEL = {'@dialer': T('def_phone'), '@sms': T('def_messages'), '@camera': T('def_camera'), '@gallery': T('def_gallery'), '@browser': T('def_internet'),
    'com.whatsapp': 'WhatsApp', 'org.telegram.messenger': 'Telegram', 'com.google.android.youtube': 'YouTube',
    'com.google.android.apps.maps': T('lbl_maps'), 'com.google.android.gm': T('lbl_mail'), 'com.android.chrome': T('def_internet')};
  const ICONS = Object.fromEntries(['auto', 'app', 'symbol', 'photo', 'none'].map(k => [k, T('icon_' + k)]));
  const SYMBOLS = Object.fromEntries(['phone', 'message', 'video', 'photo', 'camera', 'weather', 'web', 'person', 'alert', 'pill', 'home',
    'heart', 'music', 'radio', 'grid', 'shower', 'cart', 'doctor', 'cards', 'calendar', 'walk', 'food', 'bed', 'water'].map(k => [k, T('sym_' + k)]));
  const PRESETS = [['pill', 'preset_medicine'], ['shower', 'preset_shower'], ['cart', 'preset_shopping'], ['doctor', 'preset_visit'],
    ['cards', 'preset_cards'], ['walk', 'preset_walk'], ['water', 'preset_water'], ['food', 'preset_meal']];
  // Stessi colori dell'app: 5 scuri e 4 chiari, ben distinti anche per la vista che invecchia.
  const PALETTE = [['#1F4FA8', T('c_blue')], ['#1B7A34', T('c_green')], ['#6A1B9A', T('c_purple')], ['#1A1A1A', T('c_black')], ['#C62828', T('c_red')],
    ['#FFA726', T('c_orange')], ['#4FC3F7', T('c_light_blue')], ['#FFFFFF', T('c_white')], ['#FFD54F', T('c_yellow')]];
  const DAYS = T('days_initials').split(',');

  // Se il telefono ha mandato l'elenco delle sue app, il menu mostra quelle (più i ruoli predefiniti).
  const DEV = (window.DEVICE_APPS || []).filter(a => a && a.pkg);
  if (DEV.length) {
    APPS.splice(5, APPS.length - 5, ...DEV.map(a => [a.pkg, a.label]));
    DEV.forEach(a => { APP_LABEL[a.pkg] = a.label; });
  }

  let cfg, page = 0, selId = null, dirty = document.getElementById('pubform').dataset.dirty === '1';
  try { cfg = JSON.parse(ta.value); } catch (e) { cfg = null; }
  if (!cfg) { show('t-json'); return; }
  cfg.pages = cfg.pages || [{cols: 2, rows: 5, tiles: []}];
  cfg.sos = cfg.sos || {numbers: [], holdSeconds: 3, countdownSeconds: 5, message: T('def_sos_message')};
  cfg.reminders = cfg.reminders || [];
  cfg.phone = Object.assign({ringerLock: false, ringVolumePercent: 100, batteryAlertPercent: 15, badges: true, smsUpdateCommand: false, freshStart: true}, cfg.phone || {});
  if (dirty) document.getElementById('dirty').textContent = T('unpublished');

  // -------- utilità
  function el(tag, attrs, ...kids) {
    const n = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
      else if (k === 'style') n.style.cssText = v;
      else if (v === true) n.setAttribute(k, '');
      else if (v !== false && v != null) n.setAttribute(k, v);
    }
    kids.flat().forEach(c => n.append(c instanceof Node ? c : document.createTextNode(c)));
    return n;
  }
  function select(opts, value, onchange, id) {
    return el('select', {id, onchange: e => onchange(e.target.value)},
      Object.entries(opts).map(([v, t]) => el('option', {value: v, selected: v === value}, t)));
  }
  function field(label, input, hint) { return el('div', {}, el('label', {}, label, hint ? el('span', {class: 'hint'}, ' ' + hint) : ''), input); }
  function light(hex) {
    const m = /^#?([0-9a-f]{6})$/i.exec(hex || ''); if (!m) return false;
    const n = parseInt(m[1], 16), c = [n >> 16 & 255, n >> 8 & 255, n & 255].map(v => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; });
    return .2126 * c[0] + .7152 * c[1] + .0722 * c[2] > .45;
  }
  function uid(p) { return p + Date.now().toString(36) + Math.floor(Math.random() * 1e4).toString(36); }
  function changed() {
    ta.value = JSON.stringify(cfg, null, 2); dirty = true;
    document.getElementById('dirty').textContent = T('unpublished');
    issues();
  }
  function cur() { return cfg.pages[page]; }
  function overlap(a, b) { return a.col < b.col + (b.w || 1) && b.col < a.col + (a.w || 1) && a.row < b.row + (b.h || 1) && b.row < a.row + (a.h || 1); }
  function problems() {
    const out = [], ids = {};
    cfg.pages.forEach((p, pi) => {
      p.tiles.forEach(t => {
        if (ids[t.id]) out.push(T('p_dup', t.id)); ids[t.id] = 1;
        const w = t.w || 1, h = t.h || 1;
        if (t.col < 0 || t.row < 0 || t.col + w > p.cols || t.row + h > p.rows) out.push(T('p_out', pi + 1, name(t)));
        p.tiles.forEach(o => { if (o !== t && o.id > t.id && overlap(t, o)) out.push(T('p_overlap', pi + 1, name(t), name(o))); });
        if (['call', 'wa_video', 'sms'].includes(t.type) && !(t.target || '').trim()) out.push(T('p_no_number', name(t)));
        if (t.type === 'url' && !/^https?:\/\//.test(t.target || '')) out.push(T('p_bad_url', name(t)));
      });
    });
    (cfg.sos.numbers || []).forEach(n => { if (!/^\+?\d[\d ]{2,19}$/.test(n)) out.push(T('p_sos_number', n)); });
    const hasSos = cfg.pages.some(p => p.tiles.some(t => t.type === 'sos'));
    if (hasSos && !(cfg.sos.numbers || []).length) out.push(T('p_sos_empty'));
    cfg.reminders.forEach(r => { if (!/^([01]?\d|2[0-3]):[0-5]\d$/.test(r.time || '')) out.push(T('p_rem_time', r.title || '')); });
    return out;
  }
  function newer(v, min) {
    const a = (v || '0').split(/[.-]/).map(n => parseInt(n, 10) || 0), b = min.split('.').map(Number);
    for (let i = 0; i < 3; i++) { if ((a[i] || 0) !== b[i]) return (a[i] || 0) > b[i]; }
    return true;
  }
  function issues() {
    const app = FORM.dataset.app, warn = document.getElementById('warns');
    const usesNew = cfg.pages.some(p => p.tiles.some(t => ['weather', 'photos'].includes(t.type))) || cfg.reminders.some(r => r.date || r.photo);
    const usesNew0100 = cfg.pages.some(p => p.tiles.some(t => t.type === 'missed_calls')) || !!cfg.phone.homeButton;
    const old090 = usesNew && !newer(app, '0.9.0'), old0100 = usesNew0100 && !newer(app, '0.10.0'), old0101 = cfg.pages.some(p => p.tiles.some(t => t.homeButton === false)) && !newer(app, '0.10.1'),
      old0120 = cfg.pages.some(p => p.tiles.some(t => t.type === 'search')) && !newer(app, '0.12.0'),
      old0130 = (cfg.phone.autoAnswer || cfg.phone.announceCaller) && !newer(app, '0.13.0');
    warn.hidden = !(app && (old090 || old0100 || old0101 || old0120 || old0130));
    warn.textContent = old090 ? T('old_app_warn', app || '?') : old0100 ? T('old_app_warn_0100', app || '?') : old0101 ? T('old_app_warn_0101', app || '?') : old0120 ? T('old_app_warn_0120', app || '?') : T('old_app_warn_0130', app || '?');
    const p = problems(), box = document.getElementById('issues');
    box.replaceChildren(...(p.length ? [el('b', {}, T('p_title')), el('ul', {}, p.map(x => el('li', {}, x)))] : []));
    document.getElementById('pubbtn').disabled = p.length > 0;
  }
  function name(t) { return t.type === 'clock' ? T('clock') : (t.label || TYPES[t.type] || t.type); }

  // -------- schede
  function show(id) {
    document.querySelectorAll('[role=tab]').forEach(b => b.setAttribute('aria-selected', String(b.dataset.tab === id)));
    document.querySelectorAll('[role=tabpanel]').forEach(p => p.hidden = p.id !== id);
  }
  document.querySelectorAll('[role=tab]').forEach(b => b.addEventListener('click', () => {
    if (!document.getElementById('t-json').hidden && b.dataset.tab !== 't-json') {
      try { cfg = JSON.parse(ta.value); document.getElementById('jsonerr').textContent = ''; }
      catch (e) { document.getElementById('jsonerr').textContent = T('json_error', e.message); return; }
      page = Math.min(page, cfg.pages.length - 1); render();
    }
    show(b.dataset.tab);
  }));
  ta.addEventListener('input', () => { dirty = true; document.getElementById('dirty').textContent = T('unpublished'); });

  // -------- schermata
  function renderPages() {
    const bar = document.getElementById('pagebar');
    bar.replaceChildren(
      ...cfg.pages.map((_, i) => el('button', {type: 'button', class: 'small sec', 'aria-pressed': String(i === page), onclick: () => { page = i; selId = null; render(); }}, T('page_n', i + 1))),
      el('button', {type: 'button', class: 'small sec', onclick: () => { cfg.pages.push({cols: cur().cols, rows: cur().rows, tiles: []}); page = cfg.pages.length - 1; selId = null; changed(); render(); }}, T('add_page')),
      el('span', {style: 'flex:1'}),
      el('label', {style: 'margin:0', for: 'cols'}, T('columns')), select({1: '1', 2: '2', 3: '3', 4: '4'}, String(cur().cols), v => { cur().cols = +v; changed(); render(); }, 'cols'),
      el('label', {style: 'margin:0', for: 'rows'}, T('rows')), select({3: '3', 4: '4', 5: '5', 6: '6', 7: '7', 8: '8'}, String(cur().rows), v => { cur().rows = +v; changed(); render(); }, 'rows'),
      cfg.pages.length > 1 ? el('button', {type: 'button', class: 'small danger', onclick: () => {
        if (!confirm(T('delete_page_confirm', page + 1))) return;
        cfg.pages.splice(page, 1); page = Math.max(0, page - 1); selId = null; changed(); render();
      }}, T('delete_page')) : '');
    bar.querySelectorAll('select').forEach(s => s.style.width = 'auto');
  }

  function renderGrid() {
    const p = cur(), g = document.getElementById('phonegrid');
    g.style.gridTemplateColumns = 'repeat(' + p.cols + ', minmax(0,1fr))';
    g.style.gridAutoRows = '62px';
    const occ = Array.from({length: p.rows}, () => Array(p.cols).fill(false));
    const kids = [];
    p.tiles.forEach(t => {
      const w = t.w || 1, h = t.h || 1;
      for (let r = t.row; r < Math.min(t.row + h, p.rows); r++) for (let c = t.col; c < Math.min(t.col + w, p.cols); c++) if (r >= 0 && c >= 0) occ[r][c] = true;
      const isL = light(t.color);
      kids.push(el('div', {
        class: 'tile' + (t.id === selId ? ' sel' : '') + (isL ? ' light' : ''), role: 'button', tabindex: '0',
        style: 'grid-column:' + (t.col + 1) + ' / span ' + w + ';grid-row:' + (t.row + 1) + ' / span ' + h + ';background:' + (t.color || '#1F4FA8') + ';color:' + (isL ? '#1A1A1A' : '#fff'),
        onclick: () => { selId = t.id; render(); }, onkeydown: e => { if (e.key === 'Enter') { selId = t.id; render(); } }
      }, t.type === 'clock' ? '🕑 ' + T('clock') : t.type === 'weather' ? '☀ ' + (t.label || T('type_weather')) : t.type === 'photos' ? '🖼 ' + (t.label || T('type_photos')) : t.type === 'missed_calls' ? '📞 ' + (t.label || T('def_missed')) : t.type === 'search' ? '🔍 ' + (t.label || T('def_search')) : t.type === 'sos' ? '⚠ ' + (t.label || 'SOS') : name(t)));
    });
    for (let r = 0; r < p.rows; r++) for (let c = 0; c < p.cols; c++) if (!occ[r][c]) {
      kids.push(el('button', {type: 'button', class: 'cell', title: T('add_tile_here'), 'aria-label': T('add_tile_at', r + 1, c + 1),
        style: 'grid-column:' + (c + 1) + ';grid-row:' + (r + 1), onclick: () => {
          const t = {id: uid('t'), col: c, row: r, w: 1, h: 1, type: 'app', target: '@dialer', label: T('def_phone'), icon: {kind: 'auto', ref: ''}, color: '#1F4FA8'};
          p.tiles.push(t); selId = t.id; changed(); render();
        }}, '+'));
    }
    g.replaceChildren(...kids);
  }

  function renderSide() {
    const side = document.getElementById('side'), p = cur(), t = p.tiles.find(x => x.id === selId);
    if (!t) {
      side.replaceChildren(el('h3', {}, T('how_to_use')), el('p', {}, T('how_to_use_text')),
        el('p', {class: 'hint'}, T('how_to_use_hint')));
      return;
    }
    const upd = (k, v) => { t[k] = v; changed(); render(); };
    t.icon = t.icon || {kind: 'auto', ref: ''};
    const parts = [el('h3', {}, T('selected_tile')),
      field(T('what_it_does'), select(TYPES, t.type, v => {
        // Il valore del tipo precedente non vale per il nuovo (es. «@dialer» per una videochiamata).
        const phoneTypes = ['call', 'wa_video', 'sms'];
        const keep = (phoneTypes.includes(t.type) && phoneTypes.includes(v)) || t.type === v;
        if (!keep) t.target = '';
        t.type = v;
        if (v === 'clock') { t.color = '#FFFFFF'; delete t.label; }
        if (v === 'photos' && !t.label) t.label = T('def_photos');
        if (v === 'search') { t.label = T('def_search'); t.target = ''; t.icon = {kind: 'auto', ref: ''}; }
        if (v === 'missed_calls') { t.label = T('def_missed'); t.target = ''; t.icon = {kind: 'auto', ref: ''}; }
        if (v === 'sos') { t.color = '#B71C1C'; t.label = 'SOS'; t.w = Math.min(2, p.cols); }
        if (['call', 'wa_video', 'sms'].includes(v) && t.icon.kind === 'symbol') t.icon = {kind: 'auto', ref: ''};
        changed(); render();
      }))];

    if (t.type === 'app') {
      const known = APPS.some(a => a[0] === t.target);
      const opts = Object.fromEntries(APPS.concat([['__other', T('other_app')]]));
      parts.push(field(T('which_app'), select(opts, known ? t.target : '__other', v => {
        if (v === '__other') { t.target = ''; } else { t.target = v; if (!t.label || Object.values(APP_LABEL).includes(t.label)) t.label = APP_LABEL[v] || t.label; }
        changed(); render();
      })));
      if (!known) parts.push(field(T('package_name'), el('input', {type: 'text', value: t.target || '', placeholder: T('pkg_placeholder'), onchange: e => upd('target', e.target.value.trim())}),
        DEV.length ? T('app_not_installed') : T('pkg_where')));
      if (!DEV.length) parts.push(el('p', {class: 'hint'}, T('apps_list_hint')));
      parts.push(field(T('tile_open'), select({default: T('open_default'), fresh: T('open_fresh'), resume: T('open_resume')}, t.open || 'default',
        v => { if (v === 'default') delete t.open; else t.open = v; changed(); render(); })));
    }
    if (['call', 'wa_video', 'sms'].includes(t.type)) {
      parts.push(field(T('phone_number'), el('input', {type: 'tel', value: t.target || '', placeholder: T('phone_placeholder'), onchange: e => upd('target', e.target.value.replace(/[^\d+]/g, ''))})));
      if (t.type === 'wa_video') parts.push(el('p', {class: 'hint'}, T('wa_hint')));
      if (t.type === 'sms') parts.push(field(T('msg_text'), el('input', {type: 'text', value: t.body || '', onchange: e => upd('body', e.target.value)})));
    }
    if (t.type === 'weather') {
      const q = el('input', {type: 'text', value: t.label || '', placeholder: T('weather_search')});
      const out = el('div', {});
      parts.push(field(T('weather_place'), q, t.target ? '(' + t.target + ')' : ''),
        el('p', {}, el('button', {type: 'button', class: 'small sec', onclick: async () => {
          out.replaceChildren(T('loading'));
          const list = await searchPlace(q.value).catch(() => []);
          out.replaceChildren(...(list.length ? list.map(pl => el('p', {}, el('button', {type: 'button', class: 'small sec', onclick: () => {
            t.target = pl.target; t.label = pl.name; changed(); render();
          }}, pl.name + (pl.area ? ' (' + pl.area + ')' : '')))) : [el('p', {class: 'hint'}, T('weather_no_results'))]));
        }}, T('weather_search'))), out);
    }
    if (t.type === 'photos') {
      t.photos = t.photos || [];
      parts.push(el('label', {}, T('photos_label')), el('p', {class: 'hint'}, T('photos_hint')),
        el('div', {}, t.photos.map(ref => thumb(ref, () => { t.photos = t.photos.filter(x => x !== ref); changed(); render(); }))),
        field(T('upload_photos'), fileInput(true, refs => { t.photos = [...new Set(t.photos.concat(refs))]; changed(); render(); })));
    }
    if (t.type === 'search') parts.push(el('p', {class: 'hint'}, T('search_hint')));
    if (t.type === 'missed_calls') {
      parts.push(el('p', {class: 'hint'}, T('missed_hint')));
      if (FORM.dataset.edition === 'play') parts.push(el('p', {class: 'msg warn'}, T('missed_hint_play')));
    }
    if (t.type === 'url') parts.push(field(T('address'), el('input', {type: 'url', value: t.target || '', placeholder: 'https://', onchange: e => upd('target', e.target.value.trim())})));
    if (t.type === 'shortcut') parts.push(field(T('shortcut'), el('input', {type: 'text', value: t.target || '', placeholder: T('shortcut_placeholder'), onchange: e => upd('target', e.target.value.trim())}), T('experts')));
    if (t.type !== 'clock') parts.push(field(T('tile_text'), el('input', {type: 'text', value: t.label || '', maxlength: '30', onchange: e => upd('label', e.target.value)})));

    if (!['clock', 'sos', 'weather', 'photos', 'missed_calls'].includes(t.type)) {
      parts.push(field(T('icon'), select(ICONS, t.icon.kind || 'auto', v => { t.icon = {kind: v, ref: v === 'symbol' ? (t.icon.ref && SYMBOLS[t.icon.ref] ? t.icon.ref : 'phone') : (v === 'photo' || v === 'auto' ? /^(contact|media):/.test(t.icon.ref || '') ? t.icon.ref : '' : '')}; changed(); render(); })));
      if (t.icon.kind === 'symbol') parts.push(field(T('symbol'), select(SYMBOLS, t.icon.ref || 'phone', v => { t.icon.ref = v; changed(); render(); })));
      // Foto del familiare caricata da qui: non serve averla nella rubrica del telefono.
      if (['call', 'wa_video', 'sms'].includes(t.type)) {
        const media = (t.icon.ref || '').startsWith('media:') ? t.icon.ref : '';
        parts.push(el('label', {}, T('family_photo')),
          media ? thumb(media, () => { t.icon = {kind: 'auto', ref: '', fill: t.icon.fill}; if (!t.icon.fill) delete t.icon.fill; changed(); render(); }) : '',
          fileInput(false, refs => { t.icon = Object.assign({}, t.icon, {kind: 'photo', ref: refs[0]}); changed(); render(); }),
          el('div', {class: 'hint'}, T('family_photo_hint')));
      }
      if (t.icon.kind === 'photo' || (t.icon.kind === 'auto' && ['call', 'wa_video', 'sms'].includes(t.type)))
        parts.push(el('p', {class: 'hint'}, T('photo_hint')),
          el('label', {style: 'font-weight:400'}, el('input', {type: 'checkbox', checked: !!t.icon.fill,
            onchange: e => { t.icon.fill = e.target.checked; if (!t.icon.fill) delete t.icon.fill; changed(); render(); }}), ' ' + T('photo_fill')),
          el('div', {class: 'hint'}, T('photo_fill_hint')));
    }

    if (cfg.phone.homeButton && ['app', 'shortcut', 'url', 'sms', 'wa_video'].includes(t.type))
      parts.push(el('label', {style: 'font-weight:400'}, el('input', {type: 'checkbox', checked: t.homeButton !== false,
        onchange: e => { if (e.target.checked) delete t.homeButton; else t.homeButton = false; changed(); render(); }}), ' ' + T('tile_home_button')),
        el('div', {class: 'hint'}, T('tile_home_button_hint')));
    parts.push(el('label', {}, T('color')), el('div', {class: 'swatches'}, PALETTE.map(([hex, nm]) =>
      el('button', {type: 'button', class: 'sw', title: nm, 'aria-label': nm, 'aria-pressed': String((t.color || '').toUpperCase() === hex), style: 'background:' + hex, onclick: () => upd('color', hex)}))));

    const nums = n => Object.fromEntries(Array.from({length: n}, (_, i) => [String(i + 1), String(i + 1)]));
    parts.push(el('div', {class: 'row'},
      field(T('width'), select(nums(p.cols), String(t.w || 1), v => upd('w', +v))),
      field(T('height'), select(nums(p.rows), String(t.h || 1), v => upd('h', +v))),
      field(T('column'), select(nums(p.cols), String(t.col + 1), v => upd('col', +v - 1))),
      field(T('row'), select(nums(p.rows), String(t.row + 1), v => upd('row', +v - 1)))));
    const clash = p.tiles.find(o => o !== t && overlap(t, o));
    if (clash) parts.push(el('p', {class: 'issues'}, T('overlaps_move', name(clash))));
    // Spostamento su un'altra pagina: primo posto libero dove entra con la sua misura; se non c'è, solo un avviso.
    if (cfg.pages.length > 1) {
      const moveMsg = el('p', {class: 'issues', hidden: true});
      const others = Object.fromEntries([['', T('move_choose')]].concat(cfg.pages.map((_, i) => [String(i), T('page_n', i + 1)]).filter(([i]) => +i !== page)));
      parts.push(field(T('move_to_page'), select(others, '', v => {
        if (v === '') return;
        const dest = cfg.pages[+v], w = Math.min(t.w || 1, dest.cols), h = Math.min(t.h || 1, dest.rows);
        let spot = null;
        for (let r = 0; r + h <= dest.rows && !spot; r++) for (let c = 0; c + w <= dest.cols && !spot; c++) {
          const probe = {col: c, row: r, w, h};
          if (!dest.tiles.some(o => overlap(probe, o))) spot = probe;
        }
        if (!spot) { moveMsg.textContent = T('move_no_room', +v + 1); moveMsg.hidden = false; return; }
        p.tiles = p.tiles.filter(x => x !== t);
        Object.assign(t, spot); dest.tiles.push(t);
        page = +v; changed(); render();
      })), moveMsg);
    }
    parts.push(el('p', {}, el('button', {type: 'button', class: 'danger', onclick: () => {
      const lastSos = t.type === 'sos' && cfg.pages.every(pp => pp.tiles.filter(x => x.type === 'sos' && x !== t).length === 0);
      if (!confirm(lastSos ? T('last_sos_confirm') : T('delete_confirm', name(t)))) return;
      p.tiles = p.tiles.filter(x => x !== t); selId = null; changed(); render();
    }}, T('delete_tile'))));
    side.replaceChildren(...parts);
  }

  // -------- SOS
  function renderSos() {
    const s = cfg.sos, box = document.getElementById('t-sos');
    s.numbers = s.numbers || [];
    const list = s.numbers.map((n, i) => el('div', {class: 'row', style: 'align-items:flex-end'},
      field(i === 0 ? T('sos_first') : T('sos_other'), el('input', {type: 'tel', value: n, onchange: e => { s.numbers[i] = e.target.value.trim(); changed(); }})),
      el('div', {style: 'flex:0 0 auto'}, el('button', {type: 'button', class: 'small danger', onclick: () => { s.numbers.splice(i, 1); changed(); renderSos(); }}, T('remove')))));
    box.replaceChildren(
      el('p', {}, T('sos_text')),
      el('p', {class: 'hint'}, T('sos_hint')),
      ...list,
      el('p', {}, el('button', {type: 'button', class: 'sec', disabled: s.numbers.length >= 5, onclick: () => { s.numbers.push(''); renderSos(); }}, T('add_number'))),
      el('div', {class: 'row'},
        field(T('hold_seconds'), select({1: '1', 2: '2', 3: '3', 4: '4', 5: '5'}, String(s.holdSeconds || 3), v => { s.holdSeconds = +v; changed(); })),
        field(T('cancel_seconds'), select({3: '3', 5: '5', 8: '8', 10: '10'}, String(s.countdownSeconds || 5), v => { s.countdownSeconds = +v; changed(); }))),
      field(T('sms_text'), el('input', {type: 'text', value: s.message || '', onchange: e => { s.message = e.target.value; changed(); }}), T('sms_text_hint')));
  }

  // -------- promemoria
  function renderRem() {
    const box = document.getElementById('t-rem');
    const items = cfg.reminders.slice().sort((a, b) => (a.time || '').localeCompare(b.time || '')).map(r => {
      const set = (k, v) => { r[k] = v; changed(); };
      const all = !r.days || !r.days.length;
      const oneDay = !!r.date;
      return el('div', {class: 'rem'},
        el('div', {style: 'display:flex;gap:6px;flex-wrap:wrap;margin-bottom:8px'}, PRESETS.map(([ic, key]) =>
          el('button', {type: 'button', class: 'small sec', onclick: () => { r.icon = ic; r.title = T(key); changed(); renderRem(); }}, T(key)))),
        el('div', {class: 'row'},
          field(T('hour'), el('input', {type: 'time', value: r.time || '08:00', onchange: e => { set('time', e.target.value); renderRem(); }})),
          field(T('rem_title'), el('input', {type: 'text', value: r.title || '', onchange: e => set('title', e.target.value)})),
          field(T('detail'), el('input', {type: 'text', value: r.detail || '', placeholder: T('detail_placeholder'), onchange: e => set('detail', e.target.value)}))),
        el('div', {class: 'row'},
          field(T('rem_icon'), select(SYMBOLS, r.icon || 'pill', v => { set('icon', v); renderRem(); })),
          el('div', {}, el('label', {style: 'font-weight:600'}, el('input', {type: 'checkbox', checked: oneDay, onchange: e => {
            if (e.target.checked) { const d = new Date(Date.now() + 86400000); set('date', d.toISOString().slice(0, 10)); } else { delete r.date; changed(); }
            renderRem();
          }}), ' ' + T('rem_one_day')),
          oneDay ? el('input', {type: 'date', value: r.date, onchange: e => set('date', e.target.value)}) : '')),
        oneDay ? '' : el('label', {}, T('days')),
        oneDay ? '' : el('div', {class: 'days'}, DAYS.map((d, i) => el('label', {}, el('input', {type: 'checkbox', checked: all || r.days.includes(i + 1), onchange: e => {
          let ds = all ? [1, 2, 3, 4, 5, 6, 7] : r.days.slice();
          ds = e.target.checked ? ds.concat(i + 1) : ds.filter(x => x !== i + 1);
          ds = [...new Set(ds)].sort();
          set('days', ds.length === 7 || !ds.length ? [] : ds);
        }}), d))),
        el('label', {}, T('rem_photo')),
        el('div', {}, r.photo ? thumb(r.photo, () => { delete r.photo; changed(); renderRem(); }) : '',
          fileInput(false, refs => { r.photo = refs[0]; changed(); renderRem(); })),
        el('div', {class: 'row'},
          field(T('snooze_after'), select({5: '5 min', 10: '10 min', 15: '15 min', 30: '30 min'}, String(r.snoozeMinutes || 10), v => set('snoozeMinutes', +v))),
          field(T('repeat_every'), select({2: '2 min', 5: '5 min', 10: '10 min'}, String(r.repeatMinutes || 5), v => set('repeatMinutes', +v))),
          field(T('at_most'), select({0: T('no_repeat'), 3: T('n_times', 3), 6: T('n_times', 6), 10: T('n_times', 10)}, String(r.maxRepeats ?? 6), v => set('maxRepeats', +v)))),
        el('div', {class: 'row', style: 'align-items:center;margin-top:8px'},
          el('label', {style: 'margin:0;font-weight:400'}, el('input', {type: 'checkbox', checked: r.enabled !== false, onchange: e => set('enabled', e.target.checked)}), ' ' + T('active')),
          el('div', {style: 'flex:0 0 auto'}, el('button', {type: 'button', class: 'small danger', onclick: () => {
            if (!confirm(T('delete_rem_confirm', r.time))) return;
            cfg.reminders = cfg.reminders.filter(x => x !== r); changed(); renderRem();
          }}, T('delete')))));
    });
    box.replaceChildren(
      el('p', {}, T('rem_intro')),
      ...(items.length ? items : [el('p', {class: 'hint'}, T('no_reminders'))]),
      el('p', {}, el('button', {type: 'button', class: 'sec', onclick: () => {
        cfg.reminders.push({id: uid('r'), time: '08:00', days: [], title: T('def_reminder_title'), detail: '', icon: 'pill', snoozeMinutes: 10, repeatMinutes: 5, maxRepeats: 6, enabled: true});
        changed(); renderRem();
      }}, T('add_reminder'))));
    // Nomi leggibili nella tabella esiti
    document.querySelectorAll('[data-rem]').forEach(td => { const r = cfg.reminders.find(x => x.id === td.dataset.rem); if (r) td.textContent = r.time + ' · ' + (r.title || ''); });
  }

  // -------- telefono
  function renderPhone() {
    const p = cfg.phone, box = document.getElementById('t-phone');
    const set = (k, v) => { p[k] = v; changed(); renderPhone(); };
    const check = (k, label, hint) => el('div', {style: 'margin:12px 0'},
      el('label', {style: 'font-weight:600'}, el('input', {type: 'checkbox', checked: !!p[k], onchange: e => set(k, e.target.checked)}), ' ' + label),
      el('div', {class: 'hint'}, hint));
    box.replaceChildren(
      check('blockUnknown', T('block_unknown'), T('block_unknown_hint')),
      check('speakerCalls', T('speaker_calls'), T('speaker_calls_hint')),
      check('autoAnswer', T('auto_answer'), T('auto_answer_hint')),
      p.autoAnswer ? field(T('auto_answer_after'), select({5: '5 s', 10: '10 s', 15: '15 s', 20: '20 s', 30: '30 s'}, String(p.autoAnswerSeconds || 10), v => set('autoAnswerSeconds', +v))) : '',
      check('announceCaller', T('announce_caller'), T('announce_caller_hint')),
      check('speakTiles', T('speak_tiles'), T('speak_tiles_hint')),
      check('voiceConfirm', T('voice_confirm'), T('voice_confirm_hint')),
      check('bedsideClock', T('bedside'), T('bedside_hint')),
      check('homeButton', T('home_button'), T('home_button_hint')),
      p.homeButton ? el('div', {style: 'margin:0 0 12px 24px'},
        field(T('home_side'), select({right: T('home_side_right'), left: T('home_side_left')}, p.homeButtonSide || 'right', v => set('homeButtonSide', v))),
        el('label', {style: 'font-weight:400'}, el('input', {type: 'checkbox', checked: p.homeButtonLabel !== false, onchange: e => set('homeButtonLabel', e.target.checked)}), ' ' + T('home_label')),
        el('label', {}, T('home_color')),
        el('div', {class: 'swatches'}, PALETTE.map(([hex, nm]) => el('button', {type: 'button', class: 'sw', title: nm, 'aria-label': nm,
          'aria-pressed': String((p.homeButtonColor || '#1F4FA8').toUpperCase() === hex), style: 'background:' + hex, onclick: () => set('homeButtonColor', hex)})))) : '',
      check('freshStart', T('fresh_start'), T('fresh_start_hint')),
      check('ringerLock', T('ringer_lock'), T('ringer_lock_hint')),
      p.ringerLock ? field(T('min_volume'), select({30: '30%', 50: '50%', 70: '70%', 80: '80%', 90: '90%', 100: '100%'}, String(p.ringVolumePercent), v => set('ringVolumePercent', +v))) : '',
      field(T('battery_alert'), select(Object.assign({0: T('never_cap')}, ...[10, 15, 20, 25, 30].map(n => ({[n]: T('below_pct', n)}))), String(p.batteryAlertPercent), v => set('batteryAlertPercent', +v)),
        T('battery_alert_hint')),
      ...(['standard', 'play'].includes(document.getElementById('pubform').dataset.edition)
        ? [el('p', {class: 'msg warn'}, T('edition_warn', T(document.getElementById('pubform').dataset.edition === 'play' ? 'edition_play' : 'edition_standard')))]
        : []),
      check('badges', T('badges'), T('badges_hint')),
      check('smsUpdateCommand', T('sms_command'), T('sms_command_hint')));
  }


  // -------- configurazione guidata: poche domande → schermata pronta da controllare e pubblicare
  function renderWizard() {
    const box = document.getElementById('wizbody');
    if (!box || box.dataset.done) return;
    box.dataset.done = '1';
    const fam = [0, 1, 2, 3].map(i => ({
      name: el('input', {type: 'text', placeholder: T('wiz_name_ph')}),
      num: el('input', {type: 'tel', placeholder: T('phone_placeholder')}),
      video: el('input', {type: 'checkbox'}),
      sos: el('input', {type: 'checkbox', checked: i < 2}),
    }));
    const hasWa = !DEV.length || DEV.some(a => a.pkg === 'com.whatsapp');
    const appsOpts = [
      ['@dialer', T('def_phone'), true], ['@sms', T('def_messages'), true], ['com.whatsapp', 'WhatsApp', hasWa && DEV.length > 0],
      ['@camera', T('def_camera'), true], ['@gallery', T('def_gallery'), false], ['missed_calls', T('def_missed'), true],
      ['search', T('def_search'), false], ['photos', T('def_photos'), false], ['@browser', T('def_internet'), false],
    ].map(([k, label, on]) => ({k, label, box: el('input', {type: 'checkbox', checked: on})}));
    const city = el('input', {type: 'text', placeholder: T('wiz_city_ph')});
    const KINDS = {pill: T('preset_medicine'), shower: T('preset_shower'), cart: T('preset_shopping'), doctor: T('preset_visit'), calendar: T('wiz_other')};
    const rem = [0, 1, 2, 3].map(() => {
      const r = {kind: 'pill', title: el('input', {type: 'text', placeholder: T('wiz_rem_ph')}), time: el('input', {type: 'time'})};
      r.sel = select(KINDS, 'pill', v => { r.kind = v; });
      return r;
    });
    const OPTS = [['speakerCalls', T('speaker_calls'), true], ['speakTiles', T('speak_tiles'), true], ['voiceConfirm', T('voice_confirm'), false],
      ['homeButton', T('home_button'), true], ['autoAnswer', T('auto_answer'), true], ['announceCaller', T('announce_caller'), true],
      ['blockUnknown', T('block_unknown'), false], ['bedsideClock', T('bedside'), false]].map(([k, label, on]) => ({k, label, box: el('input', {type: 'checkbox', checked: on})}));
    const out = el('p', {class: 'hint'});
    const check = (b, label) => el('label', {style: 'font-weight:400;display:inline-block;margin-right:16px'}, b, ' ' + label);

    box.replaceChildren(
      el('p', {class: 'hint'}, T('wiz_lead')),
      el('h3', {}, '1. ' + T('wiz_family')),
      el('table', {}, el('tr', {}, el('th', {}, T('wiz_name')), el('th', {}, T('phone_number')), el('th', {}, T('wiz_video')), el('th', {}, 'SOS')),
        fam.map(f => el('tr', {}, el('td', {}, f.name), el('td', {}, f.num), el('td', {style: 'text-align:center'}, f.video), el('td', {style: 'text-align:center'}, f.sos)))),
      el('h3', {}, '2. ' + T('wiz_apps')),
      el('div', {}, appsOpts.map(a => check(a.box, a.label))),
      field(T('wiz_weather'), city, T('wiz_weather_hint')),
      el('h3', {}, '3. ' + T('wiz_reminders')),
      el('table', {}, el('tr', {}, el('th', {}, T('wiz_kind')), el('th', {}, T('wiz_what')), el('th', {}, T('wiz_time'))),
        rem.map(r => el('tr', {}, el('td', {}, r.sel), el('td', {}, r.title), el('td', {}, r.time)))),
      el('h3', {}, '4. ' + T('wiz_options')),
      el('div', {}, OPTS.map(o => check(o.box, o.label))),
      el('p', {}, el('button', {type: 'button', onclick: () => build().catch(e => { out.textContent = String(e); })}, T('wiz_build'))),
      out);

    async function build() {
      const people = fam.map(f => ({name: f.name.value.trim(), num: f.num.value.replace(/[^\d+]/g, ''), video: f.video.checked, sos: f.sos.checked}))
        .filter(f => f.name && f.num);
      if (cfg.pages.some(p => p.tiles.length > 1) && !confirm(T('wiz_replace'))) return;
      const items = [];
      const greens = ['#1B7A34', '#6A1B9A'];
      people.forEach((f, i) => {
        items.push({w: 1, t: {type: 'call', target: f.num, label: f.name, icon: {kind: 'auto', ref: ''}, color: greens[i % 2]}});
        if (f.video) items.push({w: 1, t: {type: 'wa_video', target: f.num, label: T('wiz_video_of', f.name), icon: {kind: 'auto', ref: ''}, color: '#00695C'}});
      });
      let place = null;
      if (city.value.trim()) {
        out.textContent = T('loading');
        place = (await searchPlace(city.value.trim()).catch(() => []))[0] || null;
        if (!place) { out.textContent = T('weather_no_results'); return; }
      }
      if (place) items.push({w: 1, t: {type: 'weather', target: place.target, label: place.name, color: '#0277BD'}});
      appsOpts.filter(a => a.box.checked).forEach(a => {
        if (a.k === 'missed_calls') items.push({w: 2, t: {type: 'missed_calls', target: '', label: T('def_missed'), color: '#FFE7A8'}});
        else if (a.k === 'search') items.push({w: 2, t: {type: 'search', target: '', label: T('def_search'), color: '#37474F'}});
        else if (a.k === 'photos') items.push({w: 1, t: {type: 'photos', target: '', label: T('def_photos'), photos: [], color: '#6A1B9A'}});
        else items.push({w: 1, t: {type: 'app', target: a.k, label: APP_LABEL[a.k] || a.label, icon: {kind: 'auto', ref: ''},
          color: ['@camera', '@gallery'].includes(a.k) ? '#6A1B9A' : a.k === 'com.whatsapp' ? '#1B7A34' : '#1F4FA8'}});
      });
      // Ordine di importanza: familiari, chiamate perse, meteo, app, ricerca. Un riquadro largo
      // che capiterebbe a metà riga aspetta l'inizio della riga successiva, così non restano buchi.
      const prio = t => ({call: 0, wa_video: 0, missed_calls: 1, weather: 2, app: 3, photos: 4, search: 5})[t.type] ?? 3;
      items.sort((a, b) => prio(a.t) - prio(b.t));
      const ROWS = 5, pages = [{cols: 2, rows: ROWS, tiles: [{id: 'clock', col: 0, row: 0, w: 2, h: 1, type: 'clock', color: '#FFFFFF'}]}];
      let pg = 0, row = 1, col = 0;
      const lastRow = () => pg === 0 ? ROWS - 2 : ROWS - 1;   // sulla prima pagina l'ultima riga è dell'SOS
      const put = it => {
        if (row > lastRow()) { pg++; row = 0; col = 0; pages.push({cols: 2, rows: ROWS, tiles: []}); }
        pages[pg].tiles.push(Object.assign({id: uid('t'), col, row, w: it.w, h: 1}, it.t));
        col += it.w;
        if (col >= 2) { col = 0; row++; }
      };
      const waiting = [];
      for (const it of items) {
        if (it.w === 2 && col === 1) { waiting.push(it); continue; }
        put(it);
        while (col === 0 && waiting.length) put(waiting.shift());
      }
      if (col === 1 && waiting.length) { col = 0; row++; }
      waiting.forEach(put);
      pages[0].tiles.push({id: 'sos', col: 0, row: ROWS - 1, w: 2, h: 1, type: 'sos', label: 'SOS', color: '#B71C1C'});
      cfg.pages = pages;
      cfg.sos.numbers = people.filter(f => f.sos).map(f => f.num);
      const rems = rem.filter(r => r.time.value).map(r => ({id: uid('r'), time: r.time.value, days: [],
        title: r.title.value.trim() || KINDS[r.kind], detail: '', icon: r.kind, snoozeMinutes: 10, repeatMinutes: 5, maxRepeats: 6, enabled: true}));
      if (rems.length) cfg.reminders = rems;
      OPTS.forEach(o => { cfg.phone[o.k] = o.box.checked; });
      if (cfg.phone.autoAnswer && !cfg.phone.autoAnswerSeconds) cfg.phone.autoAnswerSeconds = 10;
      page = 0; selId = null; changed(); render();
      document.getElementById('wizard').open = false;
      out.textContent = '';
      document.getElementById('dirty').textContent = T('wiz_done');
      document.querySelector('.tabs').scrollIntoView({behavior: 'smooth'});
    }
  }

  function render() { renderPages(); renderGrid(); renderSide(); renderSos(); renderRem(); renderPhone(); renderWizard(); issues(); }
  render();
  window.addEventListener('beforeunload', e => { if (dirty) { e.preventDefault(); e.returnValue = ''; } });
  document.getElementById('pubform').addEventListener('submit', () => {
    // Se si sta modificando il JSON a mano, si pubblica quello (lo valida il server).
    if (document.getElementById('t-json').hidden) ta.value = JSON.stringify(cfg, null, 2);
    dirty = false;
  });
})();
</script>
</body></html>
