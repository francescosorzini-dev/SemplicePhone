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
const TILE_TYPES = ['app', 'call', 'wa_video', 'sms', 'url', 'shortcut', 'clock', 'sos'];
const EVENT_TITLES = [
    'sos' => '🆘 SOS attivato',
    'reminder_missed' => '💊 Promemoria non confermato',
    'battery' => '🔋 Batteria scarica',
    'offline' => '📵 Telefono non raggiungibile',
    'online' => '✅ Telefono di nuovo raggiungibile',
    'test' => '🔔 Messaggio di prova',
];

// ---------------------------------------------------------------- bootstrap
if (!extension_loaded('sodium')) { http_response_code(500); exit('ext-sodium mancante'); }
foreach ([DATA, DATA . '/devices', DATA . '/reports', DATA . '/events', DATA . '/history'] as $d) if (!is_dir($d)) mkdir($d, 0700, true);
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
    return ($m['name'] ?? '') !== '' ? $m['name'] : "Telefono $id";
}

// ---------------------------------------------------------------- impostazioni e avvisi
function settings(): array {
    return array_replace_recursive([
        'telegramToken' => '', 'telegramChat' => '', 'emailTo' => '', 'emailFrom' => '', 'panelUrl' => '',
        'notify' => ['sos' => true, 'reminder_missed' => true, 'battery' => true, 'offline' => true],
        'offlineHours' => 24,
    ], read_json(DATA . '/settings.json', []));
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
    return [$code >= 200 && $code < 300, $code ? "HTTP $code" : 'connessione non riuscita'];
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
        $out[] = ['canale' => 'Email ' . $s['emailTo'], 'ok' => (bool)$ok, 'info' => $ok ? 'inviata' : 'mail() non riuscita'];
    }
    append_jsonl(DATA . '/alerts.jsonl', ['at' => time(), 'device' => $deviceId, 'title' => $title, 'text' => $text, 'result' => $out]);
    return $out;
}

/** Telefoni che non si fanno sentire da troppo: un avviso per ogni sparizione. Al massimo ogni 10 minuti. */
function run_checks(bool $force = false): void {
    $stamp = DATA . '/checks.json';
    $last = (int)(read_json($stamp, [])['at'] ?? 0);
    if (!$force && time() - $last < 600) return;
    write_atomic($stamp, json_encode(['at' => time()]));
    $s = settings();
    if (empty($s['notify']['offline'])) return;
    $limit = max(1, (int)$s['offlineHours']) * 3600;
    foreach (device_ids() as $id) {
        $m = read_json(dev_path($id, 'meta.json'), []);
        $seen = (int)($m['lastSeen'] ?? 0);
        if (!$seen || !empty($m['offlineAlerted']) || !empty($m['ignoreOffline']) || time() - $seen < $limit) continue;
        $m['offlineAlerted'] = true;
        write_atomic(dev_path($id, 'meta.json'), json_encode($m));
        send_alert(EVENT_TITLES['offline'], 'Nessun contatto da ' . intdiv(time() - $seen, 3600) . ' ore (spento, senza rete o batteria esaurita?).', $id);
    }
}

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
        $meta['appliedSeq'] = $r['seq'] ?? null;
        write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
        if ($backOnline) send_alert(EVENT_TITLES['online'], 'Il telefono si è ricollegato.', $id);
        // Il telefono rimanda l'elenco app solo se cambia: se qui manca (portale nuovo, telefono
        // rimosso e ricollegato…) glielo si chiede esplicitamente.
        echo json_encode(['ok' => true, 'needApps' => !is_file(dev_path($id, 'apps.json'))]);
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
            if (isset(EVENT_TITLES[$type]) && !empty($s['notify'][$type])) {
                send_alert(EVENT_TITLES[$type], mb_substr((string)($ev['text'] ?? ''), 0, 600), $id);
            }
        }
        $meta['eventKeys'] = array_slice($seenKeys, -100);
        write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
        if ($backOnline) send_alert(EVENT_TITLES['online'], 'Il telefono si è ricollegato.', $id);
        http_response_code(204);
        exit;
    }

    write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
    if ($backOnline) send_alert(EVENT_TITLES['online'], 'Il telefono si è ricollegato.', $id);
    run_checks();
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
    if (!is_object($cfg)) return ['la configurazione deve essere un oggetto JSON'];
    $pages = $cfg->pages ?? null;
    if (!is_array($pages) || !$pages) return ['serve almeno una pagina in "pages"'];
    $ids = [];
    foreach ($pages as $pi => $p) {
        $cols = (int)($p->cols ?? 2); $rows = (int)($p->rows ?? 5);
        if ($cols < 1 || $cols > 4 || $rows < 1 || $rows > 8) { $err[] = "pagina $pi: griglia {$cols}x{$rows} fuori limiti"; continue; }
        $grid = [];
        foreach (($p->tiles ?? []) as $t) {
            $id = (string)($t->id ?? '');
            if ($id === '') { $err[] = "pagina $pi: tile senza id"; continue; }
            if (isset($ids[$id])) $err[] = "id duplicato $id";
            $ids[$id] = true;
            if (!in_array($t->type ?? '', TILE_TYPES, true)) $err[] = "tile $id: tipo non valido";
            $c = (int)($t->col ?? -1); $r = (int)($t->row ?? -1); $w = (int)($t->w ?? 1); $h = (int)($t->h ?? 1);
            if ($c < 0 || $r < 0 || $w < 1 || $h < 1 || $c + $w > $cols || $r + $h > $rows) { $err[] = "pagina $pi: tile $id fuori griglia"; continue; }
            for ($y = $r; $y < $r + $h; $y++) for ($x = $c; $x < $c + $w; $x++) {
                if (isset($grid[$y][$x])) $err[] = "pagina $pi: $id sovrapposto a {$grid[$y][$x]}";
                $grid[$y][$x] = $id;
            }
        }
    }
    foreach (($cfg->sos->numbers ?? []) as $n) {
        if (!preg_match('/^\+?\d[\d ]{2,19}$/', (string)$n)) $err[] = "numero SOS non valido: «{$n}»";
    }
    foreach (($cfg->reminders ?? []) as $rem) {
        if (!preg_match('/^([01]?\d|2[0-3]):[0-5]\d$/', (string)($rem->time ?? ''))) $err[] = 'promemoria «' . ($rem->title ?? '?') . '»: orario non valido';
    }
    return $err;
}

function pin_hash(string $pin): string {
    $salt = random_bytes(16);
    $hash = hash_pbkdf2('sha256', $pin, $salt, PBKDF2_ITER, 32, true);
    return 'pbkdf2-sha256$' . PBKDF2_ITER . '$' . base64_encode($salt) . '$' . base64_encode($hash);
}

/** Firma e pubblica: seq monotono, payload = byte esatti firmati. Tiene le ultime versioni nello storico. */
function publish(string $id, $cfg, ?string $pin): int {
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
    write_atomic("$dir/$seq.json", json_encode(['seq' => $seq, 'at' => time(), 'pin' => $pin !== null && $pin !== '', 'config' => $cfg], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
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
        { "id": "whatsapp", "col": 0, "row": 3, "type": "app", "target": "com.whatsapp", "label": "WhatsApp", "color": "#0B6E4F" },
        { "id": "browser", "col": 1, "row": 3, "type": "app", "target": "@browser", "label": "Internet", "icon": { "kind": "symbol", "ref": "web" }, "color": "#1A1A1A" },
        { "id": "sos", "col": 0, "row": 4, "w": 2, "h": 1, "type": "sos", "label": "SOS", "color": "#B71C1C" }
      ]
    }
  ],
  "sos": { "numbers": [], "holdSeconds": 3, "countdownSeconds": 5, "message": "Ho bisogno di aiuto." },
  "reminders": [],
  "phone": { "ringerLock": false, "ringVolumePercent": 100, "batteryAlertPercent": 15, "badges": true, "smsUpdateCommand": false }
}
JSON;

// ---------------------------------------------------------------- pannello di gestione
session_set_cookie_params(['httponly' => true, 'samesite' => 'Strict', 'secure' => !empty($_SERVER['HTTPS'])]);
session_start();
$nonce = base64_encode(random_bytes(16));
header('X-Frame-Options: DENY');
header("Content-Security-Policy: default-src 'self'; style-src 'unsafe-inline'; script-src 'nonce-$nonce'");
$adminFile = DATA . '/admin.json';
$admin = read_json($adminFile);
$csrf = $_SESSION['csrf'] ??= bin2hex(random_bytes(16));
$msg = ''; $err = [];

function h($s): string { return htmlspecialchars((string)$s, ENT_QUOTES); }
function check_csrf(string $csrf): void { if (!hash_equals($csrf, (string)($_POST['csrf'] ?? ''))) { http_response_code(400); exit('Sessione scaduta: ricarica la pagina.'); } }
function ago(?int $t): string {
    if (!$t) return 'mai';
    $d = time() - $t;
    if ($d < 90) return 'adesso';
    if ($d < 3600) return intdiv($d, 60) . ' min fa';
    if ($d < 86400) return intdiv($d, 3600) . ' ore fa';
    return intdiv($d, 86400) . ' giorni fa';
}
function base_url(): string {
    return (!empty($_SERVER['HTTPS']) ? 'https' : 'http') . '://' . ($_SERVER['HTTP_HOST'] ?? 'host') . strtok($_SERVER['REQUEST_URI'] ?? '/', '?');
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    check_csrf($csrf);
    $act = $_POST['act'] ?? '';
    if ($act === 'setup' && !$admin) {
        $pw = (string)($_POST['password'] ?? '');
        if (strlen($pw) < 10) $err[] = 'La password deve avere almeno 10 caratteri.';
        elseif ($pw !== (string)($_POST['password2'] ?? '')) $err[] = 'Le due password non coincidono.';
        else { write_atomic($adminFile, json_encode(['hash' => password_hash($pw, PASSWORD_DEFAULT)])); $_SESSION['ok'] = true; header('Location: ?'); exit; }
    } elseif ($act === 'login' && $admin) {
        usleep(300000);
        if (password_verify((string)($_POST['password'] ?? ''), $admin['hash'])) { session_regenerate_id(true); $_SESSION['ok'] = true; header('Location: ?'); exit; }
        $err[] = 'Password errata.';
    } elseif ($act === 'logout') {
        session_destroy(); header('Location: ?'); exit;
    } elseif (!empty($_SESSION['ok']) && $act === 'settings') {
        $s = settings();
        foreach (['telegramToken', 'telegramChat', 'emailTo', 'emailFrom'] as $k) $s[$k] = trim((string)($_POST[$k] ?? ''));
        foreach (array_keys($s['notify']) as $k) $s['notify'][$k] = !empty($_POST['notify_' . $k]);
        $s['offlineHours'] = max(1, min(168, (int)($_POST['offlineHours'] ?? 24)));
        if ($s['emailTo'] !== '' && !filter_var(explode(',', $s['emailTo'])[0], FILTER_VALIDATE_EMAIL)) $err[] = 'Indirizzo email non valido.';
        if ($s['telegramToken'] !== '' && !preg_match('/^\d+:[\w-]{20,}$/', $s['telegramToken'])) $err[] = 'Il token Telegram non sembra valido (formato 123456:ABC…).';
        if (!$err) { save_settings($s); $msg = 'Impostazioni degli avvisi salvate.'; }
    } elseif (!empty($_SESSION['ok']) && $act === 'test') {
        $res = send_alert(EVENT_TITLES['test'], 'Se leggi questo messaggio, gli avvisi di SemplicePhone funzionano.');
        if (!$res) $err[] = 'Nessun canale configurato: inserisci Telegram o email e salva.';
        foreach ($res as $r) { if ($r['ok']) $msg .= ($msg ? ' ' : '') . $r['canale'] . ': inviato.'; else $err[] = $r['canale'] . ': ' . $r['info']; }
    } elseif (!empty($_SESSION['ok'])) {
        $id = strtolower(trim((string)($_POST['id'] ?? '')));
        if (!valid_id($id)) $err[] = 'Codice telefono non valido: 6–32 caratteri, solo lettere minuscole e cifre.';
        elseif ($act === 'publish') {
            $cfg = json_decode((string)($_POST['config'] ?? ''));
            if (json_last_error() !== JSON_ERROR_NONE) $err[] = 'Configurazione non leggibile: ' . json_last_error_msg();
            else $err = validate_config($cfg);
            $pin = trim((string)($_POST['pin'] ?? ''));
            if ($pin !== '' && !preg_match('/^\d{4,8}$/', $pin)) $err[] = 'Il PIN deve avere da 4 a 8 cifre.';
            if (!$err) {
                write_atomic(dev_path($id, 'config.json'), json_encode($cfg, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
                $meta = read_json(dev_path($id, 'meta.json'), []);
                $meta['name'] = trim((string)($_POST['name'] ?? ($meta['name'] ?? '')));
                write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
                publish($id, $cfg, $pin !== '' ? $pin : null);
                $msg = 'Pubblicato. Il telefono lo riceve entro 15 minuti (con rete), subito con «Aggiorna ora» nelle sue impostazioni o con l\'SMS «#AGGIORNA» se attivato.';
            }
        } elseif ($act === 'rename') {
            $meta = read_json(dev_path($id, 'meta.json'), []);
            $meta['name'] = trim((string)($_POST['name'] ?? ''));
            write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
            header('Location: ?edit=' . urlencode($id)); exit;
        } elseif ($act === 'forget') {
            @unlink(dev_path($id, 'secret'));
            $msg = 'Fatto: il prossimo telefono che si collega con questo codice viene accettato.';
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
    if ($s['panelUrl'] !== base_url()) { $s['panelUrl'] = base_url(); save_settings($s); }
    run_checks();
}
?><!doctype html>
<html lang="it"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SemplicePhone – gestione</title>
<style>
:root{--ink:#1A1A1A;--mute:#5a5650;--line:#d9d3c7;--bg:#F7F5F0;--card:#fff;--accent:#1F4FA8;--ok:#1B6E34;--warn:#A84300;--bad:#B71C1C}
*{box-sizing:border-box}
body{font:16px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif;margin:0;background:var(--bg);color:var(--ink)}
header.top{background:var(--ink);color:#fff}
header.top .in{max-width:1080px;margin:0 auto;padding:12px 16px;display:flex;align-items:center;gap:12px}
header.top .brand{font-weight:700;font-size:19px;flex:1}
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
<header class="top"><div class="in"><div class="brand">SemplicePhone · gestione</div>
<?php if ($logged): ?><nav class="nav"><a href="?" <?= $view !== 'alerts' ? 'aria-current="page"' : '' ?>>Telefoni</a><a href="?avvisi" <?= $view === 'alerts' ? 'aria-current="page"' : '' ?>>Avvisi</a></nav><?php endif; ?>
<?php if ($logged): ?><form method="post" style="margin:0"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="logout"><button class="small sec">Esci</button></form><?php endif; ?>
</div></header>
<main>
<?php if ($msg): ?><div class="msg ok" role="status"><?= h($msg) ?></div><?php endif; ?>
<?php foreach ($err as $e): ?><div class="msg err" role="alert"><?= h($e) ?></div><?php endforeach; ?>

<?php if (!$admin): ?>
  <h1>Benvenuto</h1>
  <p class="lead">Primo avvio: scegli la password con cui entrerai in questo pannello.</p>
  <form method="post" class="card" style="max-width:480px"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="setup">
    <label for="p1">Password <span class="hint">(almeno 10 caratteri)</span></label><input id="p1" type="password" name="password" autocomplete="new-password" required minlength="10">
    <label for="p2">Ripeti la password</label><input id="p2" type="password" name="password2" autocomplete="new-password" required minlength="10">
    <p><button>Inizia</button></p>
  </form>

<?php elseif (!$logged): ?>
  <h1>Accedi</h1>
  <form method="post" class="card" style="max-width:420px"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="login">
    <label for="pw">Password</label><input id="pw" type="password" name="password" autocomplete="current-password" required autofocus>
    <p><button>Entra</button></p>
  </form>

<?php elseif ($view === 'alerts'):
    $s = settings();
    $log = array_reverse(tail_jsonl(DATA . '/alerts.jsonl', 30));
?>
  <h1>Avvisi</h1>
  <p class="lead">Ricevi subito un messaggio quando succede qualcosa su uno dei telefoni.</p>
  <form method="post" class="card"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="settings">
    <h3>Quando avvisare</h3>
    <?php foreach (['sos' => 'Quando viene premuto SOS', 'reminder_missed' => 'Quando un promemoria non viene confermato', 'battery' => 'Quando la batteria è quasi scarica', 'offline' => 'Quando un telefono non si fa sentire'] as $k => $label): ?>
      <label style="font-weight:400"><input type="checkbox" name="notify_<?= $k ?>" <?= !empty($s['notify'][$k]) ? 'checked' : '' ?>> <?= h($label) ?></label>
    <?php endforeach; ?>
    <div class="row"><div><label for="oh">Dopo quante ore senza notizie</label><input id="oh" type="number" name="offlineHours" min="1" max="168" value="<?= (int)$s['offlineHours'] ?>"></div><div></div></div>

    <h3>Telegram <span class="hint">(consigliato: arriva subito sul cellulare)</span></h3>
    <details><summary>Come si configura</summary>
      <ol class="steps">
        <li>In Telegram scrivi a <b>@BotFather</b>, invia <code>/newbot</code> e segui le istruzioni: ricevi un <b>token</b>.</li>
        <li>Apri il bot appena creato e premi <b>Avvia</b> (ogni familiare che vuole gli avvisi deve farlo).</li>
        <li>Per sapere il tuo <b>chat id</b> scrivi a <b>@userinfobot</b>. Per un gruppo, aggiungi il bot al gruppo e usa l'id del gruppo (inizia con -).</li>
      </ol>
    </details>
    <div class="row">
      <div><label for="tt">Token del bot</label><input id="tt" type="password" name="telegramToken" value="<?= h($s['telegramToken']) ?>" autocomplete="off" placeholder="123456:ABC…"></div>
      <div><label for="tc">Chat id <span class="hint">(più di uno separati da virgola)</span></label><input id="tc" type="text" name="telegramChat" value="<?= h($s['telegramChat']) ?>"></div>
    </div>
    <h3>Email</h3>
    <div class="row">
      <div><label for="et">Destinatari <span class="hint">(separati da virgola)</span></label><input id="et" type="text" name="emailTo" value="<?= h($s['emailTo']) ?>"></div>
      <div><label for="ef">Mittente <span class="hint">(facoltativo)</span></label><input id="ef" type="text" name="emailFrom" value="<?= h($s['emailFrom']) ?>" placeholder="semplicephone@<?= h($_SERVER['HTTP_HOST'] ?? 'tuosito') ?>"></div>
    </div>
    <p class="hint">L'email usa la funzione mail() dell'hosting: se non arriva, controlla la cartella spam o usa Telegram.</p>
    <p class="row" style="gap:8px"><button>Salva</button></p>
  </form>
  <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="test"><button class="sec">Invia un messaggio di prova</button></form>
  <p class="hint">Il controllo dei telefoni silenziosi parte da solo quando qualcuno apre il pannello o un telefono si collega. Per farlo anche di notte, se l'hosting lo permette aggiungi un cron ogni 10 minuti: <code>php <?= h(__FILE__) ?> cron</code></p>

  <h2>Ultimi avvisi inviati</h2>
  <div class="card">
  <?php if (!$log): ?><p class="hint">Nessun avviso inviato finora.</p><?php else: ?>
    <table><tr><th>Quando</th><th>Avviso</th><th>Esito</th></tr>
    <?php foreach ($log as $a): $okAll = $a['result'] && !in_array(false, array_column($a['result'], 'ok'), true); ?>
      <tr><td><?= h(date('d/m H:i', (int)$a['at'])) ?></td><td><b><?= h($a['title']) ?></b><?= $a['device'] ? ' · ' . h(device_name($a['device'])) : '' ?><br><span class="hint"><?= h($a['text']) ?></span></td>
      <td><span class="pill <?= !$a['result'] ? 'mute' : ($okAll ? 'ok' : 'bad') ?>"><?= !$a['result'] ? 'Nessun canale' : ($okAll ? 'Inviato' : 'Errore') ?></span></td></tr>
    <?php endforeach; ?></table>
  <?php endif; ?>
  </div>

<?php elseif ($view === 'list'):
    $ids = array_fill_keys(device_ids(), true);
?>
  <h1>I tuoi telefoni</h1>
  <p class="lead">Tocca un telefono per cambiarne la schermata, i numeri SOS e i promemoria.</p>
  <?php $st = settings(); if ($st['telegramToken'] === '' && $st['emailTo'] === ''): ?>
    <div class="msg warn">Gli avvisi immediati (SOS, promemoria saltati, batteria) non sono ancora attivi. <a href="?avvisi">Configurali</a>.</div>
  <?php endif; ?>
  <?php if (!$ids): ?><div class="card">Nessun telefono collegato. Segui i passi qui sotto per collegare il primo.</div><?php endif; ?>
  <div class="grid-devices">
  <?php foreach (array_keys($ids) as $id): $m = read_json(dev_path($id, 'meta.json'), []);
      $seen = (int)($m['lastSeen'] ?? 0);
      $online = $seen && time() - $seen < 8 * 3600;
      $synced = isset($m['seq'], $m['appliedSeq']) && (int)$m['appliedSeq'] >= (int)$m['seq'];
  ?>
    <a class="dev" href="?edit=<?= h($id) ?>">
      <div class="name"><?= h(($m['name'] ?? '') !== '' ? $m['name'] : 'Telefono senza nome') ?></div>
      <div class="hint">Codice <?= h($id) ?> · ultimo contatto <?= h(ago($seen ?: null)) ?></div>
      <span class="pill <?= $seen ? ($online ? 'ok' : 'warn') : 'mute' ?>"><?= $seen ? ($online ? 'Collegato' : 'Non si sente da un po\'') : 'Mai collegato' ?></span>
      <?php if (isset($m['battery']) && $m['battery'] >= 0): ?><span class="pill <?= $m['battery'] < 20 && empty($m['charging']) ? 'bad' : 'mute' ?>">Batteria <?= (int)$m['battery'] ?>%<?= !empty($m['charging']) ? ' in carica' : '' ?></span><?php endif; ?>
      <?php if (isset($m['seq'])): ?><span class="pill <?= $synced ? 'ok' : 'warn' ?>"><?= $synced ? 'Aggiornato' : 'Modifiche in arrivo' ?></span><?php else: ?><span class="pill mute">Da configurare</span><?php endif; ?>
    </a>
  <?php endforeach; ?>
  </div>

  <h2>Collegare un telefono</h2>
  <div class="card">
    <ol class="steps">
      <li>Sul telefono tocca <b>5 volte</b> l'orologio, inserisci il PIN e apri <b>Gestione da remoto</b>.</li>
      <li>Copia questi due valori nei campi del telefono:
        <label>Indirizzo</label><code class="k" id="u"><?= h(base_url()) ?>?device={device}</code><button class="small sec" data-copy="u">Copia indirizzo</button>
        <label>Chiave pubblica</label><code class="k" id="k"><?= h(public_key_b64()) ?></code><button class="small sec" data-copy="k">Copia chiave</button>
      </li>
      <li>Attiva l'interruttore e premi <b>Aggiorna ora</b>: il telefono compare in questa pagina.</li>
    </ol>
    <details><summary>Preparare la configurazione prima di collegare il telefono</summary>
      <form method="get" class="row" style="align-items:flex-end;margin-top:8px">
        <div><label for="nid">Codice del telefono <span class="hint">(lo vedi in Gestione da remoto)</span></label><input id="nid" type="text" name="edit" pattern="[a-z0-9]{6,32}" required></div>
        <div style="flex:0 0 auto"><button>Apri</button></div>
      </form>
    </details>
  </div>

<?php else:
    $meta = read_json(dev_path($edit, 'meta.json'), []);
    $src = $_POST['config'] ?? (is_file(dev_path($edit, 'config.json')) ? file_get_contents(dev_path($edit, 'config.json')) : DEFAULT_CONFIG);
    $loaded = '';
    if (!isset($_POST['config']) && isset($_GET['from']) && is_file($hf = DATA . "/history/$edit/" . (int)$_GET['from'] . '.json')) {
        $hv = read_json($hf, []);
        $src = json_encode($hv['config'] ?? [], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        $loaded = 'Hai caricato la versione del ' . date('d/m/Y H:i', (int)($hv['at'] ?? 0)) . '. Controlla e premi «Pubblica sul telefono» per applicarla.';
    } elseif (!isset($_POST['config']) && isset($_GET['copy']) && valid_id($cp = strtolower((string)$_GET['copy'])) && is_file(dev_path($cp, 'config.json'))) {
        $src = file_get_contents(dev_path($cp, 'config.json'));
        $loaded = 'Hai copiato la configurazione di «' . device_name($cp) . '», compresi numeri SOS e promemoria. Controlla e premi «Pubblica sul telefono».';
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
  <p><a href="?">← Tutti i telefoni</a></p>
  <h1><?= h(($meta['name'] ?? '') !== '' ? $meta['name'] : 'Telefono senza nome') ?></h1>
  <p class="lead">Codice <?= h($edit) ?> · ultimo contatto <?= h(ago(isset($meta['lastSeen']) ? (int)$meta['lastSeen'] : null)) ?>
    <?php if (isset($meta['battery']) && $meta['battery'] >= 0): ?> · batteria <?= (int)$meta['battery'] ?>%<?php endif; ?>
    <?php if (!empty($meta['appVersion'])): ?> · app <?= h($meta['appVersion']) ?><?php endif; ?></p>

  <?php if ($loaded): ?><div class="msg warn"><?= h($loaded) ?></div><?php endif; ?>
  <?php if (isset($meta['badgesAccess']) && !$meta['badgesAccess']): ?><div class="msg warn">Sul telefono manca l'«Accesso alle notifiche»: i pallini sui riquadri non compaiono finché non viene concesso dalle impostazioni di SemplicePhone.</div><?php endif; ?>
  <form method="post" id="pubform" data-dirty="<?= $loaded ? '1' : '0' ?>" data-edition="<?= h($meta['edition'] ?? '') ?>">
    <input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="publish"><input type="hidden" name="id" value="<?= h($edit) ?>">
    <div class="row">
      <div><label for="name">Nome <span class="hint">(solo per te, es. «Nonna Maria»)</span></label><input id="name" type="text" name="name" value="<?= h($meta['name'] ?? '') ?>"></div>
      <div><label for="pin">Nuovo PIN del telefono <span class="hint">(lascia vuoto per non cambiarlo)</span></label><input id="pin" type="text" name="pin" inputmode="numeric" pattern="\d{4,8}" autocomplete="off" placeholder="4–8 cifre"></div>
    </div>

    <div class="tabs" role="tablist">
      <button type="button" role="tab" aria-selected="true" data-tab="t-screen">Schermata</button>
      <button type="button" role="tab" aria-selected="false" data-tab="t-sos">SOS</button>
      <button type="button" role="tab" aria-selected="false" data-tab="t-rem">Promemoria</button>
      <button type="button" role="tab" aria-selected="false" data-tab="t-phone">Telefono</button>
      <button type="button" role="tab" aria-selected="false" data-tab="t-json">Avanzato</button>
    </div>

    <section class="tabpanel" id="t-screen" role="tabpanel">
      <div class="pagebar" id="pagebar"></div>
      <div class="editor">
        <div class="phone" aria-label="Anteprima della schermata"><div class="phonegrid" id="phonegrid"></div></div>
        <div class="side" id="side"></div>
      </div>
    </section>
    <section class="tabpanel" id="t-sos" role="tabpanel" hidden></section>
    <section class="tabpanel" id="t-rem" role="tabpanel" hidden></section>
    <section class="tabpanel" id="t-phone" role="tabpanel" hidden></section>
    <section class="tabpanel" id="t-json" role="tabpanel" hidden>
      <p class="hint">La stessa configurazione in formato JSON. Le modifiche fatte qui si vedono nelle altre schede.</p>
      <textarea class="json" name="config" id="config" spellcheck="false"><?= h($src) ?></textarea>
      <div class="issues" id="jsonerr"></div>
    </section>

    <div class="issues" id="issues" role="alert"></div>
    <div class="sticky">
      <button id="pubbtn">Pubblica sul telefono</button>
      <span class="hint" id="dirty"></span>
    </div>
  </form>

  <h2>Avvisi da questo telefono</h2>
  <div class="card">
  <?php if (!$urgent): ?><p class="hint">Nessun avviso ricevuto.</p><?php else: ?>
    <table><tr><th>Quando</th><th>Cosa</th></tr>
    <?php foreach ($urgent as $ev): ?><tr><td><?= h(date('d/m H:i', (int)(($ev['at'] ?? 0) / 1000))) ?></td><td><b><?= h(EVENT_TITLES[$ev['type'] ?? ''] ?? ($ev['type'] ?? '')) ?></b><br><span class="hint"><?= h($ev['text'] ?? '') ?></span></td></tr><?php endforeach; ?>
    </table>
  <?php endif; ?>
  </div>

  <h2>Versioni precedenti</h2>
  <div class="card">
  <?php if (!$versions): ?><p class="hint">Ancora nessuna pubblicazione.</p><?php else: ?>
    <table><tr><th>Pubblicata</th><th></th></tr>
    <?php foreach ($versions as $i => $v): $hv = read_json(DATA . "/history/$edit/$v.json", []); ?>
      <tr><td><?= h(date('d/m/Y H:i', (int)($hv['at'] ?? 0))) ?><?= $i === 0 ? ' <span class="pill ok">attuale</span>' : '' ?><?= !empty($hv['pin']) ? ' <span class="pill mute">PIN cambiato</span>' : '' ?></td>
      <td style="text-align:right"><?php if ($i > 0): ?><a class="btn sec" href="?edit=<?= h($edit) ?>&amp;from=<?= (int)$v ?>">Carica nell'editor</a><?php endif; ?></td></tr>
    <?php endforeach; ?></table>
  <?php endif; ?>
  <?php if ($others): ?>
    <form method="get" class="row" style="align-items:flex-end;margin-top:12px"><input type="hidden" name="edit" value="<?= h($edit) ?>">
      <div><label for="cp">Copia la configurazione da un altro telefono</label>
        <select id="cp" name="copy"><?php foreach ($others as $o): ?><option value="<?= h($o) ?>"><?= h(device_name($o)) ?></option><?php endforeach; ?></select></div>
      <div style="flex:0 0 auto"><button class="sec">Copia nell'editor</button></div>
    </form>
  <?php endif; ?>
  </div>

  <h2>App installate sul telefono</h2>
  <div class="card">
  <?php if (!$devApps): ?>
    <p class="hint">Elenco non ancora ricevuto. Sul telefono (versione 0.4.1 o successiva) apri Gestione da remoto e premi «Aggiorna ora».</p>
  <?php else: ?>
    <details><summary><?= count($devApps) ?> app · aggiornato <?= h(ago(filemtime(dev_path($edit, 'apps.json')) ?: null)) ?></summary>
      <table style="margin-top:8px"><tr><th>Nome</th><th>Pacchetto</th></tr>
      <?php foreach ($devApps as $a): ?><tr><td><?= h($a['label']) ?></td><td class="hint"><?= h($a['pkg']) ?></td></tr><?php endforeach; ?>
      </table>
    </details>
  <?php endif; ?>
  </div>

  <h2>Promemoria: cosa è successo</h2>
  <div class="card">
  <?php if (!$events): ?><p class="hint">Ancora nessuna notizia dal telefono.</p><?php else: ?>
    <table><tr><th>Quando</th><th>Promemoria</th><th>Esito</th></tr>
    <?php foreach ($events as $ev): $o = $ev['outcome'] ?? ''; ?><tr><td><?= h(date('d/m H:i', (int)(($ev['at'] ?? 0) / 1000))) ?></td><td data-rem="<?= h($ev['id'] ?? '') ?>"><?= h($ev['id'] ?? '') ?></td>
      <td><span class="pill <?= $o === 'preso' ? 'ok' : ($o === 'rinviato' ? 'warn' : 'bad') ?>"><?= h(['preso' => 'Preso', 'rinviato' => 'Rinviato', 'non_confermato' => 'Non confermato'][$o] ?? $o) ?></span></td></tr><?php endforeach; ?>
    </table>
  <?php endif; ?>
  </div>

  <details class="card"><summary>Il telefono è stato reinstallato o sostituito?</summary>
    <p>Per sicurezza il pannello accetta solo il telefono che si è collegato la prima volta. Dopo una reinstallazione premi qui e ricollega il telefono.</p>
    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="forget"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="danger">Accetta di nuovo il prossimo collegamento</button></form>
  </details>
  <details class="card"><summary>Altre azioni</summary>
    <form method="post" style="margin:10px 0"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="ignore"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="sec"><?= empty($meta['ignoreOffline']) ? 'Non avvisare se questo telefono resta spento' : 'Riattiva l\'avviso «telefono non raggiungibile»' ?></button></form>
    <form method="post" id="delform"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="delete"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="danger">Rimuovi questo telefono dal pannello</button></form>
  </details>
  <script nonce="<?= h($nonce) ?>">
    window.DEVICE_APPS = <?= json_encode($devApps, JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT | JSON_UNESCAPED_UNICODE) ?>;
    document.getElementById('delform').addEventListener('submit', e => { if (!confirm('Rimuovere il telefono, il suo storico e i suoi avvisi dal pannello?')) e.preventDefault(); });
  </script>
<?php endif; ?>
<footer class="hint" style="margin:32px 0 8px;text-align:center">SemplicePhone <?= h(VERSION) ?> · software libero <a href="<?= h(SOURCE_URL) ?>">AGPL-3.0 — codice sorgente</a></footer>
</main>

<script nonce="<?= h($nonce) ?>">
// Copia negli appunti (pagina elenco)
document.querySelectorAll('[data-copy]').forEach(b => b.addEventListener('click', e => {
  e.preventDefault();
  const t = document.getElementById(b.dataset.copy).textContent.trim();
  (navigator.clipboard ? navigator.clipboard.writeText(t) : Promise.reject()).then(
    () => { const o = b.textContent; b.textContent = 'Copiato ✓'; setTimeout(() => b.textContent = o, 1500); },
    () => { prompt('Copia questo valore:', t); });
}));

(function () {
  const ta = document.getElementById('config');
  if (!ta) return;

  const TYPES = {app: 'Apri un\'app', call: 'Chiama un numero', wa_video: 'Videochiamata WhatsApp', sms: 'Messaggio già scritto',
    url: 'Pagina web', shortcut: 'Scorciatoia di un\'app', clock: 'Orologio e data', sos: 'Pulsante SOS'};
  const APPS = [['@dialer', 'Telefono (quella del telefono)'], ['@sms', 'Messaggi (quella del telefono)'], ['@camera', 'Fotocamera (quella del telefono)'],
    ['@gallery', 'Galleria foto (quella del telefono)'], ['@browser', 'Internet (quello del telefono)'], ['com.whatsapp', 'WhatsApp'],
    ['org.telegram.messenger', 'Telegram'], ['com.google.android.youtube', 'YouTube'], ['com.google.android.apps.maps', 'Google Maps'],
    ['com.google.android.gm', 'Gmail'], ['com.android.chrome', 'Chrome']];
  const APP_LABEL = {'@dialer': 'Telefono', '@sms': 'Messaggi', '@camera': 'Fotocamera', '@gallery': 'Foto', '@browser': 'Internet',
    'com.whatsapp': 'WhatsApp', 'org.telegram.messenger': 'Telegram', 'com.google.android.youtube': 'YouTube',
    'com.google.android.apps.maps': 'Mappe', 'com.google.android.gm': 'Posta', 'com.android.chrome': 'Internet'};
  const ICONS = {auto: 'Automatica', app: 'Icona dell\'app', symbol: 'Simbolo', photo: 'Foto del contatto', none: 'Nessuna'};
  const SYMBOLS = {phone: 'Telefono', message: 'Messaggio', video: 'Video', photo: 'Foto', camera: 'Fotocamera', weather: 'Meteo', web: 'Internet',
    person: 'Persona', alert: 'Attenzione', pill: 'Medicina', home: 'Casa', heart: 'Cuore', music: 'Musica', radio: 'Radio', grid: 'Riquadri'};
  const PALETTE = [['#1F4FA8', 'Blu'], ['#0B6E4F', 'Verde'], ['#6A1B9A', 'Viola'], ['#1A1A1A', 'Nero'], ['#B71C1C', 'Rosso'],
    ['#A84300', 'Arancio'], ['#00695C', 'Petrolio'], ['#FFFFFF', 'Bianco'], ['#FFF3C4', 'Giallo chiaro']];
  const DAYS = ['L', 'M', 'M', 'G', 'V', 'S', 'D'];

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
  cfg.sos = cfg.sos || {numbers: [], holdSeconds: 3, countdownSeconds: 5, message: 'Ho bisogno di aiuto.'};
  cfg.reminders = cfg.reminders || [];
  cfg.phone = Object.assign({ringerLock: false, ringVolumePercent: 100, batteryAlertPercent: 15, badges: true, smsUpdateCommand: false}, cfg.phone || {});
  if (dirty) document.getElementById('dirty').textContent = 'Modifiche non ancora pubblicate';

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
    document.getElementById('dirty').textContent = 'Modifiche non ancora pubblicate';
    issues();
  }
  function cur() { return cfg.pages[page]; }
  function overlap(a, b) { return a.col < b.col + (b.w || 1) && b.col < a.col + (a.w || 1) && a.row < b.row + (b.h || 1) && b.row < a.row + (a.h || 1); }
  function problems() {
    const out = [], ids = {};
    cfg.pages.forEach((p, pi) => {
      p.tiles.forEach(t => {
        if (ids[t.id]) out.push('Due riquadri hanno lo stesso codice (' + t.id + ').'); ids[t.id] = 1;
        const w = t.w || 1, h = t.h || 1;
        if (t.col < 0 || t.row < 0 || t.col + w > p.cols || t.row + h > p.rows) out.push('Pagina ' + (pi + 1) + ': «' + name(t) + '» esce dalla griglia.');
        p.tiles.forEach(o => { if (o !== t && o.id > t.id && overlap(t, o)) out.push('Pagina ' + (pi + 1) + ': «' + name(t) + '» e «' + name(o) + '» si sovrappongono.'); });
        if (['call', 'wa_video', 'sms'].includes(t.type) && !(t.target || '').trim()) out.push('«' + name(t) + '»: manca il numero di telefono.');
        if (t.type === 'url' && !/^https?:\/\//.test(t.target || '')) out.push('«' + name(t) + '»: l\'indirizzo web deve iniziare con https://');
      });
    });
    (cfg.sos.numbers || []).forEach(n => { if (!/^\+?\d[\d ]{2,19}$/.test(n)) out.push('Numero SOS non valido: «' + n + '».'); });
    const hasSos = cfg.pages.some(p => p.tiles.some(t => t.type === 'sos'));
    if (hasSos && !(cfg.sos.numbers || []).length) out.push('Il pulsante SOS non ha numeri: aggiungili nella scheda SOS.');
    cfg.reminders.forEach(r => { if (!/^([01]?\d|2[0-3]):[0-5]\d$/.test(r.time || '')) out.push('Promemoria «' + (r.title || '') + '»: orario non valido.'); });
    return out;
  }
  function issues() {
    const p = problems(), box = document.getElementById('issues');
    box.replaceChildren(...(p.length ? [el('b', {}, 'Da sistemare prima di pubblicare:'), el('ul', {}, p.map(x => el('li', {}, x)))] : []));
    document.getElementById('pubbtn').disabled = p.length > 0;
  }
  function name(t) { return t.type === 'clock' ? 'Orologio' : (t.label || TYPES[t.type] || t.type); }

  // -------- schede
  function show(id) {
    document.querySelectorAll('[role=tab]').forEach(b => b.setAttribute('aria-selected', String(b.dataset.tab === id)));
    document.querySelectorAll('[role=tabpanel]').forEach(p => p.hidden = p.id !== id);
  }
  document.querySelectorAll('[role=tab]').forEach(b => b.addEventListener('click', () => {
    if (!document.getElementById('t-json').hidden && b.dataset.tab !== 't-json') {
      try { cfg = JSON.parse(ta.value); document.getElementById('jsonerr').textContent = ''; }
      catch (e) { document.getElementById('jsonerr').textContent = 'Il JSON contiene un errore: ' + e.message; return; }
      page = Math.min(page, cfg.pages.length - 1); render();
    }
    show(b.dataset.tab);
  }));
  ta.addEventListener('input', () => { dirty = true; document.getElementById('dirty').textContent = 'Modifiche non ancora pubblicate'; });

  // -------- schermata
  function renderPages() {
    const bar = document.getElementById('pagebar');
    bar.replaceChildren(
      ...cfg.pages.map((_, i) => el('button', {type: 'button', class: 'small sec', 'aria-pressed': String(i === page), onclick: () => { page = i; selId = null; render(); }}, 'Pagina ' + (i + 1))),
      el('button', {type: 'button', class: 'small sec', onclick: () => { cfg.pages.push({cols: cur().cols, rows: cur().rows, tiles: []}); page = cfg.pages.length - 1; selId = null; changed(); render(); }}, '+ Pagina'),
      el('span', {style: 'flex:1'}),
      el('label', {style: 'margin:0', for: 'cols'}, 'Colonne'), select({1: '1', 2: '2', 3: '3', 4: '4'}, String(cur().cols), v => { cur().cols = +v; changed(); render(); }, 'cols'),
      el('label', {style: 'margin:0', for: 'rows'}, 'Righe'), select({3: '3', 4: '4', 5: '5', 6: '6', 7: '7', 8: '8'}, String(cur().rows), v => { cur().rows = +v; changed(); render(); }, 'rows'),
      cfg.pages.length > 1 ? el('button', {type: 'button', class: 'small danger', onclick: () => {
        if (!confirm('Eliminare la pagina ' + (page + 1) + ' e i suoi riquadri?')) return;
        cfg.pages.splice(page, 1); page = Math.max(0, page - 1); selId = null; changed(); render();
      }}, 'Elimina pagina') : '');
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
      }, t.type === 'clock' ? '🕑 Orologio' : t.type === 'sos' ? '⚠ ' + (t.label || 'SOS') : name(t)));
    });
    for (let r = 0; r < p.rows; r++) for (let c = 0; c < p.cols; c++) if (!occ[r][c]) {
      kids.push(el('button', {type: 'button', class: 'cell', title: 'Aggiungi un riquadro qui', 'aria-label': 'Aggiungi riquadro riga ' + (r + 1) + ' colonna ' + (c + 1),
        style: 'grid-column:' + (c + 1) + ';grid-row:' + (r + 1), onclick: () => {
          const t = {id: uid('t'), col: c, row: r, w: 1, h: 1, type: 'app', target: '@dialer', label: 'Telefono', icon: {kind: 'auto', ref: ''}, color: '#1F4FA8'};
          p.tiles.push(t); selId = t.id; changed(); render();
        }}, '+'));
    }
    g.replaceChildren(...kids);
  }

  function renderSide() {
    const side = document.getElementById('side'), p = cur(), t = p.tiles.find(x => x.id === selId);
    if (!t) {
      side.replaceChildren(el('h3', {}, 'Come si usa'), el('p', {}, 'Tocca un riquadro dell\'anteprima per modificarlo, oppure un «+» per aggiungerne uno nuovo.'),
        el('p', {class: 'hint'}, 'La griglia è quella che vedrà il telefono. Righe e colonne si cambiano in alto.'));
      return;
    }
    const upd = (k, v) => { t[k] = v; changed(); render(); };
    t.icon = t.icon || {kind: 'auto', ref: ''};
    const parts = [el('h3', {}, 'Riquadro selezionato'),
      field('Cosa fa', select(TYPES, t.type, v => {
        t.type = v;
        if (v === 'clock') { t.color = '#FFFFFF'; delete t.label; }
        if (v === 'sos') { t.color = '#B71C1C'; t.label = 'SOS'; t.w = Math.min(2, p.cols); }
        if (['call', 'wa_video', 'sms'].includes(v) && t.icon.kind === 'symbol') t.icon = {kind: 'auto', ref: ''};
        changed(); render();
      }))];

    if (t.type === 'app') {
      const known = APPS.some(a => a[0] === t.target);
      const opts = Object.fromEntries(APPS.concat([['__other', 'Altra app (nome pacchetto)…']]));
      parts.push(field('Quale app', select(opts, known ? t.target : '__other', v => {
        if (v === '__other') { t.target = ''; } else { t.target = v; if (!t.label || Object.values(APP_LABEL).includes(t.label)) t.label = APP_LABEL[v] || t.label; }
        changed(); render();
      })));
      if (!known) parts.push(field('Nome pacchetto', el('input', {type: 'text', value: t.target || '', placeholder: 'es. it.ilmeteo', onchange: e => upd('target', e.target.value.trim())}),
        DEV.length ? '(app non installata sul telefono)' : '(si trova nell\'indirizzo della pagina Play Store dopo «id=»)'));
      if (!DEV.length) parts.push(el('p', {class: 'hint'}, 'L\'elenco delle app installate compare qui dopo che il telefono (versione 0.4.1 o successiva) ha premuto «Aggiorna ora».'));
    }
    if (['call', 'wa_video', 'sms'].includes(t.type)) {
      parts.push(field('Numero di telefono', el('input', {type: 'tel', value: t.target || '', placeholder: '+39 333 1234567', onchange: e => upd('target', e.target.value.replace(/[^\d+]/g, ''))})));
      if (t.type === 'wa_video') parts.push(el('p', {class: 'hint'}, 'Il numero deve essere in rubrica sul telefono, con WhatsApp attivo.'));
      if (t.type === 'sms') parts.push(field('Testo del messaggio', el('input', {type: 'text', value: t.body || '', onchange: e => upd('body', e.target.value)})));
    }
    if (t.type === 'url') parts.push(field('Indirizzo', el('input', {type: 'url', value: t.target || '', placeholder: 'https://', onchange: e => upd('target', e.target.value.trim())})));
    if (t.type === 'shortcut') parts.push(field('Scorciatoia', el('input', {type: 'text', value: t.target || '', placeholder: 'pacchetto/id', onchange: e => upd('target', e.target.value.trim())}), '(per esperti)'));
    if (t.type !== 'clock') parts.push(field('Scritta sul riquadro', el('input', {type: 'text', value: t.label || '', maxlength: '30', onchange: e => upd('label', e.target.value)})));

    if (!['clock', 'sos'].includes(t.type)) {
      parts.push(field('Icona', select(ICONS, t.icon.kind || 'auto', v => { t.icon = {kind: v, ref: v === 'symbol' ? (t.icon.ref && SYMBOLS[t.icon.ref] ? t.icon.ref : 'phone') : (v === 'photo' || v === 'auto' ? (t.icon.ref || '').startsWith('contact:') ? t.icon.ref : '' : '')}; changed(); render(); })));
      if (t.icon.kind === 'symbol') parts.push(field('Simbolo', select(SYMBOLS, t.icon.ref || 'phone', v => { t.icon.ref = v; changed(); render(); })));
      if (t.icon.kind === 'photo' || (t.icon.kind === 'auto' && ['call', 'wa_video', 'sms'].includes(t.type)))
        parts.push(el('p', {class: 'hint'}, 'La foto viene presa dalla rubrica del telefono in base al numero.'));
    }

    parts.push(el('label', {}, 'Colore'), el('div', {class: 'swatches'}, PALETTE.map(([hex, nm]) =>
      el('button', {type: 'button', class: 'sw', title: nm, 'aria-label': nm, 'aria-pressed': String((t.color || '').toUpperCase() === hex), style: 'background:' + hex, onclick: () => upd('color', hex)}))));

    const nums = n => Object.fromEntries(Array.from({length: n}, (_, i) => [String(i + 1), String(i + 1)]));
    parts.push(el('div', {class: 'row'},
      field('Larghezza', select(nums(p.cols), String(t.w || 1), v => upd('w', +v))),
      field('Altezza', select(nums(p.rows), String(t.h || 1), v => upd('h', +v))),
      field('Colonna', select(nums(p.cols), String(t.col + 1), v => upd('col', +v - 1))),
      field('Riga', select(nums(p.rows), String(t.row + 1), v => upd('row', +v - 1)))));
    const clash = p.tiles.find(o => o !== t && overlap(t, o));
    if (clash) parts.push(el('p', {class: 'issues'}, 'Si sovrappone a «' + name(clash) + '»: spostalo o rimpiccioliscilo.'));
    parts.push(el('p', {}, el('button', {type: 'button', class: 'danger', onclick: () => {
      const lastSos = t.type === 'sos' && cfg.pages.every(pp => pp.tiles.filter(x => x.type === 'sos' && x !== t).length === 0);
      if (!confirm(lastSos ? 'È l\'ultimo pulsante SOS: toglierlo comunque?' : 'Eliminare «' + name(t) + '»?')) return;
      p.tiles = p.tiles.filter(x => x !== t); selId = null; changed(); render();
    }}, 'Elimina riquadro')));
    side.replaceChildren(...parts);
  }

  // -------- SOS
  function renderSos() {
    const s = cfg.sos, box = document.getElementById('t-sos');
    s.numbers = s.numbers || [];
    const list = s.numbers.map((n, i) => el('div', {class: 'row', style: 'align-items:flex-end'},
      field(i === 0 ? 'Numero da chiamare' : 'Altro numero (riceve l\'SMS)', el('input', {type: 'tel', value: n, onchange: e => { s.numbers[i] = e.target.value.trim(); changed(); }})),
      el('div', {style: 'flex:0 0 auto'}, el('button', {type: 'button', class: 'small danger', onclick: () => { s.numbers.splice(i, 1); changed(); renderSos(); }}, 'Togli'))));
    box.replaceChildren(
      el('p', {}, 'Tenendo premuto il pulsante SOS, il telefono manda a tutti questi numeri un SMS con la posizione e poi chiama il primo.'),
      el('p', {class: 'hint'}, 'Usa numeri di familiari o vicini: le app non possono chiamare direttamente il 112.'),
      ...list,
      el('p', {}, el('button', {type: 'button', class: 'sec', disabled: s.numbers.length >= 5, onclick: () => { s.numbers.push(''); renderSos(); }}, '+ Aggiungi numero')),
      el('div', {class: 'row'},
        field('Secondi da tenere premuto', select({1: '1', 2: '2', 3: '3', 4: '4', 5: '5'}, String(s.holdSeconds || 3), v => { s.holdSeconds = +v; changed(); })),
        field('Secondi per annullare', select({3: '3', 5: '5', 8: '8', 10: '10'}, String(s.countdownSeconds || 5), v => { s.countdownSeconds = +v; changed(); }))),
      field('Testo dell\'SMS', el('input', {type: 'text', value: s.message || '', onchange: e => { s.message = e.target.value; changed(); }}), '(la posizione viene aggiunta in fondo)'));
  }

  // -------- promemoria
  function renderRem() {
    const box = document.getElementById('t-rem');
    const items = cfg.reminders.slice().sort((a, b) => (a.time || '').localeCompare(b.time || '')).map(r => {
      const set = (k, v) => { r[k] = v; changed(); };
      const all = !r.days || !r.days.length;
      return el('div', {class: 'rem'},
        el('div', {class: 'row'},
          field('Ora', el('input', {type: 'time', value: r.time || '08:00', onchange: e => { set('time', e.target.value); renderRem(); }})),
          field('Titolo', el('input', {type: 'text', value: r.title || '', onchange: e => set('title', e.target.value)})),
          field('Dettaglio', el('input', {type: 'text', value: r.detail || '', placeholder: 'es. 1 compressa di …', onchange: e => set('detail', e.target.value)}))),
        el('label', {}, 'Giorni'),
        el('div', {class: 'days'}, DAYS.map((d, i) => el('label', {}, el('input', {type: 'checkbox', checked: all || r.days.includes(i + 1), onchange: e => {
          let ds = all ? [1, 2, 3, 4, 5, 6, 7] : r.days.slice();
          ds = e.target.checked ? ds.concat(i + 1) : ds.filter(x => x !== i + 1);
          ds = [...new Set(ds)].sort();
          set('days', ds.length === 7 || !ds.length ? [] : ds);
        }}), d))),
        el('div', {class: 'row'},
          field('Se rinvia, riprova dopo', select({5: '5 min', 10: '10 min', 15: '15 min', 30: '30 min'}, String(r.snoozeMinutes || 10), v => set('snoozeMinutes', +v))),
          field('Se non risponde, risuona ogni', select({2: '2 min', 5: '5 min', 10: '10 min'}, String(r.repeatMinutes || 5), v => set('repeatMinutes', +v))),
          field('Per al massimo', select({0: 'nessuna ripetizione', 3: '3 volte', 6: '6 volte', 10: '10 volte'}, String(r.maxRepeats ?? 6), v => set('maxRepeats', +v)))),
        el('div', {class: 'row', style: 'align-items:center;margin-top:8px'},
          el('label', {style: 'margin:0;font-weight:400'}, el('input', {type: 'checkbox', checked: r.enabled !== false, onchange: e => set('enabled', e.target.checked)}), ' Attivo'),
          el('div', {style: 'flex:0 0 auto'}, el('button', {type: 'button', class: 'small danger', onclick: () => {
            if (!confirm('Eliminare il promemoria delle ' + r.time + '?')) return;
            cfg.reminders = cfg.reminders.filter(x => x !== r); changed(); renderRem();
          }}, 'Elimina'))));
    });
    box.replaceChildren(
      el('p', {}, 'All\'ora indicata il telefono suona e mostra a tutto schermo il pulsante «PRESO». Gli esiti compaiono in fondo a questa pagina.'),
      ...(items.length ? items : [el('p', {class: 'hint'}, 'Nessun promemoria.')]),
      el('p', {}, el('button', {type: 'button', class: 'sec', onclick: () => {
        cfg.reminders.push({id: uid('r'), time: '08:00', days: [], title: 'È ora della medicina', detail: '', snoozeMinutes: 10, repeatMinutes: 5, maxRepeats: 6, enabled: true});
        changed(); renderRem();
      }}, '+ Aggiungi promemoria')));
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
      check('ringerLock', 'Suoneria protetta', 'Se il telefono viene messo in silenzioso o il volume si abbassa, torna al livello scelto. Vale anche per le sveglie dei promemoria.'),
      p.ringerLock ? field('Volume minimo', select({30: '30%', 50: '50%', 70: '70%', 80: '80%', 90: '90%', 100: '100%'}, String(p.ringVolumePercent), v => set('ringVolumePercent', +v))) : '',
      field('Avviso batteria scarica', select({0: 'Mai', 10: 'Sotto il 10%', 15: 'Sotto il 15%', 20: 'Sotto il 20%', 25: 'Sotto il 25%', 30: 'Sotto il 30%'}, String(p.batteryAlertPercent), v => set('batteryAlertPercent', +v)),
        '(avviso in Home, SMS ai numeri SOS e messaggio qui se gli avvisi sono attivi)'),
      ...(document.getElementById('pubform').dataset.edition === 'standard'
        ? [el('p', {class: 'msg warn'}, 'Questo telefono ha l\'edizione standard: pallini con messaggi non letti e comando SMS «#AGGIORNA» funzionano solo con l\'edizione completa.')]
        : []),
      check('badges', 'Pallino sui riquadri', 'Mostra quante chiamate perse e quanti messaggi non letti ci sono sui riquadri delle app. Sul telefono va concesso l\'«Accesso alle notifiche».'),
      check('smsUpdateCommand', 'Comando SMS «#AGGIORNA»', 'Se uno dei numeri SOS manda al telefono l\'SMS «#AGGIORNA», il telefono applica subito l\'ultima configurazione pubblicata invece di aspettare il controllo (ogni 15 minuti).'));
  }

  function render() { renderPages(); renderGrid(); renderSide(); renderSos(); renderRem(); renderPhone(); issues(); }
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
