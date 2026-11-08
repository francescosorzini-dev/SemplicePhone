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
const TILE_TYPES = ['app', 'call', 'wa_video', 'tg_video', 'sms', 'call_me', 'torch', 'magnifier', 'where', 'music', 'news', 'url', 'shortcut', 'clock', 'sos', 'weather', 'photos', 'missed_calls', 'search', 'messages', 'radio'];
const MEDIA_MAX = 8 * 1024 * 1024;
// Versione dell'app che conosce meteo, foto e promemoria in una data: le precedenti li ignorano o scartano.
const APP_FEATURES_090 = '0.9.0';
// Versione che conosce il riquadro chiamate perse e il pulsante «Casa».
const APP_FEATURES_0100 = '0.10.0';
const EVENT_TYPES = ['sos', 'reminder_missed', 'battery', 'offline', 'online', 'test', 'call_blocked', 'location', 'ring_done', 'message_read', 'remote_app', 'scam_message', 'call_out_blocked', 'long_call', 'sim_changed', 'inactive', 'active_again', 'fall', 'zone_left', 'zone_back', 'heat', 'pills_low', 'call_me', 'voice_reply'];
/** Avvisi della 0.17 (sicurezza, truffe, cadute…) che si possono attivare o spegnere uno per uno. */
const NOTIFY_0170 = ['remote_app', 'scam_message', 'call_out_blocked', 'long_call', 'sim_changed', 'inactive', 'active_again', 'fall', 'zone_left', 'zone_back', 'heat', 'pills_low', 'call_me', 'voice_reply'];
// Versione che riceve messaggi, «Fai squillare» e la risposta automatica.
const APP_FEATURES_0130 = '0.13.0';
const MESSAGE_MAX = 300;
const VOICE_MAX = 5 << 20;
/** Videomessaggi: al massimo 80 MB, o meno se l'hosting accetta caricamenti più piccoli. */
function ini_bytes(string $v): int { $n = (int)$v; $u = strtolower(substr(trim($v), -1)); return $u === 'g' ? $n << 30 : ($u === 'm' ? $n << 20 : ($u === 'k' ? $n << 10 : $n)); }
function video_max(): int {
    $lim = [80 << 20];
    foreach (['upload_max_filesize', 'post_max_size'] as $k) if (($b = ini_bytes((string)ini_get($k))) > 0) $lim[] = $b - ($k === 'post_max_size' ? 65536 : 0);
    return max(0, min($lim));
}
const NEWS_FEEDS = [['ANSA', 'https://www.ansa.it/sito/notizie/topnews/topnews_rss.xml'], ['Rai News', 'https://www.rainews.it/rss/tutti'], ['Il Post', 'https://www.ilpost.it/feed/'],
    ['BBC News', 'https://feeds.bbci.co.uk/news/rss.xml'], ['Tagesschau', 'https://www.tagesschau.de/index~rss2.xml'], ['France Info', 'https://www.francetvinfo.fr/titres.rss'], ['RTVE', 'https://api2.rtve.es/rss/temas_noticias.xml']];
const VIDEO_TYPES = ['video/mp4', 'video/webm', 'video/3gpp', 'video/quicktime', 'video/x-matroska'];
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
        'notify' => ['sos' => true, 'reminder_missed' => true, 'battery' => true, 'offline' => true, 'call_blocked' => false, 'location' => true, 'ring_done' => true, 'message_read' => true,
            'remote_app' => true, 'scam_message' => true, 'call_out_blocked' => false, 'long_call' => true, 'sim_changed' => true, 'inactive' => true, 'active_again' => true,
            'fall' => true, 'zone_left' => true, 'zone_back' => true, 'heat' => true, 'pills_low' => true, 'call_me' => true, 'voice_reply' => true],
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
/*SYM-BEGIN*/ const SYM_UI_JSON = '["speaker","mic","folder","close","backspace","play","chevron","minus","plus","check","settings","chart","history","refresh","edit"]'; const SYM_JSON = '{"phone":["M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"],"message":["M6 4h12a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9l-5 4V6a2 2 0 0 1 2-2z"],"video":["M5 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z","M15 10.5l6-3.5v10l-6-3.5"],"photo":["M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z","M7 10a2 2 0 1 0 4 0a2 2 0 1 0-4 0","M21 16l-5-5-9 9"],"camera":["M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z","M8.5 13a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0"],"weather":["M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0","M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5L19 19M5 19l1.5-1.5M17.5 6.5L19 5"],"web":["M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0","M3 12h18","M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18"],"person":["M8 8a4 4 0 1 0 8 0a4 4 0 1 0-8 0","M4 21a8 8 0 0 1 16 0"],"alert":["M12 3.5l9 16.5H3z","M12 10v4.5","M12 17.5v.5"],"pill":["M5.1 12.9l7.8-7.8a4.2 4.2 0 0 1 6 6l-7.8 7.8a4.2 4.2 0 0 1-6-6z","M9 9l6 6"],"home":["M3 11l9-7 9 7","M5 10v10h14V10","M10 20v-6h4v6"],"heart":["M12 20s-8-4.9-8-11a4.5 4.5 0 0 1 8-2.8A4.5 4.5 0 0 1 20 9c0 6.1-8 11-8 11z"],"music":["M9 18V6l10-2v12","M5 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0","M15 16a2 2 0 1 0 4 0a2 2 0 1 0-4 0"],"radio":["M5 9h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1","M7 9L16 4","M13 14.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0","M7 13h3M7 16h3"],"grid":["M4 5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z","M14 5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1z","M4 15a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z","M14 15a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1z"],"shower":["M5.5 21V7a3 3 0 0 1 3-3h3a3 3 0 0 1 3 3","M10.5 11a4 4 0 0 1 8 0z","M12 14.5v1.5M17 14.5v1.5M14.5 14.5v1.5M13 19v1M16 19v1"],"cart":["M3 4h2l2.5 11h10L20 7H6","M9 19.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0","M16 19.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0"],"doctor":["M5 4v6a4 4 0 0 0 8 0V4","M4 4h2M12 4h2","M9 14v2a4 4 0 0 0 8 0v-1","M15 13a2 2 0 1 0 4 0a2 2 0 1 0-4 0"],"cards":["M5 9a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2z","M9 7V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-2"],"calendar":["M6 5h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z","M4 10h16","M8 3v4M16 3v4","M8 14h.01M12 14h.01M16 14h.01M8 17.5h.01M12 17.5h.01"],"walk":["M11.25 4.5a1.75 1.75 0 1 0 3.5 0a1.75 1.75 0 1 0-3.5 0","M12.5 8.5L11 14","M11 14l-2.5 3.5L7 21","M11 14l3 3 .5 4","M12.5 8.5L9.5 10l-1 3","M12.5 8.5l2 3 2.5 1"],"food":["M4.5 3v5a3.5 3.5 0 0 0 7 0V3","M8 3v18","M19.5 14V3a4 4 0 0 0-4 4v5a2 2 0 0 0 2 2h2z","M19.5 14v7"],"bed":["M3 5v14","M3 15h18v4","M21 15v-3a3 3 0 0 0-3-3h-7v6","M5.5 11.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0"],"water":["M6 4h12l-1.5 16h-9z","M7 9h10"],"cloud":["M7 18h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 18z"],"rain":["M7 14h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 14z","M8 17l-1 3M12 17l-1 3M16 17l-1 3"],"snow":["M7 14h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 14z","M8 18h.01M12 18h.01M16 18h.01M10 21h.01M14 21h.01"],"storm":["M7 14h10a4 4 0 0 0 0-8a5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 14z","M12 14l-2 4h4l-2 4"],"fog":["M4 8h16M6 12h12M4 16h16"],"missed":["M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2","M16 3l5 5M21 3l-5 5"],"battery":["M4.5 6h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-12a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z","M21.5 10v4","M12 9.5L9.5 12H13l-2.5 3"],"search":["M4 10a6 6 0 1 0 12 0a6 6 0 1 0-12 0","M14.5 14.5L20 20"],"torch":["M7 3h10v3l-3 4v11h-4V10L7 6z","M7 6h10","M12 13v2"],"magnifier":["M3 10a7 7 0 1 0 14 0a7 7 0 1 0-14 0","M15 15l6 6","M10 7v6M7 10h6"],"pin":["M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z","M9.5 10a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0"],"news":["M4 5h13v14H6a2 2 0 0 1-2-2z","M17 9h3v8a2 2 0 0 1-2 2","M7 8h7M7 11h7M7 14h4"],"callme":["M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2","M21 3l-6 6","M15 4v5h5"],"check":["M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0","M8 12.5l2.5 2.5 5.5-6"],"clock":["M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0","M12 7v5l3 2"],"car":["M6.5 10l1.5-4h8l1.5 4","M5 10h14a2 2 0 0 1 2 2v4a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-4a2 2 0 0 1 2-2z","M7 13.5h.01M17 13.5h.01","M5.5 17v2M18.5 17v2"],"bus":["M6 3h12a2 2 0 0 1 2 2v12H4V5a2 2 0 0 1 2-2z","M4 11h16","M7 17v3M17 17v3","M8 14h.01M16 14h.01"],"key":["M4 16a4 4 0 1 0 8 0a4 4 0 1 0-8 0","M10.8 13.2L20 4","M17 7l2.5 2.5M14.5 9.5l2 2"],"glasses":["M3 13.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0","M14 13.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0","M10 13a2 2 0 0 1 4 0","M3.5 12L5 6.5M20.5 12L19 6.5"],"paw":["M8.5 16.5c0-2.5 1.6-4.5 3.5-4.5s3.5 2 3.5 4.5c0 2-1.6 3-3.5 3s-3.5-1-3.5-3z","M4.5 11a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0","M8 6.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0","M13 6.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0","M16.5 11a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0"],"plant":["M7 14h10l-1.5 7h-7z","M12 14V8","M12 10C9 10 7 8 7 5c3 0 5 2 5 5z","M12 8c0-3 2-5 5-5 0 3-2 5-5 5z"],"tooth":["M7 3C4.5 3 3 5 3 7.5c0 3 1.5 4.5 2 7l1 5c.3 1.3 2 1.3 2.3 0l1.2-4.5c.3-1 2.7-1 3 0l1.2 4.5c.3 1.3 2 1.3 2.3 0l1-5c.5-2.5 2-4 2-7C21 5 19.5 3 17 3c-2 0-3 1-5 1S9 3 7 3z"],"cake":["M5 21v-7a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v7","M3 21h18","M12 13V9","M12 6.5c-.8-.8-.8-2 0-3 .8 1 .8 2.2 0 3z","M5 16.5c1.2 0 1.2 1 2.3 1s1.2-1 2.3-1 1.2 1 2.4 1 1.2-1 2.3-1 1.2 1 2.3 1 1.2-1 2.4-1"],"trash":["M4 7h16","M9 7V4h6v3","M6 7l1 13h10l1-13","M10 11v6M14 11v6"],"wallet":["M4 7a2 2 0 0 1 2-2h11v4","M4 7v11a2 2 0 0 0 2 2h14V9H6a2 2 0 0 1-2-2z","M16 14.5h.01"],"book":["M12 6.5C10 5 7 4.5 3 5v14c4-.5 7 0 9 1.5 2-1.5 5-2 9-1.5V5c-4-.5-7 0-9 1.5z","M12 6.5v14"],"tv":["M5 7h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z","M8 3l4 4 4-4"],"mail":["M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z","M3.5 7l8.5 6 8.5-6"],"thermometer":["M10 14.5V5a2 2 0 0 1 4 0v9.5a4 4 0 1 1-4 0z","M12 9v8"],"syringe":["M16 5l3 3-9 9-3-3z","M14 3l7 7","M8.5 15.5L3 21","M12.5 9.5l2 2"],"family":["M5 8.5a3 3 0 1 0 6 0a3 3 0 1 0-6 0","M2.5 20a5.5 5.5 0 0 1 11 0","M14 9.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0","M15 14.7a4.5 4.5 0 0 1 6.5 4v1.3"],"moon":["M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"],"speaker":["M4 9h3l5-4v14l-5-4H4z","M15.5 9a4 4 0 0 1 0 6","M18 6.5a7.5 7.5 0 0 1 0 11"],"mic":["M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0z","M5.5 11a6.5 6.5 0 0 0 13 0","M12 17.5V21","M9 21h6"],"folder":["M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"],"close":["M6 6l12 12M18 6L6 18"],"backspace":["M8 5h11a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8l-6-7z","M11 9l6 6M17 9l-6 6"],"play":["M6 4.5v15L19 12z"],"chevron":["M6 9l6 6 6-6"],"minus":["M5 12h14"],"plus":["M12 5v14M5 12h14"],"bell":["M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z","M10 20.5a2 2 0 0 0 4 0","M12 3v2"],"settings":["M4 7h9M17 7h3","M13 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0","M4 12h3M11 12h9","M7 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0","M4 17h11M19 17h1","M15 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0"],"chart":["M4 20h16","M6 20v-6M10 20V8M14 20v-9M18 20V5"],"history":["M4 12a8 8 0 1 0 2.3-5.6","M4 4v4h4","M12 8v4l3 2"],"refresh":["M20 12a8 8 0 1 1-2.3-5.6","M20 4v4h-4"],"edit":["M5 19l1-4L16 5l3 3L9 18z","M14 7l3 3"]}'; /*SYM-END*/
/** Simbolo dell'app come SVG in linea (stessi tracciati del telefono, da tools/symbols/gen.py). */
function sym_svg(string $name, string $cls = 'sym'): string {
    static $all = null;
    $all ??= json_decode(SYM_JSON, true) ?: [];
    $paths = $all[$name] ?? $all['grid'] ?? [];
    return '<svg class="' . h($cls) . '" viewBox="0 0 24 24" aria-hidden="true">' . implode('', array_map(fn($d) => '<path d="' . h($d) . '"/>', $paths)) . '</svg>';
}
/** Simbolo di ogni tipo di avviso, per le pagine del portale (email e Telegram restano con le emoji). */
const EV_SYM = ['sos' => 'alert', 'fall' => 'alert', 'reminder_missed' => 'pill', 'pills_low' => 'pill', 'battery' => 'battery', 'offline' => 'web', 'online' => 'check',
    'test' => 'bell', 'call_blocked' => 'phone', 'call_out_blocked' => 'phone', 'long_call' => 'phone', 'sim_changed' => 'phone', 'location' => 'pin', 'zone_left' => 'pin',
    'zone_back' => 'home', 'ring_done' => 'bell', 'message_read' => 'message', 'remote_app' => 'alert', 'scam_message' => 'alert', 'inactive' => 'moon',
    'active_again' => 'check', 'heat' => 'thermometer', 'call_me' => 'callme', 'voice_reply' => 'mic'];
/** Toglie le emoji in testa a un titolo (per le pagine, dove c'è il simbolo). */
function no_emoji(string $s): string { return preg_replace('/^[\p{So}\p{Sk}\x{FE0F}\x{200D}\x{2190}-\x{21FF}\x{2300}-\x{23FF}\s]+/u', '', $s) ?? $s; }
/** Titolo dell'avviso per le pagine: simbolo + testo senza emoji. */
function ev_title_html(string $type): string { return sym_svg(EV_SYM[$type] ?? 'bell') . ' ' . h(no_emoji(ev_title($type))); }
function ev_title(string $type): string { return in_array($type, EVENT_TYPES, true) ? t('ev_' . $type) : $type; }
/**
 * Testo di un avviso nella lingua del portale. I telefoni dalla 0.17.2 mandano «key» (nome della
 * stringa dell'app, qui «evt_<key>») e «args»; un argomento {"key": …} è a sua volta da tradurre.
 * SOS e posizione si ricompongono dai dati. Telefoni più vecchi: il testo così come è arrivato.
 */
function ev_text(array $ev): string {
    $tr = fn(string $k) => lang_table($GLOBALS['LANG'] ?? 'en')[$k] ?? lang_table('en')[$k] ?? null;
    $args = array_map(fn($a) => is_array($a) ? (isset($a['key']) && is_string($a['key']) ? t('evt_' . $a['key']) : '') : (string)$a, array_values((array)($ev['args'] ?? [])));
    if (isset($ev['pkey']) && is_string($ev['pkey'])) return t($ev['pkey'], ...$args);
    if (isset($ev['key']) && is_string($ev['key']) && $tr('evt_' . $ev['key']) !== null) return t('evt_' . $ev['key'], ...$args);
    $type = (string)($ev['type'] ?? '');
    if (($type === 'location' || $type === 'sos') && array_key_exists('fixAt', $ev)) {
        $lat = $ev['lat'] ?? null; $lon = $ev['lon'] ?? null;
        if (!is_numeric($lat) || !is_numeric($lon)) $loc = t('evt_sms_no_location');
        else {
            $time = is_numeric($ev['fixAt']) ? date('H:i', intdiv((int)$ev['fixAt'], 1000)) : '';
            $loc = t('evt_sms_location', sprintf('https://maps.google.com/?q=%.6f,%.6f', (float)$lat, (float)$lon))
                . (is_numeric($ev['acc'] ?? null) ? t('evt_sms_location_acc', (int)$ev['acc'], $time) : t('evt_sms_location_time', $time));
        }
        if ($type === 'location') return $loc;
        // Il messaggio predefinito («Ho bisogno di aiuto.») in qualunque lingua → nella lingua del portale.
        $msg = trim((string)($ev['msg'] ?? ''));
        foreach (langs() as $l) if ($msg !== '' && $msg === (lang_table($l)['evt_default_sos_message'] ?? null)) { $msg = t('evt_default_sos_message'); break; }
        return "SOS - $msg\n$loc";
    }
    return (string)($ev['text'] ?? '');
}
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
/**
 * Lingua degli avvisi di un telefono: quella scelta nel portale per quel telefono, altrimenti la
 * lingua dell'app sul telefono (dal rapporto), altrimenti quella del portale.
 */
function alert_lang(?string $id, ?array $meta = null): string {
    $m = $meta ?? ($id ? read_json(dev_path($id, 'meta.json'), []) : []);
    foreach ([$m['alertLang'] ?? '', $m['lang'] ?? '', settings()['lang'] ?? ''] as $l) if (is_string($l) && in_array($l, langs(), true)) return $l;
    return 'en';
}
/** Esegue [$f] con i testi nella lingua [$l] (t() legge $GLOBALS['LANG']). */
function in_lang(string $l, callable $f) {
    $old = $GLOBALS['LANG'] ?? 'en';
    $GLOBALS['LANG'] = $l;
    try { return $f(); } finally { $GLOBALS['LANG'] = $old; }
}
/** Avviso di un telefono: [$make] restituisce [titolo, testo], composti nella lingua degli avvisi di quel telefono. */
function device_alert(string $id, callable $make, ?array $meta = null): array {
    return in_lang(alert_lang($id, $meta), function () use ($make, $id) { [$title, $text] = $make(); return send_alert($title, $text, $id); });
}

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
            $hours = intdiv(time() - $seen, 3600);
            // Anche tra gli avvisi del telefono, così compare nel riepilogo e nello storico.
            append_jsonl(DATA . "/events/$id.jsonl", ['type' => 'offline', 'at' => time() * 1000, 'text' => t('offline_text', $hours), 'pkey' => 'offline_text', 'args' => [$hours], 'receivedAt' => time(), 'server' => true]);
            device_alert($id, fn() => [ev_title('offline'), t('offline_text', $hours)]);
        }
        if ($sendWeekly && $seen) device_alert($id, fn() => [t('weekly_title'), implode("\n", weekly_lines($id))]);
        calendar_sync($id);
    }
}

// ---------------------------------------------------------------- calendario di famiglia (ICS)
function http_get(string $url, int $max = 2097152): ?string {
    if (function_exists('curl_init')) {
        $c = curl_init($url);
        curl_setopt_array($c, [CURLOPT_RETURNTRANSFER => true, CURLOPT_FOLLOWLOCATION => true, CURLOPT_MAXREDIRS => 3, CURLOPT_TIMEOUT => 8,
            CURLOPT_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS, CURLOPT_USERAGENT => 'SemplicePhone-portal']);
        $res = curl_exec($c); $code = (int)curl_getinfo($c, CURLINFO_HTTP_CODE); curl_close($c);
        return is_string($res) && $code >= 200 && $code < 300 && strlen($res) <= $max ? $res : null;
    }
    $res = @file_get_contents($url, false, stream_context_create(['http' => ['timeout' => 8, 'user_agent' => 'SemplicePhone-portal']]), 0, $max + 1);
    return is_string($res) && strlen($res) <= $max ? $res : null;
}

/** Valore ICS (DTSTART, EXDATE…) → [DateTimeImmutable nel fuso del portale, tutto il giorno?]. */
function ics_time(string $val, array $params): ?array {
    $tz = new DateTimeZone(date_default_timezone_get());
    $val = trim(explode(',', $val)[0]);
    if (($params['VALUE'] ?? '') === 'DATE' || preg_match('/^\d{8}$/', $val)) {
        $d = DateTimeImmutable::createFromFormat('!Ymd', substr($val, 0, 8), $tz);
        return $d ? [$d, true] : null;
    }
    if (!preg_match('/^(\d{8}T\d{6})(Z?)$/', $val, $m)) return null;
    try { $src = $m[2] === 'Z' ? new DateTimeZone('UTC') : (isset($params['TZID']) ? new DateTimeZone(trim($params['TZID'], '"')) : $tz); }
    catch (Exception $e) { $src = $tz; }
    $d = DateTimeImmutable::createFromFormat('Ymd\THis', $m[1], $src);
    return $d ? [$d->setTimezone($tz), false] : null;
}

/** Eventi del file ICS che iniziano tra $from e $to: [uid, titolo, inizio, tutto il giorno]. Ripetizioni semplici comprese. */
function ics_events(string $text, DateTimeImmutable $from, DateTimeImmutable $to): array {
    $lines = preg_split('/\r?\n/', preg_replace('/\r?\n[ \t]/', '', $text));
    $out = []; $ev = null;
    foreach ($lines as $line) {
        if ($line === 'BEGIN:VEVENT') { $ev = ['ex' => []]; continue; }
        if ($line === 'END:VEVENT') { if ($ev) $out = array_merge($out, ics_expand($ev, $from, $to)); $ev = null; continue; }
        if ($ev === null || !preg_match('/^([A-Z-]+)((?:;[^:]*)?):(.*)$/', $line, $m)) continue;
        $params = [];
        foreach (array_filter(explode(';', $m[2])) as $pp) { [$k, $v] = array_pad(explode('=', $pp, 2), 2, ''); $params[strtoupper($k)] = $v; }
        $v = $m[3];
        switch ($m[1]) {
            case 'UID': $ev['uid'] = $v; break;
            case 'SUMMARY': $ev['title'] = trim(str_replace(['\\n', '\\N', '\\,', '\;', '\\\\'], [' ', ' ', ',', ';', '\\'], $v)); break;
            case 'DTSTART': $ev['start'] = ics_time($v, $params); break;
            case 'RRULE': $ev['rrule'] = $v; break;
            case 'STATUS': $ev['status'] = strtoupper($v); break;
            case 'EXDATE': foreach (explode(',', $v) as $x) if ($t = ics_time($x, $params)) $ev['ex'][] = $t[0]->format('Ymd'); break;
            case 'RECURRENCE-ID': $ev['override'] = true; break;
        }
    }
    return $out;
}

function ics_expand(array $ev, DateTimeImmutable $from, DateTimeImmutable $to): array {
    if (empty($ev['start']) || ($ev['status'] ?? '') === 'CANCELLED' || !empty($ev['override'])) return [];
    [$start, $allDay] = $ev['start'];
    $title = mb_substr($ev['title'] ?? '', 0, 60) ?: '…';
    $uid = (string)($ev['uid'] ?? md5($title . $start->format('c')));
    $one = fn($d) => ['uid' => $uid, 'title' => $title, 'start' => $d, 'allDay' => $allDay];
    if (empty($ev['rrule'])) return $start >= $from && $start <= $to ? [$one($start)] : [];
    $r = [];
    foreach (explode(';', $ev['rrule']) as $pp) { [$k, $v] = array_pad(explode('=', $pp, 2), 2, ''); $r[strtoupper($k)] = $v; }
    $step = ['DAILY' => 'day', 'WEEKLY' => 'week', 'MONTHLY' => 'month', 'YEARLY' => 'year'][$r['FREQ'] ?? ''] ?? null;
    if (!$step) return [];
    $int = max(1, (int)($r['INTERVAL'] ?? 1));
    $count = isset($r['COUNT']) ? (int)$r['COUNT'] : PHP_INT_MAX;
    $until = isset($r['UNTIL']) && ($u = ics_time($r['UNTIL'], [])) ? $u[0]->setTime(23, 59, 59) : null;
    $days = $step === 'week' && !empty($r['BYDAY'])
        ? array_values(array_filter(array_map(fn($d) => array_search(substr($d, -2), ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU']), explode(',', $r['BYDAY'])), fn($x) => $x !== false))
        : null;
    $out = []; $n = 0;
    for ($i = 0; $i < 3000 && $n < $count; $i++) {
        $base = $start->modify('+' . ($i * $int) . " $step");
        if ($step === 'month' && $base->format('d') !== $start->format('d')) continue;
        $cands = [$base];
        if ($days !== null) {
            $monday = $base->modify('-' . ((int)$base->format('N') - 1) . ' days');
            $cands = array_map(fn($d) => $monday->modify("+$d days"), $days);
            sort($cands);
        }
        foreach ($cands as $d) {
            if ($d < $start) continue;
            if (($until && $d > $until) || $d > $to || $n >= $count) break 2;
            $n++;
            if ($d >= $from && !in_array($d->format('Ymd'), $ev['ex'], true)) $out[] = $one($d);
        }
    }
    return $out;
}

/** Promemoria «cal-…» dagli appuntamenti dei prossimi 14 giorni: prima dell'ora e, se scelto, la sera prima. */
function calendar_reminders(array $events, int $lead, bool $eve): array {
    $now = new DateTimeImmutable();
    $out = [];
    $add = function (DateTimeImmutable $at, string $title, string $detail, string $key) use (&$out, $now) {
        if ($at <= $now) return;
        $out[] = ['id' => 'cal-' . substr(sha1($key), 0, 12), 'time' => $at->format('H:i'), 'date' => $at->format('Y-m-d'), 'days' => [],
            'title' => $title, 'detail' => $detail, 'icon' => 'calendar', 'snoozeMinutes' => 10, 'repeatMinutes' => 5, 'maxRepeats' => 2, 'enabled' => true];
    };
    usort($events, fn($a, $b) => $a['start'] <=> $b['start']);
    foreach (array_slice($events, 0, 25) as $e) {
        $k = $e['uid'] . '|' . $e['start']->format('c');
        if ($e['allDay']) {
            $add($e['start']->setTime(9, 0), $e['title'], t('cal_today'), "$k|day");
            if ($eve) $add($e['start']->modify('-1 day')->setTime(20, 0), t('cal_tomorrow', $e['title']), '', "$k|eve");
        } else {
            $hm = $e['start']->format('H:i');
            $add($e['start']->modify("-$lead minutes"), $e['title'], t('cal_at', $hm), "$k|lead");
            if ($eve) $add($e['start']->modify('-1 day')->setTime(20, 0), t('cal_tomorrow', $e['title']), t('cal_at', $hm), "$k|eve");
        }
    }
    $seen = [];
    return array_values(array_filter($out, function ($r) use (&$seen) { if (isset($seen[$r['id']])) return false; return $seen[$r['id']] = true; }));
}

/**
 * Scarica il calendario del telefono (al massimo ogni 3 ore, o subito con $force) e mette gli
 * appuntamenti tra i promemoria; pubblica una nuova versione solo se cambiano.
 */
function calendar_sync(string $id, bool $force = false): void {
    $meta = read_json(dev_path($id, 'meta.json'), []);
    $url = (string)($meta['calendarUrl'] ?? '');
    $curFile = dev_path($id, 'config.json');
    if (!is_file($curFile)) return;
    if ($url === '' && empty($meta['calendarHad'])) return;
    if (!$force && $url !== '' && time() - (int)($meta['calendarAt'] ?? 0) < 3 * 3600) return;
    $new = [];
    if ($url !== '') {
        $meta['calendarAt'] = time();
        $text = http_get(preg_replace('#^webcal://#i', 'https://', $url));
        if ($text === null || strpos($text, 'BEGIN:VCALENDAR') === false) {
            $meta['calendarError'] = time();
            write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
            return;
        }
        unset($meta['calendarError']);
        $events = ics_events($text, new DateTimeImmutable(), new DateTimeImmutable('+14 days'));
        $new = calendar_reminders($events, (int)($meta['calendarLead'] ?? 60), !empty($meta['calendarEve']));
        $meta['calendarCount'] = count($events);
    }
    $meta['calendarHad'] = $url !== '';
    write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
    $cfg = json_decode((string)file_get_contents($curFile), true);
    if (!is_array($cfg)) return;
    $old = array_values(array_filter((array)($cfg['reminders'] ?? []), fn($r) => strpos((string)($r['id'] ?? ''), 'cal-') === 0));
    $key = fn($l) => json_encode(array_map(fn($r) => [$r['id'] ?? '', $r['time'] ?? '', $r['date'] ?? '', $r['title'] ?? '', $r['detail'] ?? ''], $l));
    if ($key($old) === $key($new)) return;
    $cfg['reminders'] = array_merge(array_values(array_filter((array)($cfg['reminders'] ?? []), fn($r) => strpos((string)($r['id'] ?? ''), 'cal-') !== 0)), $new);
    write_atomic($curFile, json_encode($cfg, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
    publish($id, $cfg, null, ['source' => 'calendar', 'changes' => [t('chg_calendar', count($new))]]);
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

    // Risposta vocale del nonno a un messaggio (m4a, al massimo 5 MB): la si ascolta nel portale.
    if (isset($_GET['voice']) && $_SERVER['REQUEST_METHOD'] === 'POST') {
        write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
        $body = file_get_contents('php://input', false, null, 0, VOICE_MAX + 1);
        if ($body === false || strlen($body) < 200 || strlen($body) > VOICE_MAX || substr($body, 4, 4) !== 'ftyp') { http_response_code(400); exit('{"error":"voice"}'); }
        $dir = DATA . "/voice/$id"; if (!is_dir($dir)) mkdir($dir, 0700, true);
        $name = time() . '_' . preg_replace('/\D/', '', (string)$_GET['voice']) . '.m4a';
        write_atomic("$dir/$name", $body);
        // Si tengono le ultime 30 risposte.
        $all = glob("$dir/*.m4a") ?: []; sort($all);
        foreach (array_slice($all, 0, max(0, count($all) - 30)) as $old) @unlink($old);
        $ev = ['type' => 'voice_reply', 'text' => t('voice_reply_text'), 'pkey' => 'voice_reply_text', 'at' => (int)(microtime(true) * 1000), 'receivedAt' => time(), 'file' => $name];
        append_jsonl(DATA . "/events/$id.jsonl", $ev);
        if (!empty(settings()['notify']['voice_reply'])) device_alert($id, fn() => [ev_title('voice_reply'), t('voice_reply_text')]);
        http_response_code(204);
        exit;
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
        // Pastiglie rimaste per promemoria (contate dal telefono a ogni «Presa»).
        if (isset($r['stock']) && is_array($r['stock'])) $meta['stock'] = array_map('intval', array_slice($r['stock'], 0, 60, true));
        $r['receivedAt'] = time();
        append_jsonl(DATA . "/reports/$id.jsonl", $r);
        foreach (['battery', 'charging', 'appVersion', 'lastResult', 'badgesAccess', 'edition'] as $k) $meta[$k] = $r[$k] ?? null;
        // Lingua dell'app sul telefono: predefinita per gli avvisi di questo telefono.
        if (isset($r['lang']) && in_array($r['lang'], langs(), true)) $meta['lang'] = $r['lang'];
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
        if ($backOnline) device_alert($id, fn() => [ev_title('online'), t('back_online_text')]);
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
            if (isset($ev['lang']) && in_array($ev['lang'], langs(), true)) $meta['lang'] = $ev['lang'];
            append_jsonl(DATA . "/events/$id.jsonl", $ev);
            if ($type === 'location') unset($meta['locateRequest']);
            if ($type === 'ring_done') unset($meta['ringRequest']);
            if ($type === 'message_read' && isset($meta['message']) && (string)($ev['message'] ?? '') === (string)$meta['message']['id']) $meta['message']['readAt'] = time();
            if (in_array($type, EVENT_TYPES, true) && !empty($s['notify'][$type])) {
                device_alert($id, fn() => [ev_title($type), mb_substr(ev_text($ev), 0, 600)], $meta);
            }
        }
        $meta['eventKeys'] = array_slice($seenKeys, -100);
        write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
        if ($backOnline) device_alert($id, fn() => [ev_title('online'), t('back_online_text')]);
        http_response_code(204);
        exit;
    }

    write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
    if ($backOnline) device_alert($id, fn() => [ev_title('online'), t('back_online_text')]);
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

// ---------------------------------------------------------------- carattere
/**
 * Atkinson Hyperlegible (Braille Institute of America, SIL Open Font License 1.1, testo della licenza in
 * app/src/main/assets/licenses/AtkinsonHyperlegible-OFL.txt): lo stesso carattere dell'app, così testi e
 * anteprima del portale sembrano il telefono. File TTF originali, non modificati, serviti da ?font=regular|bold;
 * la licenza da ?font=license.
 */
function serve_font(string $w): void {
    // Testo della licenza OFL, che deve accompagnare il carattere: ?font=license
    if ($w === 'license') { header('Content-Type: text/plain; charset=utf-8'); echo base64_decode(FONT_LICENSE_B64); exit; }
    $data = $w === 'bold' ? FONT_BOLD_B64 : ($w === 'regular' ? FONT_REGULAR_B64 : '');
    if ($data === '') { http_response_code(404); exit; }
    header('Content-Type: font/ttf');
    header('Cache-Control: public, max-age=31536000, immutable');
    echo base64_decode($data);
    exit;
}
const FONT_REGULAR_B64 = 'AAEAAAASAQAABAAgRFNJRwAAAAEAANREAAAACEdERUYmIiVoAACtoAAAAPhHUE9TBhS8lAAArpgAACDKR1NVQs2+72cAAM9kAAAE3k9TLzKBnCDAAAABqAAAAGBjbWFwMF4gUgAAB8wAAAOOY3Z0IBXBCrQAABooAAAAbGZwZ21iLv99AAALXAAADgxnYXNwAAAAEAAArZgAAAAIZ2x5ZhpHbHEAAB14AACEOGhlYWQdEfmxAAABLAAAADZoaGVhCIkENgAAAWQAAAAkaG10eMbdPbEAAAIIAAAFwmxvY2GjLsNGAAAalAAAAuRtYXhwAssPEgAAAYgAAAAgbmFtZXEwlLkAAKGwAAAEpnBvc3S6e2pLAACmWAAAB0BwcmVwWrHfOwAAGWgAAAC9AAEAAAABAYk7pItRXw889QAPA+gAAAAA3G3PNQAAAADcsOWS/8b/BgUKA3gAAAAGAAIAAAAAAAAAAQAAA7b+3gAABTD/xv63BQoAAQAAAAAAAAAAAAAAAAAAAXAAAQAAAXEAWAAHAFQABAACACoAVwCNAAAAlQ4MAAIAAQAEAhYBkAAFAAACigK7AAAAjAKKArsAAAHfADEBAgAAAAAAAAAAAAAAAIAAAO8AACBLAAAAAAAAAABOT05FAMAADSXKA7b+3gAAA7YBIiAAAAMAAAAAAfACnAAAACAAAwIPADICWAAAAAAAAAEYAAABGAAAAogAPAGSACoCJQAiAj4AKgJmACYCRwA2AlYAPAH+AA8CZwA8AlYAPAJyAAoCawBMApAAMgKhAEwCNwBMAiQATALGADICsgBMAZ4AQgH+AA8CcwBMAhsATAM0AEwCsQBMAtcAMgJZAEwC8QAyAmoATAJUACgCLgAKArIATAJNAAoDSAAKAm8ACgJTAAoCXwAsAg4AJgI2AEYB9AAmAjQAJgIbACYBNwAPAjAAJgIiAEYBFgAtAP7/xgHqAEYA7wBGA0kARgIiAEYCKAAmAjYARgJAACYBVABGAdcAHwFEABQCGABEAbkAAAKOAAABzQAAAa0AAAHMACgBrQA1Aa0ANQGPADUA1gA1AP0ADwEHADUA6wA1AOsANQDrADUBjgA1AY4ANQGOADUCHQAoAh0AKAG2ADUCuAA1BTAAJgFhADUBYQA1AkoADwFzADUCMgA1AloANQMeADUBfgA1AWAAKAJDADUBfgA1AX8ANQEMADUCGgBGAfwAJgDrADUBIwBJAQAANQF9ADkCMgA1AzoANQMsADUDZAA1AikAKAJaADUCKQAoAggAKAMMADIAzQAmAM0AJgE7AEwBeQAPATsAIQIoADUBjAAKAQwANQEZAEwBLQAoAo8AJgJSACgDnwAmAsEAJgCgACgBLAAyASwAIwGxAEQCWgA1AM0AJgFwACYAzQAmAXkADwE/ACYA6QBMAT8AIgIrACgBGQBMAfQAJgJKAEYCRAA1AlMACgDpAEwCEgAfAakANQMeADUCCAAxAlUATAPmAAoBQQAZAcoANQJFADUCWgA1AkMANQJDADUCMQA1AksANQJaADUCVgAPAOsANQJGADUDBQA1Aq8AMgJuADUCRgA1AvcANQJJADUCTwAUAZ4ANQGzADUB4AApAyAAKQNQADUBwgA1Aj0AFADtAA8CKAAmAtcAMgJAAEwDagAmAjcAJgI2AEYCEAA1AloANQQcADIDnwAmAnIACgJyAAoCcgAKAnIACgJyAAoCcgAKAnIACgIOACYCDgAmAg4AJgIOACYCDgAmAg4AJgIOACYCkAAyAfQAJgI3AEwCNwBMAjcATAI3AEwCGwAmAhsAJgIbACYCGwAmAZ4AQgGeAEIBngAtAZ4ALwEWABoBFgAtARb/5AEW/+YCsQBMAtcAMgLXADIC1wAyAtcAMgLXADICsgBMArIATAKyAEwCsgBMAlMACgIiAEYCKAAmAigAJgIoACYCKAAmAigAJgIYAEQCGABEAhgARAIYAEQBrQAAAa0AAAJUACgB1wAfAlMACgJfACwBzAAoAsgAFAJ/ADgCfwBtAn8ATwJ/AFQCfwAzAn8AVgJ/AFECfwBZAn8ASAJ/AFECcgAKAg4AJgJyAAoCDgAmAjcATAIbACYCNwBMAhsAJgI3AEwCGwAmAjcATAIbACYC1wAyAigAJgGeAEIBFv/8AZ4AQgEWACICkAAyAfQAJgKQADIB9AAmAlQAKAHXAB8CsgBMAhgARAKyAEwCGABEArIATAIYAEQCsgBMAhgARAKxAEwCIgBGArEATAIiAEYCXwAsAcwAKAJfACwBzAAoAsYACgJFABQCxgAyAjAAJgLGADICMAAmAjAAJgIbAEwA7wA3Ai4ACgDvAEYCGwBMAUQAFAKxAEwCIgBGAmoATAFUAEYCagBMAVQAJQLIABQCWwAm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JUawF0NYUBtSWVggPFkusTABFCstsEQssDsriiAgPLAGI0KKOCMgLkawAiVGsBdDWFAbUllYIDxZLrEwARQrsAZDLrAwKy2wRSywABawBCWwBCYgICBGI0dhsAwjQi5HI0cjYbALQysjIDwgLiM4sTABFCstsEYssQoEJUKwABawBCWwBCUgLkcjRyNhILAGI0KxDABCsAtDKyCwYFBYILBAUVizBCAFIBuzBCYFGllCQiMgR7AGQ7ACYiCwAFBYsEBgWWawAWNgILABKyCKimEgsARDYGQjsAVDYWRQWLAEQ2EbsAVDYFmwAyWwAmIgsABQWLBAYFlmsAFjYbACJUZhOCMgPCM4GyEgIEYjR7ABKyNhOCFZsTABFCstsEcssQA6Ky6xMAEUKy2wSCyxADsrISMgIDywBiNCIzixMAEUK7AGQy6wMCstsEkssAAVIEewACNCsgABARUUEy6wNiotsEossAAVIEewACNCsgABARUUEy6wNiotsEsssQABFBOwNyotsEwssDkqLbBNLLAAFkUjIC4gRoojYTixMAEUKy2wTiywCiNCsE0rLbBPLLIAAEYrLbBQLLIAAUYrLbBRLLIBAEYrLbBSLLIBAUYrLbBTLLIAAEcrLbBULLIAAUcrLbBVLLIBAEcrLbBWLLIBAUcrLbBXLLMAAABDKy2wWCyzAAEAQystsFksswEAAEMrLbBaLLMBAQBDKy2wWyyzAAABQystsFwsswABAUMrLbBdLLMBAAFDKy2wXiyzAQEBQystsF8ssgAARSstsGAssgABRSstsGEssgEARSstsGIssgEBRSstsGMssgAASCstsGQssgABSCstsGUssgEASCstsGYssgEBSCstsGcsswAAAEQrLbBoLLMAAQBEKy2waSyzAQAARCstsGosswEBAEQrLbBrLLMAAAFEKy2wbCyzAAEBRCstsG0sswEAAUQrLbBuLLMBAQFEKy2wbyyxADwrLrEwARQrLbBwLLEAPCuwQCstsHEssQA8K7BBKy2wciywABaxADwrsEIrLbBzLLEBPCuwQCstsHQssQE8K7BBKy2wdSywABaxATwrsEIrLbB2LLEAPSsusTABFCstsHcssQA9K7BAKy2weCyxAD0rsEErLbB5LLEAPSuwQistsHossQE9K7BAKy2weyyxAT0rsEErLbB8LLEBPSuwQist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AF8ArAAIsQIBsKywNSsAAP//AAoAAAJoA08CJgAPAAABBwCSAGQAlgAIsQICsJawNSsAAP//AAoAAAJoA0YCJgAPAAABBwBHAL0APQAIsQICsD2wNSsAAP//AAoAAAJoA2ACJgAPAAAABwFfAL8AAP//AAoAAAJoA2ACJgAPAAAABwFNAP8AAP//AAoAAAJoAz0CJgAPAAABBwBbAHoAoQAIsQIBsKGwNSsAAP//ACb/9AHbApwCJgApAAAABgFjfQAAAP//ACb/9AHbAsMCJgApAAAABwFeAJsAAP//ACb/9AHbArcCJgApAAAABgFVZQAAAP//ACb/9AHbAqkCJgApAAAABgFvYgAAAP//ACb/9AHbArkCJgApAAAABgFYZwAAAP//ACb/9AHbAwkCJgApAAAABwFoAJoAAP//ACb/9AHbAsMCJgApAAAABwFMANgAAP//ADL/SwJqAqgCJgARAAAABwFUAQMAAP//ACb/SwHTAfgCJgArAAAABwFUALUAAP//AEwAAAIRA2ACJgATAAAABwFfALYAAP//AEwAAAIRA2ACJgATAAAABwFNAPYAAP//AEwAAAIRA2MCJgATAAAABwFWAI4AAP//AEwAAAIRA08CJgATAAABBwCSAFsAlgAIsQECsJawNSsAAP//ACb/9AICAsMCJgAtAAAABwFeAK0AAP//ACb/9AICAsMCJgAtAAAABwFMAOoAAP//ACb/9AICArcCJgAtAAAABgFVdwAAAP//ACb/9AICArkCJgAtAAAABgFYeQAAAP//AEIAAAFcA2ACJgAXAAAABgFfVQAAAP//AEIAAAFcA2ACJgAXAAAABwFNAJUAAP//AC0AAAFwA2MCJgAXAAAABgFWLQAAAP//AC8AAAFuA08CJgAXAAABBwCS//oAlgAIsQECsJawNSsAAP//ABoAAADGAsMCJgFiAAAABgFeGgAAAP//AC0AAAD5AsMCJgFiAAAABgFMVwAAAP///+QAAAEnArcCJgFiAAAABgFV5AAAAP///+YAAAElArkCJgFiAAAABgFY5gAAAP//AEwAAAJlA1UCJgAcAAABBwCrAJAArAAIsQEBsKywNSsAAP//ADL/9AKlA2ACJgAdAAAABwFfAPIAAP//ADL/9AKlA2ACJgAdAAAABwFNATIAAP//ADL/9AKlA2MCJgAdAAAABwFW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AAEECQAAAGIAAAADAAEECQABACoAYgADAAEECQACAA4AjAADAAEECQADAE4AmgADAAEECQAEADoA6AADAAEECQAFAEYBIgADAAEECQAGADgBaAADAAEECQAIACIBoAADAAEECQAJAHoBwgADAAEECQALAEICPAADAAEECQAMAC4CfgADAAEECQANASICrAADAAEECQAOADYDzgBDAG8AcAB5AHIAaQBnAGgAdAAgADIAMAAyADAAIABCAHIAYQBpAGwAbABlACAASQBuAHMAdABpAHQAdQB0AGUAIABvAGYAIABBAG0AZQByAGkAYwBhACwAIABJAG4AYwAuAEEAdABrAGkAbgBzAG8AbgAgAEgAeQBwAGUAcgBsAGUAZwBpAGIAbABlAFIAZQBnAHUAbABhAHIAMQAuADAAMAA2ADsATgBPAE4ARQA7AEEAdABrAGkAbgBzAG8AbgBIAHkAcABlAHIAbABlAGcAaQBiAGwAZQAtAFIAZQBnAHUAbABhAHIAQQB0AGsAaQBuAHMAbwBuACAASAB5AHAAZQByAGwAZQBnAGkAYgBsAGUAIABSAGUAZwB1AGwAYQByAFYAZQByAHMAaQBvAG4AIAAxAC4AMAAwADYAOwAgAHQAdABmAGEAdQB0AG8AaABpAG4AdAAgACgAdgAxAC4AOAAuADMAKQBBAHQAawBpAG4AcwBvAG4ASAB5AHAAZQByAGwAZQBnAGkAYgBsAGUALQBSAGUAZwB1AGwAYQByAEIAcgBhAGkAbABsAGUAIABJAG4AcwB0AGkAdAB1AHQAZQBFAGwAbABpAG8AdAB0ACAAUwBjAG8AdAB0ACwAIABNAGUAZwBhAG4AIABFAGkAcwB3AGUAcgB0AGgALAAgAEwAaQBuAHUAcwAgAEIAbwBtAGEAbgAsACAAVABoAGUAbwBkAG8AcgBlACAAUABlAHQAcgBvAHMAawB5AGgAdAB0AHAAcwA6AC8ALwB3AHcAdwAuAEIAcgBhAGkAbABsAGUASQBuAHMAdABpAHQAdQB0AGUALgBvAHIAZwAvAGgAdAB0AHAAOgAvAC8AaABlAGwAbABvAGEAcABwAGwAaQBlAGQALgBjAG8AbQBUAGgAaQBzACAARgBvAG4AdAAgAFMAbwBmAHQAdwBhAHIAZQAgAGkAcwAgAGwAaQBjAGUAbgBzAGUAZAAgAHUAbgBkAGUAcgAgAHQAaABlACAAUwBJAEwAIABPAHAAZQBuACAARgBvAG4AdAAgAEwAaQBjAGUAbgBzAGUALAAgAFYAZQByAHMAaQBvAG4AIAAxAC4AMQAuACAAVABoAGkAcwAgAGwAaQBjAGUAbgBzAGUAIABpAHMAIABhAHYAYQBpAGwAYQBiAGwAZQAgAHcAaQB0AGgAIABhACAARgBBAFEAIABhAHQAOgAgAGgAdAB0AHAAcwA6AC8ALwBzAGMAcgBpAHAAdABzAC4AcwBpAGwALgBvAHIAZwAvAE8ARgBMAGgAdAB0AHAAcwA6AC8ALwBzAGMAcgBpAHAAdABzAC4AcwBpAGwALgBvAHIAZwAvAE8ARgBMAAAAAgAAAAAAAP+yADkAAAAAAAAAAAAAAAAAAAAAAAAAAAFxAAABAgEDAAMBBAATABQAFQAWABcAGAAZABoAGwAcACQAJQAmACcAKAApACoAKwAsAC0ALgAvADAAMQAyADMANAA1ADYANwA4ADkAOgA7ADwAPQBEAEUARgBHAEgASQBKAEsATABNAE4ATwBQAFEAUgBTAFQAVQBWAFcAWABZAFoAWwBcAF0A2ADhANsA3ADdAOAAtgC3AMQAtAC1AMUAggDCAIcAqwDGAL4AvwC8AJ0AqQCkAIoA2gCDAJMBBQEGAI0BBwCIAMMA3gEIAJ4AqgD1APQA9gAfACAAIQAiACMAHQAeAD4APwBAAEEAQgBDAAQABQAGAAcACAAJAAoACwAMAA0ADgAPABAAEQASAF4AXwBgAGEAowCEAIUAvQCWAOgAhgCOAIsAogCJAJAApgCcAKcAjwCUAJUAuQCZAO8BCQEKAKUAkgELAQwAmAENAJoAmwDfANkAsgCzAIwBDgDiAOMAoQCRAO0AoADqAO4A8AC4ALAAsQDHAK4AYgBjAK0AyQEPARAAagBrAG0AbABuAGkAZABvAMsAZQDIAMoAcQBwAHIAcwDPAMwAzQDOAHUAdAB2AHcAZgDTANAA0QCvAGcA1gDUANUAaADrAHgAegB5AHsAfQB8AH8AfgCAAIEA7AC6AOQA5QC7AOYA5wDpAREBEgETARQBFQEWARcBGAEZARoBGwEcAR0BHgEfASABIQEiASMBJAElASYBJwEoASkBKgErASwA/QD+AS0BLgEvATABMQEyATMBNAE1ATYBNwE4ATkBOgE7ATwBPQE+AT8BQAFBAUIA+AD5AUMBRAFFAUYBRwFIAUkBSgFLAUwBTQFOAU8BUAFRAVIBAQFTAVQBVQFWAVcA/wEAAPsA/AFYAVkBWgFbAVwBXQFeAV8BYAFhAWIBYwFkAWUBZgFnAWgBaQFqAWsBbAFtAW4BbwFwANcBcQFyAXMBdAF1AXYBdwF4AXkBegF7AXwBfQF+BE5VTEwCQ1IHdW5pMDBBMAd1bmkwMEIyB3VuaTAwQjMHdW5pMDNCQwd1bmkwMEI5B3VuaTIyMTUHdW5pMjIxOQd1bmkwM0E5CWVzdGltYXRlZAd1bmkwMzk0B3VuaTIxMTMHQW1hY3JvbgdhbWFjcm9uB3plcm8udGYGb25lLnRmBnR3by50Zgh0aHJlZS50Zgdmb3VyLnRmB2ZpdmUudGYGc2l4LnRmCHNldmVuLnRmCGVpZ2h0LnRmB25pbmUudGYGQWJyZXZlBmFicmV2ZQdBb2dvbmVrB2FvZ29uZWsHRW1hY3JvbgdlbWFjcm9uCkVkb3RhY2NlbnQKZWRvdGFjY2VudAdFb2dvbmVrB2VvZ29uZWsGRWNhcm9uBmVjYXJvbg1PaHVuZ2FydW1sYXV0DW9odW5nYXJ1bWxhdXQHSW1hY3JvbgdpbWFjcm9uB0lvZ29uZWsHaW9nb25lawpDZG90YWNjZW50CmNkb3RhY2NlbnQGU2FjdXRlBnNhY3V0ZQdVbWFjcm9uB3VtYWNyb24FVXJpbmcFdXJpbmcNVWh1bmdhcnVtbGF1dA11aHVuZ2FydW1sYXV0B1VvZ29uZWsHdW9nb25lawZOYWN1dGUGbmFjdXRlBk5jYXJvbgZuY2Fyb24GWmFjdXRlBnphY3V0ZQpaZG90YWNjZW50Cnpkb3RhY2NlbnQESGJhcgRoYmFyCkdkb3RhY2NlbnQKZ2RvdGFjY2VudAd1bmkwMTIzB3VuaTAxM0IHdW5pMDEzQwZUY2Fyb24GbGNhcm9uBkxjYXJvbgZ0Y2Fyb24HdW5pMDE0NQd1bmkwMTQ2BlJhY3V0ZQZyYWN1dGUGUmNhcm9uBnJjYXJvbgZEY3JvYXQGRGNhcm9uBmRjYXJvbgd1bmkwMTM2B3VuaTAxMzcHdW5pMDEyMgd1bmkwMjE4B3VuaTAxNjIHdW5pMDIxQQlhY3V0ZWNvbWIOYWN1dGVjb21iLmNh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/64Aw//kAMT/5ADF/+QAxv/kAMf/5ADI/+QAyf/kAOb/7QD0/+0A9f/tAPb/7QED/64BBP/kAQX/rgEG/+QBGv/tAUj/7QFq/+0AEgAPABwAJgATACf/2gBCABMAvAAcAL0AHAC+ABwAvwAcAMAAHADBABwAwgAcAOb/2gD1/9oA9wATAQMAHAEFABwBKAATASoAEwBbAA//vQAR/+QAFf/kABj/2gAd/+QAH//kACIAJgAkABMAJQATACcAEwAp/9oAK//RACz/0QAt/9EAL//RADX/7QA2/+0AN//RADn/0QA7/+0Alv+iALL/0QC1/9oAtv/RALr/5AC7/9EAvP+9AL3/vQC+/70Av/+9AMD/vQDB/70Awv+9AMP/2gDE/9oAxf/aAMb/2gDH/9oAyP/aAMn/2gDK/+QAy//RAND/0QDR/9EA0v/RANP/0QDd/+QA3v/kAN//5ADg/+QA4f/kAOYAEwDn/+0A6P/RAOn/0QDq/9EA6//RAOz/0QD0/+0A9QATAQP/vQEE/9oBBf+9AQb/2gEI/9EBCv/RAQz/0QEO/9EBFf/kARb/0QEX/+QBGP/RARr/7QEk/+0BJv/tAS3/5AEu/9EBL//kATD/0QEx/9EBNAAmATn/7QE//9EBQf/RAUT/5AFF/+QBRv/RAUj/7QFKACYBSwAmAWr/7QBLAA//0QAR/+0AFf/tABj/5AAd/+0AH//tACIAHAAkABMAKf/kACv/6QAs/+kALf/pAC//5AA3/+kAOf/pALL/6QC1/+QAtv/pALr/7QC7/+kAvP/RAL3/0QC+/9EAv//RAMD/0QDB/9EAwv/RAMP/5ADE/+QAxf/kAMb/5ADH/+QAyP/kAMn/5ADK/+0Ay//pAND/6QDR/+kA0v/pANP/6QDd/+0A3v/tAN//7QDg/+0A4f/tAOj/6QDp/+kA6v/pAOv/6QDs/+kBA//RAQT/5AEF/9EBBv/kAQj/6QEK/+kBDP/pAQ7/6QEV/+0BFv/pARf/7QEY/+kBLf/tAS7/5AEv/+0BMP/kATH/5AE0ABwBP//pAUH/6QFE/+0BRf/tAUb/6QFKABwBSwAcAC0AEf/kABX/5AAd/+QAH//kACn/7QAv/+0APf/kAD7/7QBB/+0Atf/tALr/5ADD/+0AxP/tAMX/7QDG/+0Ax//tAMj/7QDJ/+0Ayv/kAN3/5ADe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AQYBBwEIAQkBCgELAQwBDQEOAREBEwEVARYBFwEYARoBHgEgASIBJAEmAScBKAEpASoBNAE1ATcBOQE6ATwBPgE/AUABQQFFAUYBSAFKAUsBagFsAW4AAgBbAA8ADwAEABEAEQALABIAEgADABMAEwACABcAFwAGABoAGgAWAB0AHQADACAAIAASACIAIgAQACMAIwAKACcAJwARACgAKAAPACsAKwAJAC0ALQABAC4ALgAVADkAOQAXADsAOwAHADwAPAAOAD0APQAFAD4APgANAEEAQQANAEIAQgAMAEoASgATAEsASwAIAE0ATQATAE4ATgAIAFIAUgAIAIMAgwAIAIUAhQAIAJYAlgACALAAsAAWALUAtQABALYAtgADALoAugACALsAuwABALwAwgAEAMoAygALAMsAywAJAMwAzwACANAA0wABANQA1wAGAN0A4QADAOIA5QAKAOYA5gARAO0A8AAFAPEA8gANAPQA9AAHAPUA9QARAPYA9gAPAPcA9wAMAPgA+AADAQMBAwAEAQUBBQAEAQcBBwACAQgBCAABAQkBCQACAQoBCgABAQsBCwACAQwBDAABAQ0BDQACAQ4BDgABAREBEQAGARMBEwAGARUBFQALARYBFgAJARcBFwALARgBGAAJARoBGgAHAR4BHgAFASABIAAFASIBIgAFAScBJwAPASgBKAAMASkBKQAPASoBKgAMATQBNAAQATUBNQAUATcBNwAOAToBOgASATwBPAASAT4BPgADAT8BPwAVAUABQAADAUEBQQAUAUUBRQALAUYBRgAJAUgBSAAHAUoBSwAQAWoBagAHAWwBbAAOAW4BbgAOAAIAUgAPAA8ABAARABEAAgAVABUAAgAdAB0AAgAfAB8AAgAiACIADQAnACcADgAoACgADwApACkAAwArAC0AAQAvAC8ADAAxADEABgA2ADYACAA3ADcAAQA5ADkAAQA7ADsABwA8ADwACwA9AD0ABQA+AD4ACgBBAEEACgBCAEIACQCyALIAAQC1ALUAAwC2ALYAAQC6ALoAAgC7ALsAAQC8AMIABADDAMkAAwDKAMoAAgDLAMsAAQDQANMAAQDYANsABgDdAOEAAgDmAOYADgDnAOcACADoAOwAAQDtAPAABQDxAPIACgD0APQABwD1APUADgD2APYADwD3APcACQEDAQMABAEEAQQAAwEFAQUABAEGAQYAAwEIAQgAAQEKAQoAAQEMAQwAAQEOAQ4AAQESARIABgEUARQABgEVARUAAgEWARYAAQEXARcAAgEYARgAAQEaARoABwEcARwABQEeAR4ABQEgASAABQEiASIABQEkASQACAEmASYACAEoASgACQEqASoACQEtAS0AAgEuAS4ADAEvAS8AAgEwATEADAE0ATQADQE3ATcACwE5ATkACAE/AT8AAQFBAUEAAQFEAUUAAgFGAUYAAQFIAUgABwFKAUsADQFiAWIABgFqAWoABwFsAWwACwFuAW4ACwAEAAAAAQAIAAEADAA0AAQAtgEwAAIABgFMAU8AAAFRAVsABAFeAWEADwFjAWQAEwFmAWkAFQFvAXAAGQACABUADwAPAAAAEQATAAEAFQAXAAQAGQAaAAcAHAAdAAkAIAAjAAsAJwApAA8AKwAtABIALwAwABUAMwA3ABcAOgA9ABwAQQBCACAAvAD4ACIBAwETAF8BFQEhAHABIwErAH0BLQE+AIYBQAFLAJgBYgFiAKQBagFqAKUBbAFuAKYAGwAAC0IAAAtIAAALTgAAC1QAAAtaAAEAbgAAC2AAAgqEAAALWgAAC2AAAgqKAAALZgAAC2wAAAtyAAALeAAAC34AAAuEAAALigAAC5AAAAuWAAAMPAADAHQAAwB0AAALnAAAC6IAAAuoAAALrgABAAACmwABAJ0AAACpBwYAAAcMBxIF7AAACXwAAAVKAAAJOgAABzwAAAdOB1QJagAACIwAAAgUAAAIIAAAB6IAAAeoB64JUgAABVAAAAjUCNoI4AAACOwAAAg+AAAFVgdsB3IHeAVcAAAJFgAACZoAAAfSAAAJpgAACLwAAAgUCBoIIAgmCZoAAAfSAAAHPAAACGIAAAcYAAAHHgckBfgAAAmIAAAJQAlGCUwAAAdCAAAJiAdgCJ4AAAiYAAAFYgAAB64AAAleAAAFaAAACMIIyAjOAAAFbgAABXQAAAj4AAAISgAABXoHhAeKB5AFgAAACSIAAAnEAAAIbgAACuAJ1gjmAAAFhggCCAgIDgWMAAAG3AAABZIAAAhuAAAFmAAABwwHEgWeAAAHDAcSBaQAAAcMBxIFqgAABwwHEgWwAAAHDAcSBbYAAAcMBxIFvAAABwwHEgXCAAAHHgckBcgAAAceByQFzgAABx4HJAXUAAAHHgckBdoAAAceByQF4AAABx4HJAXmAAAHHgckBewAAAXyAAAF+AAABf4AAAYEAAAHTgdUCFAAAAdOB1QGCgAAB04HVAYQAAAHTgdUBtAAAAmIB2AGFgAACYgHYAYcAAAJiAdgBiIAAAmIB2AGKAAAB6gHrgYuAAAHqAeuBjQAAAeoB64GOgAAB6gHrgZAAAAJuAm+BkYAAAm4Cb4GTAAACbgJvgZSAAAJuAm+BlgAAAg+AAAGXgdsB3IHeAZkB2wHcgd4BmoHbAdyB3gGcAdsB3IHeAZ2B2wHcgd4BnwIGgggCCYGgggaCCAIJgaICBoIIAgmBo4IGgggCCYHzAAAB9IAAAaUAAAISgAABpoHhAeKB5AGoAeEB4oHkAamB4QHigeQBqwHhAeKB5AGsgeEB4oHkAa4CAIICAgOBr4IAggICA4GxAgCCAgIDgbKCAIICAgOBtAAAAbcAAAG1gAABtwAAAbiAAAH0gAABugAAAhuAAAG7gAAB9IAAAdIAAAIYgAABvQAAAhuAAAJKAAACS4AAAb6AAAHDAcSBwAAAAceByQHBgAABwwHEgcYAAAHHgckByoAAAdOB1QHMAAACYgHYAhcAAAHTgdUBzYAAAmIB2AHPAAAB04HVAdCAAAJiAdgB0gAAAdOB1QHWgAACYgHYAdmB2wHcgd4B34HhAeKB5AHlgAAB6gHrgecAAAJuAm+B6IAAAeoB64HtAAACXwAAAe6AAAJiAAAB8AAAAl8AAAHxgAACYgAAAfMAAAH0gAAB9gAAAhuAAAH3ggaCCAIJgfkCAIICAgOB+oIGgggCCYH8AgCCAgIDgf2CBoIIAgmB/wIAggICA4IFAgaCCAIJggsAAAIPgAACDIAAAhKAAAIOAAACD4AAAhEAAAISgAACFAAAAhiAAAIVgAACG4AAAhcAAAIYgAACGgAAAhuAAAIdAAACIwAAAh6AAAIjAAACIAAAAiYAAAIhgAACIwAAAiSAAAImAAACJ4AAAikAAAI1AjaCKoAAAjCCMgIsAAACLYAAAi8AAAIwgjICM4AAAjUCNoI4AAACuAJ1gjmAAAI7AAACPIAAAj4AAAI/gAACQQAAAkWAAAJCgAACSIAAAkQAAAJFgAACRwAAAkiAAAJKAAACS4AAAk0AAAJOgAACUAJRglMAAAJUgAACVgAAAleAAAJZAAACWoAAAlw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';
const FONT_LICENSE_B64 = 'Q29weXJpZ2h0IDIwMjAgQnJhaWxsZSBJbnN0aXR1dGUgb2YgQW1lcmljYSwgSW5jLgoKVGhpcyBGb250IFNvZnR3YXJlIGlzIGxpY2Vuc2VkIHVuZGVyIHRoZSBTSUwgT3BlbiBGb250IExpY2Vuc2UsIFZlcnNpb24gMS4xLgpUaGlzIGxpY2Vuc2UgaXMgY29waWVkIGJlbG93LCBhbmQgaXMgYWxzbyBhdmFpbGFibGUgd2l0aCBhIEZBUSBhdDoKaHR0cDovL3NjcmlwdHMuc2lsLm9yZy9PRkwKCi0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tClNJTCBPUEVOIEZPTlQgTElDRU5TRSBWZXJzaW9uIDEuMSAtIDI2IEZlYnJ1YXJ5IDIwMDcKLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0KClBSRUFNQkxFClRoZSBnb2FscyBvZiB0aGUgT3BlbiBGb250IExpY2Vuc2UgKE9GTCkgYXJlIHRvIHN0aW11bGF0ZSB3b3JsZHdpZGUKZGV2ZWxvcG1lbnQgb2YgY29sbGFib3JhdGl2ZSBmb250IHByb2plY3RzLCB0byBzdXBwb3J0IHRoZSBmb250IGNyZWF0aW9uCmVmZm9ydHMgb2YgYWNhZGVtaWMgYW5kIGxpbmd1aXN0aWMgY29tbXVuaXRpZXMsIGFuZCB0byBwcm92aWRlIGEgZnJlZSBhbmQKb3BlbiBmcmFtZXdvcmsgaW4gd2hpY2ggZm9udHMgbWF5IGJlIHNoYXJlZCBhbmQgaW1wcm92ZWQgaW4gcGFydG5lcnNoaXAKd2l0aCBvdGhlcnMuCgpUaGUgT0ZMIGFsbG93cyB0aGUgbGljZW5zZWQgZm9udHMgdG8gYmUgdXNlZCwgc3R1ZGllZCwgbW9kaWZpZWQgYW5kCnJlZGlzdHJpYnV0ZWQgZnJlZWx5IGFzIGxvbmcgYXMgdGhleSBhcmUgbm90IHNvbGQgYnkgdGhlbXNlbHZlcy4gVGhlCmZvbnRzLCBpbmNsdWRpbmcgYW55IGRlcml2YXRpdmUgd29ya3MsIGNhbiBiZSBidW5kbGVkLCBlbWJlZGRlZCwgCnJlZGlzdHJpYnV0ZWQgYW5kL29yIHNvbGQgd2l0aCBhbnkgc29mdHdhcmUgcHJvdmlkZWQgdGhhdCBhbnkgcmVzZXJ2ZWQKbmFtZXMgYXJlIG5vdCB1c2VkIGJ5IGRlcml2YXRpdmUgd29ya3MuIFRoZSBmb250cyBhbmQgZGVyaXZhdGl2ZXMsCmhvd2V2ZXIsIGNhbm5vdCBiZSByZWxlYXNlZCB1bmRlciBhbnkgb3RoZXIgdHlwZSBvZiBsaWNlbnNlLiBUaGUKcmVxdWlyZW1lbnQgZm9yIGZvbnRzIHRvIHJlbWFpbiB1bmRlciB0aGlzIGxpY2Vuc2UgZG9lcyBub3QgYXBwbHkKdG8gYW55IGRvY3VtZW50IGNyZWF0ZWQgdXNpbmcgdGhlIGZvbnRzIG9yIHRoZWlyIGRlcml2YXRpdmVzLgoKREVGSU5JVElPTlMKIkZvbnQgU29mdHdhcmUiIHJlZmVycyB0byB0aGUgc2V0IG9mIGZpbGVzIHJlbGVhc2VkIGJ5IHRoZSBDb3B5cmlnaHQKSG9sZGVyKHMpIHVuZGVyIHRoaXMgbGljZW5zZSBhbmQgY2xlYXJseSBtYXJrZWQgYXMgc3VjaC4gVGhpcyBtYXkKaW5jbHVkZSBzb3VyY2UgZmlsZXMsIGJ1aWxkIHNjcmlwdHMgYW5kIGRvY3VtZW50YXRpb24uCgoiUmVzZXJ2ZWQgRm9udCBOYW1lIiByZWZlcnMgdG8gYW55IG5hbWVzIHNwZWNpZmllZCBhcyBzdWNoIGFmdGVyIHRoZQpjb3B5cmlnaHQgc3RhdGVtZW50KHMpLgoKIk9yaWdpbmFsIFZlcnNpb24iIHJlZmVycyB0byB0aGUgY29sbGVjdGlvbiBvZiBGb250IFNvZnR3YXJlIGNvbXBvbmVudHMgYXMKZGlzdHJpYnV0ZWQgYnkgdGhlIENvcHlyaWdodCBIb2xkZXIocykuCgoiTW9kaWZpZWQgVmVyc2lvbiIgcmVmZXJzIHRvIGFueSBkZXJpdmF0aXZlIG1hZGUgYnkgYWRkaW5nIHRvLCBkZWxldGluZywKb3Igc3Vic3RpdHV0aW5nIC0tIGluIHBhcnQgb3IgaW4gd2hvbGUgLS0gYW55IG9mIHRoZSBjb21wb25lbnRzIG9mIHRoZQpPcmlnaW5hbCBWZXJzaW9uLCBieSBjaGFuZ2luZyBmb3JtYXRzIG9yIGJ5IHBvcnRpbmcgdGhlIEZvbnQgU29mdHdhcmUgdG8gYQpuZXcgZW52aXJvbm1lbnQuCgoiQXV0aG9yIiByZWZlcnMgdG8gYW55IGRlc2lnbmVyLCBlbmdpbmVlciwgcHJvZ3JhbW1lciwgdGVjaG5pY2FsCndyaXRlciBvciBvdGhlciBwZXJzb24gd2hvIGNvbnRyaWJ1dGVkIHRvIHRoZSBGb250IFNvZnR3YXJlLgoKUEVSTUlTU0lPTiAmIENPTkRJVElPTlMKUGVybWlzc2lvbiBpcyBoZXJlYnkgZ3JhbnRlZCwgZnJlZSBvZiBjaGFyZ2UsIHRvIGFueSBwZXJzb24gb2J0YWluaW5nCmEgY29weSBvZiB0aGUgRm9udCBTb2Z0d2FyZSwgdG8gdXNlLCBzdHVkeSwgY29weSwgbWVyZ2UsIGVtYmVkLCBtb2RpZnksCnJlZGlzdHJpYnV0ZSwgYW5kIHNlbGwgbW9kaWZpZWQgYW5kIHVubW9kaWZpZWQgY29waWVzIG9mIHRoZSBGb250ClNvZnR3YXJlLCBzdWJqZWN0IHRvIHRoZSBmb2xsb3dpbmcgY29uZGl0aW9uczoKCjEpIE5laXRoZXIgdGhlIEZvbnQgU29mdHdhcmUgbm9yIGFueSBvZiBpdHMgaW5kaXZpZHVhbCBjb21wb25lbnRzLAppbiBPcmlnaW5hbCBvciBNb2RpZmllZCBWZXJzaW9ucywgbWF5IGJlIHNvbGQgYnkgaXRzZWxmLgoKMikgT3JpZ2luYWwgb3IgTW9kaWZpZWQgVmVyc2lvbnMgb2YgdGhlIEZvbnQgU29mdHdhcmUgbWF5IGJlIGJ1bmRsZWQsCnJlZGlzdHJpYnV0ZWQgYW5kL29yIHNvbGQgd2l0aCBhbnkgc29mdHdhcmUsIHByb3ZpZGVkIHRoYXQgZWFjaCBjb3B5CmNvbnRhaW5zIHRoZSBhYm92ZSBjb3B5cmlnaHQgbm90aWNlIGFuZCB0aGlzIGxpY2Vuc2UuIFRoZXNlIGNhbiBiZQppbmNsdWRlZCBlaXRoZXIgYXMgc3RhbmQtYWxvbmUgdGV4dCBmaWxlcywgaHVtYW4tcmVhZGFibGUgaGVhZGVycyBvcgppbiB0aGUgYXBwcm9wcmlhdGUgbWFjaGluZS1yZWFkYWJsZSBtZXRhZGF0YSBmaWVsZHMgd2l0aGluIHRleHQgb3IKYmluYXJ5IGZpbGVzIGFzIGxvbmcgYXMgdGhvc2UgZmllbGRzIGNhbiBiZSBlYXNpbHkgdmlld2VkIGJ5IHRoZSB1c2VyLgoKMykgTm8gTW9kaWZpZWQgVmVyc2lvbiBvZiB0aGUgRm9udCBTb2Z0d2FyZSBtYXkgdXNlIHRoZSBSZXNlcnZlZCBGb250Ck5hbWUocykgdW5sZXNzIGV4cGxpY2l0IHdyaXR0ZW4gcGVybWlzc2lvbiBpcyBncmFudGVkIGJ5IHRoZSBjb3JyZXNwb25kaW5nCkNvcHlyaWdodCBIb2xkZXIuIFRoaXMgcmVzdHJpY3Rpb24gb25seSBhcHBsaWVzIHRvIHRoZSBwcmltYXJ5IGZvbnQgbmFtZSBhcwpwcmVzZW50ZWQgdG8gdGhlIHVzZXJzLgoKNCkgVGhlIG5hbWUocykgb2YgdGhlIENvcHlyaWdodCBIb2xkZXIocykgb3IgdGhlIEF1dGhvcihzKSBvZiB0aGUgRm9udApTb2Z0d2FyZSBzaGFsbCBub3QgYmUgdXNlZCB0byBwcm9tb3RlLCBlbmRvcnNlIG9yIGFkdmVydGlzZSBhbnkKTW9kaWZpZWQgVmVyc2lvbiwgZXhjZXB0IHRvIGFja25vd2xlZGdlIHRoZSBjb250cmlidXRpb24ocykgb2YgdGhlCkNvcHlyaWdodCBIb2xkZXIocykgYW5kIHRoZSBBdXRob3Iocykgb3Igd2l0aCB0aGVpciBleHBsaWNpdCB3cml0dGVuCnBlcm1pc3Npb24uCgo1KSBUaGUgRm9udCBTb2Z0d2FyZSwgbW9kaWZpZWQgb3IgdW5tb2RpZmllZCwgaW4gcGFydCBvciBpbiB3aG9sZSwKbXVzdCBiZSBkaXN0cmlidXRlZCBlbnRpcmVseSB1bmRlciB0aGlzIGxpY2Vuc2UsIGFuZCBtdXN0IG5vdCBiZQpkaXN0cmlidXRlZCB1bmRlciBhbnkgb3RoZXIgbGljZW5zZS4gVGhlIHJlcXVpcmVtZW50IGZvciBmb250cyB0bwpyZW1haW4gdW5kZXIgdGhpcyBsaWNlbnNlIGRvZXMgbm90IGFwcGx5IHRvIGFueSBkb2N1bWVudCBjcmVhdGVkCnVzaW5nIHRoZSBGb250IFNvZnR3YXJlLgoKVEVSTUlOQVRJT04KVGhpcyBsaWNlbnNlIGJlY29tZXMgbnVsbCBhbmQgdm9pZCBpZiBhbnkgb2YgdGhlIGFib3ZlIGNvbmRpdGlvbnMgYXJlCm5vdCBtZXQuCgpESVNDTEFJTUVSClRIRSBGT05UIFNPRlRXQVJFIElTIFBST1ZJREVEICJBUyBJUyIsIFdJVEhPVVQgV0FSUkFOVFkgT0YgQU5ZIEtJTkQsCkVYUFJFU1MgT1IgSU1QTElFRCwgSU5DTFVESU5HIEJVVCBOT1QgTElNSVRFRCBUTyBBTlkgV0FSUkFOVElFUyBPRgpNRVJDSEFOVEFCSUxJVFksIEZJVE5FU1MgRk9SIEEgUEFSVElDVUxBUiBQVVJQT1NFIEFORCBOT05JTkZSSU5HRU1FTlQKT0YgQ09QWVJJR0hULCBQQVRFTlQsIFRSQURFTUFSSywgT1IgT1RIRVIgUklHSFQuIElOIE5PIEVWRU5UIFNIQUxMIFRIRQpDT1BZUklHSFQgSE9MREVSIEJFIExJQUJMRSBGT1IgQU5ZIENMQUlNLCBEQU1BR0VTIE9SIE9USEVSIExJQUJJTElUWSwKSU5DTFVESU5HIEFOWSBHRU5FUkFMLCBTUEVDSUFMLCBJTkRJUkVDVCwgSU5DSURFTlRBTCwgT1IgQ09OU0VRVUVOVElBTApEQU1BR0VTLCBXSEVUSEVSIElOIEFOIEFDVElPTiBPRiBDT05UUkFDVCwgVE9SVCBPUiBPVEhFUldJU0UsIEFSSVNJTkcKRlJPTSwgT1VUIE9GIFRIRSBVU0UgT1IgSU5BQklMSVRZIFRPIFVTRSBUSEUgRk9OVCBTT0ZUV0FSRSBPUiBGUk9NCk9USEVSIERFQUxJTkdTIElOIFRIRSBGT05UIFNPRlRXQVJFLgo=';
const FONT_BOLD_B64 = 'AAEAAAASAQAABAAgRFNJRwAAAAEAANfQAAAACEdERUYmIiVoAACvOAAAAPhHUE9To3iHPAAAsDAAACK+R1NVQs2+72cAANLwAAAE3k9TLzKCyCDAAAABqAAAAGBjbWFwMF4gUgAAB8wAAAOOY3Z0IBZXCxoAABooAAAAbGZwZ21iLv99AAALXAAADgxnYXNwAAAAEAAArzAAAAAIZ2x5ZiskzcIAAB14AACF6GhlYWQdYvnWAAABLAAAADZoaGVhCNkEMgAAAWQAAAAkaG10ePFILJoAAAIIAAAFwmxvY2H+tR6AAAAalAAAAuRtYXhwAs4PEAAAAYgAAAAgbmFtZW1okl8AAKNgAAAEjnBvc3S6e2pLAACn8AAAB0BwcmVwWrHfOwAAGWgAAAC9AAEAAAABAYnWA5APXw889QAPA+gAAAAA3G3PNQAAAADcsOWS/7r/BQVmA54AAQAGAAIAAAAAAAAAAQAAA7b+3gAABX7/uv5lBWYAAQAAAAAAAAAAAAAAAAAAAXAAAQAAAXEAWAAHAFIABAACACoAVwCNAAAAmA4MAAIAAQAEAjYCvAAFAAACigK7AAAAjAKKArsAAAHfADEBAgAAAAAAAAAAAAAAAIAAAO8AACBLAAAAAAAAAABOT05FAKAADSXKA7b+3gAAA7YBIiAAAAMAAAAAAfACnAAAACAAAwIPADICWAAAAAAAAAFAAAABQAAAAowALQHAAC8CSgAiAl0AIQJyABcCZAApAngAMwIfAA8CcQAmAjEAJgKyAAUCfwAsAqEAIwKuACwCRAAsAiAALALPACMCpwAsAcoAOQIwAAUCkwAsAhkALANKACwCvAAsAvwAIwJyACwDDQAjAo4ALAJrABkCaQAPApcALAKQAAoDcwAKArsACgKvAAoCbQAgAikAGAJUADICCAAYAlQAGAI2ABgBbwAFAlUAGAI8ADIBOwAYAQP/ugI3ADIBOgAyA3YAMgI8ADICPQAYAlQAMgJZABgBdAAyAhYAFQF7AA0COgAyAg4AAALTAAACFQAAAgYAAAIEAB4B0wAlAdMAJQF1ADkAyQAeAScADwEnACUBFQApARUAKQEVACkB8gApAfIAKQHyACkCUgApAlIAKQG2ADUDIwApBX4AGAGUACkBlAApAnAADwGLADUCnQA1AmoANQMeADUBlQA1AZQAKAJqADUBlwA1AZ0ANQE6ADUCOgAyAgMAGAEtADUBQQBJASMANQGZADkCnQA1A1UANQNYADUDvwA1Aj0AKAJqADUCPQAoAmYAKALyACMA8wAYAPMAGAFNACwBugAPAU0AIQJNADUBrgAKAToANQEbACwBmgAoAw8AGAJrABkD0QAYAr4AGADeACgBbgAjAW4AIwGyADICagA1APMAGAF2ABgA8wAYAboADwFyABgA6gAsAXIAIgIVACgBGwAsAggAGAJpADICVAA1Aq8ACgDYACwCQgAVAcQAOwMeADUCZgAyAm0ALAP4AAUBeQAPAeYAKQIUACkCUgApAlIAKQJSACkCXwApAmkAKQJSACkCiwAPARUAKQLFACkC7QApApAAIwJcACkCVQApAssAKQJmACkCcgANAeoAJQG8ACUB/AApA0oAKQOnACkB+AApAkoADQFCAAUCPQAYAvsAIwJnACwDcwAYAk0AGAJUADICGwApAlIAKQQlACMDqQAYArIABQKyAAUCsgAFArIABQKyAAUCsgAFArIABQIpABgCKQAYAikAGAIpABgCKQAYAikAGAIpABgCoQAjAggAGAJEACwCRAAsAkQALAJEACwCNgAYAjYAGAI2ABgCNgAYAcoAOQHKADkBygAiAcoAOQEtABYBLQAYAS3/3AEt//oCvAAsAvwAIwL8ACMC/AAjAvwAIwL8ACMClwAsApcALAKXACwClwAsAq8ACgI8ADICPQAYAj0AGAI9ABgCPQAYAj0AGAI6ADICOgAyAjoAMgI6ADICBgAAAgYAAAJrABkCFgAVAq8ACgJtACACBAAeAwUADQJ4AB4CeABlAngAPAJ4ADgCeAAgAngANgJ4ADMCeABCAngAKQJ4AE0CsgAFAikAGAKyAAUCKQAYAkQALAI2ABgCRAAsAjYAGAJEACwCNgAYAkQALAI2ABgC/AAjAj0AGAHKADkBLQALAcoAOQE7ABgCoQAjAggAGAKhACMCCAAYAmsAGQIWABUClwAsAjoAMgKXACwCOgAyApcALAI6ADIClwAsAjsAMgK8ACwCPAAyArwALAI8ADICbQAgAgQAHgJtACACBAAeAuQADwJ1AA0CzwAjAlUAGALPACMCVQAYAlUAGAIZACwBOgAiAmkADwFKADICGQAsAXsADQK8ACwCPAAyAo4ALAF0ADICjgAsAXQAGAMFAA0CeAAY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JUawF0NYUBtSWVggPFkusTABFCstsEQssDsriiAgPLAGI0KKOCMgLkawAiVGsBdDWFAbUllYIDxZLrEwARQrsAZDLrAwKy2wRSywABawBCWwBCYgICBGI0dhsAwjQi5HI0cjYbALQysjIDwgLiM4sTABFCstsEYssQoEJUKwABawBCWwBCUgLkcjRyNhILAGI0KxDABCsAtDKyCwYFBYILBAUVizBCAFIBuzBCYFGllCQiMgR7AGQ7ACYiCwAFBYsEBgWWawAWNgILABKyCKimEgsARDYGQjsAVDYWRQWLAEQ2EbsAVDYFmwAyWwAmIgsABQWLBAYFlmsAFjYbACJUZhOCMgPCM4GyEgIEYjR7ABKyNhOCFZsTABFCstsEcssQA6Ky6xMAEUKy2wSCyxADsrISMgIDywBiNCIzixMAEUK7AGQy6wMCstsEkssAAVIEewACNCsgABARUUEy6wNiotsEossAAVIEewACNCsgABARUUEy6wNiotsEsssQABFBOwNyotsEwssDkqLbBNLLAAFkUjIC4gRoojYTixMAEUKy2wTiywCiNCsE0rLbBPLLIAAEYrLbBQLLIAAUYrLbBRLLIBAEYrLbBSLLIBAUYrLbBTLLIAAEcrLbBULLIAAUcrLbBVLLIBAEcrLbBWLLIBAUcrLbBXLLMAAABDKy2wWCyzAAEAQystsFksswEAAEMrLbBaLLMBAQBDKy2wWyyzAAABQystsFwsswABAUMrLbBdLLMBAAFDKy2wXiyzAQEBQystsF8ssgAARSstsGAssgABRSstsGEssgEARSstsGIssgEBRSstsGMssgAASCstsGQssgABSCstsGUssgEASCstsGYssgEBSCstsGcsswAAAEQrLbBoLLMAAQBEKy2waSyzAQAARCstsGosswEBAEQrLbBrLLMAAAFEKy2wbCyzAAEBRCstsG0sswEAAUQrLbBuLLMBAQFEKy2wbyyxADwrLrEwARQrLbBwLLEAPCuwQCstsHEssQA8K7BBKy2wciywABaxADwrsEIrLbBzLLEBPCuwQCstsHQssQE8K7BBKy2wdSywABaxATwrsEIrLbB2LLEAPSsusTABFCstsHcssQA9K7BAKy2weCyxAD0rsEErLbB5LLEAPSuwQistsHossQE9K7BAKy2weyyxAT0rsEErLbB8LLEBPSuwQist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CAFLBAYdKRMWJBgFRCUtAP//ACwAAAKQA3UCJgAcAAAABwFNAScAAP//ADIAAAIKAtcCJgA2AAAABwFMAO0AAP//ACwAAAKQA20CJgAcAAABBwBEAIMAnQAIsQEBsJ2wNSsAAP//ADIAAAIKAtACJgA2AAAABgFRbAAAAP//ACAAAAJNA3UCJgAoAAAABwFNAPEAAP//AB4AAAHwAtcCJgBCAAAABwFMAMgAAP//ACAAAAJNA2MCJgAoAAABBwBGANIAlAAIsQEBsJSwNSsAAP//AB4AAAHwAs8CJgBCAAAABwFaAMUAAAACAA8AAALVApwAEwAXADtAOAUDAgELBgIACgEAZwAKAAgHCghnBAECAiJNDAkCBwcjB04AABcWFRQAEwATERERERERERERDQgfKzMRIzUzNTMVITUzFTMVIxEjESERESE1IUs8PJ4BE547O57+7QET/u0B8GJKSkpKYv4QARL+7gGdUwABAA0AAAJDAroAHAA7QDgLAQcFAUwDAQEEAQAFAQBnAAcHBWEABQUrTQACAgZfCQgCBgYjBk4AAAAcABwkEyMREREREQoIHiszESM1MzUzFTMVIxU2NjMyFhURIxE0JiYjIgYVFWteXph7exFPOWBHmAgfJTAsAitOQUFOfRw0a2X+0gEIIzojRUT///8AI//0AqsDUwImABUAAAEHAEUAqQCiAAixAQGworA1KwAA//8AGP81AiICsQImAC8AAAAHAU4AmQAA//8AI//0AqsDYwImABUAAAEHAEYBDQCUAAixAQGwlLA1KwAA//8AGP81AiICzwImAC8AAAAHAVoA4gAA//8AGP81AiIDBgImAC8AAAEPAVcBsgILwAAACbECAbgCC7A1KwAAAP//ACz/BQIKApwCJgAaAAAABwFXAIgAAP//ACL/BQE3ApwCJgA0AAAABgFXIgAAAP//AA8AAAJaA20CJgAiAAABBwBEAEsAnQAIsQEBsJ2wNSsAAP//ADIAAAFwArIAJgA0AAABBwFSANUAAgAIsQEBsAKwNSsAAP//ACwAAAIKArQCJgAaAAABBwFSATMABAAIsQEBsASwNSsAAP//AA0AAAGeAucCJgA8AAABBwFSAQMANwAIsQEBsDewNSsAAP//ACz/BQKQApwCJgAcAAAABwFXAL0AAP//ADL/BQIKAf4CJgA2AAAABwFX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AG8AaABpAG4AdAAgACgAdgAxAC4AOAAuADMAKQBBAHQAawBpAG4AcwBvAG4ASAB5AHAAZQByAGwAZQBnAGkAYgBsAGUALQBCAG8AbABkAEIAcgBhAGkAbABsAGUAIABJAG4AcwB0AGkAdAB1AHQAZQBFAGwAbABpAG8AdAB0ACAAUwBjAG8AdAB0ACwAIABNAGUAZwBhAG4AIABFAGkAcwB3AGUAcgB0AGgALAAgAEwAaQBuAHUAcwAgAEIAbwBtAGEAbgAsACAAVABoAGUAbwBkAG8AcgBlACAAUABlAHQAcgBvAHMAawB5AGgAdAB0AHAAcwA6AC8ALwB3AHcAdwAuAEIAcgBhAGkAbABsAGUASQBuAHMAdABpAHQAdQB0AGUALgBvAHIAZwAvAGgAdAB0AHAAOgAvAC8AaABlAGwAbABvAGEAcABwAGwAaQBlAGQALgBjAG8AbQBUAGgAaQBzACAARgBvAG4AdAAgAFMAbwBmAHQAdwBhAHIAZQAgAGkAcwAgAGwAaQBjAGUAbgBzAGUAZAAgAHUAbgBkAGUAcgAgAHQAaABlACAAUwBJAEwAIABPAHAAZQBuACAARgBvAG4AdAAgAEwAaQBjAGUAbgBzAGUALAAgAFYAZQByAHMAaQBvAG4AIAAxAC4AMQAuACAAVABoAGkAcwAgAGwAaQBjAGUAbgBzAGUAIABpAHMAIABhAHYAYQBpAGwAYQBiAGwAZQAgAHcAaQB0AGgAIABhACAARgBBAFEAIABhAHQAOgAgAGgAdAB0AHAAcwA6AC8ALwBzAGMAcgBpAHAAdABzAC4AcwBpAGwALgBvAHIAZwAvAE8ARgBMAGgAdAB0AHAAcwA6AC8ALwBzAGMAcgBpAHAAdABzAC4AcwBpAGwALgBvAHIAZwAvAE8ARgBMAAAAAgAAAAAAAP+yADkAAAAAAAAAAAAAAAAAAAAAAAAAAAFxAAABAgEDAAMBBAATABQAFQAWABcAGAAZABoAGwAcACQAJQAmACcAKAApACoAKwAsAC0ALgAvADAAMQAyADMANAA1ADYANwA4ADkAOgA7ADwAPQBEAEUARgBHAEgASQBKAEsATABNAE4ATwBQAFEAUgBTAFQAVQBWAFcAWABZAFoAWwBcAF0A2ADhANsA3ADdAOAAtgC3AMQAtAC1AMUAggDCAIcAqwDGAL4AvwC8AJ0AqQCkAIoA2gCDAJMBBQEGAI0BBwCIAMMA3gEIAJ4AqgD1APQA9gAfACAAIQAiACMAHQAeAD4APwBAAEEAQgBDAAQABQAGAAcACAAJAAoACwAMAA0ADgAPABAAEQASAF4AXwBgAGEAowCEAIUAvQCWAOgAhgCOAIsAogCJAJAApgCcAKcAjwCUAJUAuQCZAO8BCQEKAKUAkgELAQwAmAENAJoAmwDfANkAsgCzAIwBDgDiAOMAoQCRAO0AoADqAO4A8AC4ALAAsQDHAK4AYgBjAK0AyQEPARAAagBrAG0AbABuAGkAZABvAMsAZQDIAMoAcQBwAHIAcwDPAMwAzQDOAHUAdAB2AHcAZgDTANAA0QCvAGcA1gDUANUAaADrAHgAegB5AHsAfQB8AH8AfgCAAIEA7AC6AOQA5QC7AOYA5wDpAREBEgETARQBFQEWARcBGAEZARoBGwEcAR0BHgEfASABIQEiASMBJAElASYBJwEoASkBKgErASwA/QD+AS0BLgEvATABMQEyATMBNAE1ATYBNwE4ATkBOgE7ATwBPQE+AT8BQAFBAUIA+AD5AUMBRAFFAUYBRwFIAUkBSgFLAUwBTQFOAU8BUAFRAVIBAQFTAVQBVQFWAVcA/wEAAPsA/AFYAVkBWgFbAVwBXQFeAV8BYAFhAWIBYwFkAWUBZgFnAWgBaQFqAWsBbAFtAW4BbwFwANcBcQFyAXMBdAF1AXYBdwF4AXkBegF7AXwBfQF+BE5VTEwCQ1IHdW5pMDBBMAd1bmkwMEIyB3VuaTAwQjMHdW5pMDNCQwd1bmkwMEI5B3VuaTIyMTUHdW5pMjIxOQd1bmkwM0E5CWVzdGltYXRlZAd1bmkwMzk0B3VuaTIxMTMHQW1hY3JvbgdhbWFjcm9uB3plcm8udGYGb25lLnRmBnR3by50Zgh0aHJlZS50Zgdmb3VyLnRmB2ZpdmUudGYGc2l4LnRmCHNldmVuLnRmCGVpZ2h0LnRmB25pbmUudGYGQWJyZXZlBmFicmV2ZQdBb2dvbmVrB2FvZ29uZWsHRW1hY3JvbgdlbWFjcm9uCkVkb3RhY2NlbnQKZWRvdGFjY2VudAdFb2dvbmVrB2VvZ29uZWsGRWNhcm9uBmVjYXJvbg1PaHVuZ2FydW1sYXV0DW9odW5nYXJ1bWxhdXQHSW1hY3JvbgdpbWFjcm9uB0lvZ29uZWsHaW9nb25lawpDZG90YWNjZW50CmNkb3RhY2NlbnQGU2FjdXRlBnNhY3V0ZQdVbWFjcm9uB3VtYWNyb24FVXJpbmcFdXJpbmcNVWh1bmdhcnVtbGF1dA11aHVuZ2FydW1sYXV0B1VvZ29uZWsHdW9nb25lawZOYWN1dGUGbmFjdXRlBk5jYXJvbgZuY2Fyb24GWmFjdXRlBnphY3V0ZQpaZG90YWNjZW50Cnpkb3RhY2NlbnQESGJhcgRoYmFyCkdkb3RhY2NlbnQKZ2RvdGFjY2VudAd1bmkwMTIzB3VuaTAxM0IHdW5pMDEzQwZUY2Fyb24GbGNhcm9uBkxjYXJvbgZ0Y2Fyb24HdW5pMDE0NQd1bmkwMTQ2BlJhY3V0ZQZyYWN1dGUGUmNhcm9uBnJjYXJvbgZEY3JvYXQGRGNhcm9uBmRjYXJvbgd1bmkwMTM2B3VuaTAxMzcHdW5pMDEyMgd1bmkwMjE4B3VuaTAxNjIHdW5pMDIxQQlhY3V0ZWNvbWIOYWN1dGVjb21iLmNhc2UHdW5pMDMwNgx1bmkwMzA2LmNhc2UJY2Fyb24uYWx0B3VuaTAzMEMLdW5pMDMwQy5hbHQMdW5pMDMwQy5jYXNlB3VuaTAzMjcHdW5pMDMwMgx1bmkwMzAyLmNhc2UHdW5pMDMyNgd1bmkwMzA4DHVuaTAzMDguY2FzZQd1bmkwMzA3DHVuaTAzMDcuY2FzZQRFdXJvB3VuaTIwNzQJZ3JhdmVjb21iDmdyYXZlY29tYi5jYXNlB3VuaTAzMEIMdW5pMDMwQi5jYXNlB3VuaTAzMDQMdW5pMDMwNC5jYXNlB3VuaTAwQjUHdW5pMDMyOAx1bmkwMzI4LmNhc2UHdW5pMDMwQQx1bmkwMzBBLmNhc2UHdW5pMDIxOQd1bmkwMEFEB3RjZWRpbGwHdW5pMDE2Mwd1bmkwMjFCCXRpbGRlY29tYg50aWxkZWNvbWIuY2FzZQABAAH//wAPAAEAAgAOAAAAAAAAALQAAgAbAA8ADwABABEAEwABABUAFwABABkAGgABABwAHQABACAAIwABACcAKQABACsALQABAC8AMAABADMANwABADoAPQABAEEAQgABALwA+AABAQMBEwABARUBIQABASMBKwABAS0BPgABAUABSwABAUwBTwADAVEBWwADAV4BYQADAWIBYgABAWMBZAADAWYBaQADAWoBagABAWwBbgABAW8BcAADAAEAAgAAAAwAAAAUAAEAAgFUAVcAAQAWAUwBTQFOAU8BUQFTAVUBVgFYAVkBWgFbAV4BXwFgAWEBYwFkAWgBaQFvAXAAAQAAAAoALgBWAANERkxUABRncmVrABRsYXRuABQABAAAAAD//wADAAAAAQACAANrZXJuABRtYXJrABpta21rACAAAAABAAAAAAABAAEAAAACAAIAAwAEAAoVRCBeIKQAAgAIAAIACg0EAAEBFgAEAAAAhgIKAhACFgIkAioCNAJWAnAMTAJ+DJQMcALoA1YMiAN4A84MEAyUBNgFXgy6DMAFqAcWCFAMOgy0CZIMegyOCZgMtAmmCawKtgy0DLQLLAzmDPAMMAuqC7wMMAvyC/4L+AxwC/4L/gwQDHoMlAxwDHoMTAxMDEwMTAxMDEwMTAy0DLQMtAy0DLQMtAy0DLQMcAxwDHAMcAx6DHoMegx6DIgMiAyIDIgMKgyUDJQMlAyUDJQMOgy0DDAMMAy6DOYMOgyUDEwMtAxMDGYMcAx6DHAMegxwDHoMcAx6DIgMiAy0DLQMugzmDLQMtAzADKoM8Ay0DJQMjgyUDKoMtAy6DOYMugzADMAM5gzwDPAAAgAoAAYABwAAAAkAEAACABIAFQAKABcAGgAOAB0AHwASACEAIgAVACQAJwAXACsAMAAbADIANgAhADoAPAAmAD4AQQApAH8AfwAtAIQAhAAuAIYAhgAvAJYAlgAwAKwArQAxALAAsAAzALUAtgA0ALoAyQA2AMsA1wBGANsA2wBTAN0A4QBUAOYA5wBZAPEA9QBbAPgA+ABgAQMBDgBhAREBEQBtARMBEwBuARYBFgBvARgBGgBwASQBJABzASYBJgB0ATQBNQB1ATcBNwB3ATkBOQB4AT4BQQB5AUYBSwB9AWoBagCDAWwBbACEAW4BbgCFAAEAgAAQAAEACf/4AAMACQAUAA7/6gBc/7QAAQAO/+UAAgAG/+kAB//tAAgACf/bAAwAJAAN/9EAUwAfAHwAHwCE/8kArP/JAK3/yQAGAAb/5AAM/+0ADv/pAFP/5wBc/+QAfP/nAAMACf/pAAz/7QAN/+kAGgAP/+0AJP/tACb/7QAn/+QAKf/yALX/8gC8/+0Avf/tAL7/7QC//+0AwP/tAMH/7QDC/+0Aw//yAMT/8gDF//IAxv/yAMf/8gDI//IAyf/yAOb/5AD1/+QBA//tAQT/8gEF/+0BBv/yABsAD//aABj/4AAiABcAKf/qAJb/jgC1/+oAvP/aAL3/2gC+/9oAv//aAMD/2gDB/9oAwv/aAMP/6gDE/+oAxf/qAMb/6gDH/+oAyP/qAMn/6gED/9oBBP/qAQX/2gEG/+oBNAAXAUoAFwFLABcACAAi/+0AJP/kACf/0QDm/9EA9f/RATT/7QFK/+0BS//tABUAD//kACn/7wC1/+8AvP/kAL3/5AC+/+QAv//kAMD/5ADB/+QAwv/kAMP/7wDE/+8Axf/vAMb/7wDH/+8AyP/vAMn/7wED/+QBBP/vAQX/5AEG/+8AQgAR/9EAFf/RAB3/0QAf/9EAK//fACz/3wAt/98AMf/IADf/3wA5/98APf/VAD7/zgA//84AQf/OALL/3wC2/98Auv/RALv/3wDK/9EAy//fAND/3wDR/98A0v/fANP/3wDY/8gA2f/IANr/yADb/8gA3f/RAN7/0QDf/9EA4P/RAOH/0QDo/98A6f/fAOr/3wDr/98A7P/fAO3/1QDu/9UA7//VAPD/1QDx/84A8v/OAQj/3wEK/98BDP/fAQ7/3wES/8gBFP/IARX/0QEW/98BF//RARj/3wEc/9UBHv/VASD/1QEi/9UBLf/RAS//0QE//98BQf/fAUT/0QFF/9EBRv/fAWL/yAAhAA//rgAY/70AJv/jACf/7QAo/+0AKf/kADv/7QC1/+QAvP+uAL3/rgC+/64Av/+uAMD/rgDB/64Awv+uAMP/5ADE/+QAxf/kAMb/5ADH/+QAyP/kAMn/5ADm/+0A9P/tAPX/7QD2/+0BA/+uAQT/5AEF/64BBv/kARr/7QFI/+0Bav/tABIADwAcACYAEwAn/9oAQgATALwAHAC9ABwAvgAcAL8AHADAABwAwQAcAMIAHADm/9oA9f/aAPcAEwEDABwBBQAcASgAEwEqABMAWwAP/70AEf/kABX/5AAY/9oAHf/kAB//5AAiACYAJAATACUAEwAnABMAKf/aACv/0QAs/9EALf/RAC//0QA1/+0ANv/tADf/0QA5/9EAO//tAJb/ogCy/9EAtf/aALb/0QC6/+QAu//RALz/vQC9/70Avv+9AL//vQDA/70Awf+9AML/vQDD/9oAxP/aAMX/2gDG/9oAx//aAMj/2gDJ/9oAyv/kAMv/0QDQ/9EA0f/RANL/0QDT/9EA3f/kAN7/5ADf/+QA4P/kAOH/5ADmABMA5//tAOj/0QDp/9EA6v/RAOv/0QDs/9EA9P/tAPUAEwED/70BBP/aAQX/vQEG/9oBCP/RAQr/0QEM/9EBDv/RARX/5AEW/9EBF//kARj/0QEa/+0BJP/tASb/7QEt/+QBLv/RAS//5AEw/9EBMf/RATQAJgE5/+0BP//RAUH/0QFE/+QBRf/kAUb/0QFI/+0BSgAmAUsAJgFq/+0ATgAP/9EAEf/tABX/7QAY/+QAHf/tAB//7QAiABwAJAATACcADgAp/+QAK//pACz/6QAt/+kAL//kADf/6QA5/+kAsv/pALX/5AC2/+kAuv/tALv/6QC8/9EAvf/RAL7/0QC//9EAwP/RAMH/0QDC/9EAw//kAMT/5ADF/+QAxv/kAMf/5ADI/+QAyf/kAMr/7QDL/+kA0P/pANH/6QDS/+kA0//pAN3/7QDe/+0A3//tAOD/7QDh/+0A5gAOAOj/6QDp/+kA6v/pAOv/6QDs/+kA9QAOAQP/0QEE/+QBBf/RAQb/5AEI/+kBCv/pAQz/6QEO/+kBFf/tARb/6QEX/+0BGP/pAS3/7QEu/+QBL//tATD/5AEx/+QBNAAcAT//6QFB/+kBRP/tAUX/7QFG/+kBSgAcAUsAHABQABH/5AAV/+QAHf/kAB//5AAp/+0AK//uACz/7gAt/+4AL//tADH/6wA3/+4AOf/uAD3/5AA+/+0AQf/tALL/7gC1/+0Atv/uALr/5AC7/+4Aw//tAMT/7QDF/+0Axv/tAMf/7QDI/+0Ayf/tAMr/5ADL/+4A0P/uANH/7gDS/+4A0//uANj/6wDZ/+sA2v/rANv/6wDd/+QA3v/kAN//5ADg/+QA4f/kAOj/7gDp/+4A6v/uAOv/7gDs/+4A7f/kAO7/5ADv/+QA8P/kAPH/7QDy/+0BBP/tAQb/7QEI/+4BCv/uAQz/7gEO/+4BEv/rART/6wEV/+QBFv/uARf/5AEY/+4BHP/kAR7/5AEg/+QBIv/kAS3/5AEu/+0BL//kATD/7QEx/+0BP//uAUH/7gFE/+QBRf/kAUb/7gFi/+sAAQAyABEAAwAn/9EA5v/RAPX/0QABADIALABCAA8AHAAR/+0AFf/tAB3/7QAf/+0AK//jACz/4wAt/+MAMf/0ADf/4wA5/+MAPP/0ALL/4wC2/+MAuv/tALv/4wC8ABwAvQAcAL4AHAC/ABwAwAAcAMEAHADCABwAyv/tAMv/4wDQ/+MA0f/jANL/4wDT/+MA2P/0ANn/9ADa//QA2//0AN3/7QDe/+0A3//tAOD/7QDh/+0A6P/jAOn/4wDq/+MA6//jAOz/4wEDABwBBQAcAQj/4wEK/+MBDP/jAQ7/4wES//QBFP/0ARX/7QEW/+MBF//tARj/4wEt/+0BL//tATf/9AE//+MBQf/jAUT/7QFF/+0BRv/jAWL/9AFs//QBbv/0AB0AJgATACkAFgAx/+UAOAAMAEr/5wBLABkATf/nAE4AGQBSABkAgwAZAIUAGQCGAB0AtQAWAMMAFgDEABYAxQAWAMYAFgDHABYAyAAWAMkAFgDY/+UA2f/lANr/5QDb/+UBBAAWAQYAFgES/+UBFP/lAWL/5QAfACUAHAAp//MALgATADEADwA8ABAAPgAaAD8AGABAABUAQQAaALX/8wDD//MAxP/zAMX/8wDG//MAx//zAMj/8wDJ//MA2AAPANkADwDaAA8A2wAPAPEAGgDyABoBBP/zAQb/8wESAA8BFAAPATcAEAFiAA8BbAAQAW4AEAAEAD4AGgBBABoA8QAaAPIAGgANAA8AFAAn/+0AvAAUAL0AFAC+ABQAvwAUAMAAFADBABQAwgAUAOb/7QD1/+0BAwAUAQUAFAABAAwAHwABAIb/eAAEAAb/0QAH/+EACQAfAAz/yQAGABgAHAAk/74AJf/aACYAHAA//9EAQAAcAAEANAAUAAIAJv/tAC4AGwAEABj/xwAkABMAOP/MAED/7QAGACP/5AAk/6cAJf/RADT/6wA//9QAQAAUAAIAJf/kADIAOAACACQAEQAlABIAAwAk/9oAJf/kACb/7QABAC7/6wABACUAEwAFABf/5AAk/+QAJf/tACb/7QCW/7oAAgAzADcANQAfAAEAJf/kAAEAJf/tAAkAGP+YACMAEQAkACYAJQAcAC4AEwA1/74AOP/HADr/xwCW/4MAAgAYABUAJP/tAAIANAAcAD8ADAACAxAABAAABCAGRgAYABAAAP/4AAAAAAAAAAD/8QAAAAAAAAAAAAAAAP/H/74AAAAAAAAAAAAAAAAAAAAAAAAAAAAA//QAAAAA/77/tAAAAAD/7//tAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/5P/aAAAAAAAAAAD/7QAAAAAAAP/a/9H/7QAAAAD/2wAAABP/6AAAAAAAAAAA/9j/1gAA/77/vgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/uv/ZAAAAAAAA/+QAAAAAAAAAAAAAAAAAAAAA/+T/6wAAAAAAAAAAAAAAAAAAAAAAAP/0//UAAAAAAAAAAAAA/9r/yAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/+oAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP/R/7QAAAAAAAAAAAAA/+QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/7//tAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP/pAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/+0AAAAA//YAAP/t/8gAAAARAAAAAAAAAAAAAAAAAAAAAP/tAAAAAAAAAAAAGAAAAAAAAAAAAAAAAAAAAAAAAP/kAAAAAAAA/+QAAAAAAAAAAAAAAAAAAAAA/+T/5AAAAAAAAAAA/6r/2v/H/77/xwAA/9r/vgAAAAAAAP+9AB4AEwAAAAD/qv/R/7T/vv/a/93/xAAA/+0AAAAA/6sAEwAAAAAAAAAA/+sAAAAS/+0AAAAAAAAAAAAAAAD/7QAA//AAAAAAAAAAAAAAAAAAAAAA/+QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA0AAAAAAAAAAAAAP/rAAD/6QAAAAAAAAAAAAAAAAAAAAwAAAAcAAAAAAAAAAD/2wAMABwAAP/tAAAAAAAA/9oAAAAA/6D/owAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/0f/kAAAAAQCGAA8AEQASABMAFwAaAB0AIAAiACMAJwAoACsALQAuADAANQA2ADkAOwA8AD0APgBBAEIASgBLAE0ATgBSAIMAhQCWALAAtQC2ALoAuwC8AL0AvgC/AMAAwQDCAMMAxADFAMYAxwDIAMkAygDLAMwAzQDOAM8A0ADRANIA0wDUANUA1gDXAN0A3gDfAOAA4QDiAOMA5ADlAOYA5wDtAO4A7wDwAPEA8gD0APUA9gD3APgBAwEEAQUBBgEHAQgBCQEKAQsBDAENAQ4BEQETARUBFgEXARgBGgEeASABIgEkASYBJwEoASkBKgE0ATUBNwE5AToBPAE+AT8BQAFBAUUBRgFIAUoBSwFqAWwBbgACAFsADwAPAAQAEQARAAsAEgASAAMAEwATAAIAFwAXAAYAGgAaABYAHQAdAAMAIAAgABIAIgAiABAAIwAjAAoAJwAnABEAKAAoAA8AKwArAAkALQAtAAEALgAuABUAOQA5ABcAOwA7AAcAPAA8AA4APQA9AAUAPgA+AA0AQQBBAA0AQgBCAAwASgBKABMASwBLAAgATQBNABMATgBOAAgAUgBSAAgAgwCDAAgAhQCFAAgAlgCWAAIAsACwABYAtQC1AAEAtgC2AAMAugC6AAIAuwC7AAEAvADCAAQAygDKAAsAywDLAAkAzADPAAIA0ADTAAEA1ADXAAYA3QDhAAMA4gDlAAoA5gDmABEA7QDwAAUA8QDyAA0A9AD0AAcA9QD1ABEA9gD2AA8A9wD3AAwA+AD4AAMBAwEDAAQBBQEFAAQBBwEHAAIBCAEIAAEBCQEJAAIBCgEKAAEBCwELAAIBDAEMAAEBDQENAAIBDgEOAAEBEQERAAYBEwETAAYBFQEVAAsBFgEWAAkBFwEXAAsBGAEYAAkBGgEaAAcBHgEeAAUBIAEgAAUBIgEiAAUBJwEnAA8BKAEoAAwBKQEpAA8BKgEqAAwBNAE0ABABNQE1ABQBNwE3AA4BOgE6ABIBPAE8ABIBPgE+AAMBPwE/ABUBQAFAAAMBQQFBABQBRQFFAAsBRgFGAAkBSAFIAAcBSgFLABABagFqAAcBbAFsAA4BbgFuAA4AAgBSAA8ADwAEABEAEQACABUAFQACAB0AHQACAB8AHwACACIAIgANACcAJwAOACgAKAAPACkAKQADACsALQABAC8ALwAMADEAMQAGADYANgAIADcANwABADkAOQABADsAOwAHADwAPAALAD0APQAFAD4APgAKAEEAQQAKAEIAQgAJALIAsgABALUAtQADALYAtgABALoAugACALsAuwABALwAwgAEAMMAyQADAMoAygACAMsAywABANAA0wABANgA2wAGAN0A4QACAOYA5gAOAOcA5wAIAOgA7AABAO0A8AAFAPEA8gAKAPQA9AAHAPUA9QAOAPYA9gAPAPcA9wAJAQMBAwAEAQQBBAADAQUBBQAEAQYBBgADAQgBCAABAQoBCgABAQwBDAABAQ4BDgABARIBEgAGARQBFAAGARUBFQACARYBFgABARcBFwACARgBGAABARoBGgAHARwBHAAFAR4BHgAFASABIAAFASIBIgAFASQBJAAIASYBJgAIASgBKAAJASoBKgAJAS0BLQACAS4BLgAMAS8BLwACATABMQAMATQBNAANATcBNwALATkBOQAIAT8BPwABAUEBQQABAUQBRQACAUYBRgABAUgBSAAHAUoBSwANAWIBYgAGAWoBagAHAWwBbAALAW4BbgALAAQAAAABAAgAAQAMADQABAC2ATAAAgAGAUwBTwAAAVEBWwAEAV4BYQAPAWMBZAATAWYBaQAVAW8BcAAZAAIAFQAPAA8AAAARABMAAQAVABcABAAZABoABwAcAB0ACQAgACMACwAnACkADwArAC0AEgAvADAAFQAzADcAFwA6AD0AHABBAEIAIAC8APgAIgEDARMAXwEVASEAcAEjASsAfQEtAT4AhgFAAUsAmAFiAWIApAFqAWoApQFsAW4ApgAbAAALQgAAC0gAAAtOAAALVAAAC1oAAQBuAAALYAACCoQAAAtaAAALYAACCooAAAtmAAALbAAAC3IAAAt4AAALfgAAC4QAAAuKAAALkAAAC5YAAAxCAAMAdAADAHQAAAucAAALogAAC6gAAAuuAAEAAAKYAAEA3QAAAKkG9AAABvoHAAXOAAAJdgAABUoAAAk0AAAHMAAAB0IHSAlkAAAIjAAABVAAAAVWAAAHnAAAB6IHqAlMAAAFXAAACM4I1AjaAAAI5gAACDgAAAViB2YHbAdyCNQAAAkQAAAJlAAAB8wAAAmgAAAItgAACA4IFAgaCCAFaAAABtYAAAVuAAAIXAAABwYAAAcMBxIF2gAACYIAAAk6CUAJRgAABXQAAAdUB1oImAAACOwAAAV6AAAIRAAACVgAAAWAAAAIvAjCCMgAAAWGAAAFjAAACPIAAAhEAAAFkgd+B4QHigWYAAAJHAAACb4AAAfSAAAJ0AnWCOAAAAcGB/wIAggIBZ4AAAbEAAAJvgAACGgAAAWkAAAG+gcABaoAAAb6BwAFsAAABvoHAAW2AAAG+gcABbwAAAb6BwAFwgAABvoHAAXIAAAG+gcAB94AAAcMBxIGoAAABwwHEgasAAAHDAcSBu4AAAcMBxIGsgAABwwHEgfqAAAHDAcSBqYAAAcMBxIFzgAABdQAAAXaAAAF4AAABeYAAAdCB0gF7AAAB0IHSAXyAAAHQgdIByQAAAdCB0gF+AAAB1QHWgX+AAAHVAdaBgQAAAdUB1oHKgAAB1QHWgYKAAAHogeoBhAAAAeiB6gGFgAAB6IHqAYcAAAHogeoBiIAAAmyCbgGKAAACbIJuAYuAAAJsgm4BjQAAAmyCbgGOgAACDgAAAZAB2YHbAdyBkYHZgdsB3IGTAdmB2wHcgZSB2YHbAdyBlgHZgdsB3IGXggUCBoIIAZkCBQIGgggBmoIFAgaCCAGcAgUCBoIIAZ2AAAG1gAABnwAAAhEAAAGggd+B4QHigaIB34HhAeKBo4HfgeEB4oGlAd+B4QHigaaB34HhAeKBqAH/AgCCAgGpgf8CAIICAasB/wIAggIBrIH/AgCCAgGuAAABsQAAAa+AAAGxAAABsoAAAfMAAAG4gAAB9IAAAbQAAAG1gAABtwAAAhcAAAG4gAACGgAAAkiAAAJKAAABugAAAb6BwAG7gAABwwHEgb0AAAG+gcABwYAAAcMBxIHGAAAB0IHSAceAAAHVAdaByQAAAdCB0gHKgAAB1QHWgcwAAAHQgdIBzYAAAdUB1oHPAAAB0IHSAdOAAAHVAdaB2AHZgdsB3IHeAd+B4QHigeQAAAHogeoB5YAAAmyCbgHnAAAB6IHqAeuAAAJdgAAB7QAAAmCAAAHugAACXYAAAfAAAAJggAAB8YAAAfMAAAIUAAAB9IAAAfYCBQIGgggB94H/AgCCAgH5AgUCBoIIAfqB/wIAggIB/AIFAgaCCAH9gf8CAIICAgOCBQIGgggCCYAAAg4AAAILAAACEQAAAgyAAAIOAAACD4AAAhEAAAISgAACFwAAAhQAAAIaAAACFYAAAhcAAAIYgAACGgAAAhuAAAIdAAACHoAAAiMAAAIgAAACOwAAAiGAAAIjAAACJIAAAjsAAAImAAACJ4AAAjOCNQIpAAACLwIwgiqAAAIsAAACLYAAAi8CMIIyAAACM4I1AjaAAAJ0AnWCOAAAAjmAAAI7AAACPIAAAj4AAAI/gAACRAAAAkEAAAJHAAACQoAAAkQAAAJFgAACRwAAAkiAAAJKAAACS4AAAk0AAAJOglACUYAAAlMAAAJUgAACVgAAAleAAAJZAAACWoAAAlwAAAJdgAACXwAAAmCAAAJlAAACYgAAAm+AAAJjgAACZQAAAmaAAAJoAAACaYAAAmgAAAJpgAACawAAAmyCbgJvgAACcQAAAnQCdYJygAACdAJ1gncAAAJ0AnWCdwAAAABAU0CnAABAVQCnAABAVQAAAABAT8AAAABAX4CnAABAVgCnAABATcCnAABARwB8AABAR4DHAABARwAAAABAbsB8AABAbsAAAABAR8B8AABAN0B8AABAQQB8AABAVkDcwABAVkDUQABAVkDYwABAVkDHAABATQDdQABAYADdQABAVkDTQABAWUCnAABAWX/RQABARgB8AABARP/RQABAQoDdQABAVYDdQABAS8DcwABAPkC2AABAUAC1wABARwC1gABAMIDdQABAQ4DdQABAOcDcwABAOcDYwABAH4C2AABAMUC1wABAKEC1gABAKECzwABAW0DUQABAVkDdQABAaUDdQABAX4DcwABAX4DUQABAX4DYwABAScDdQABAXMDdQABAUwDcwABAUwDYwABAX8DdQABATECsQABAPwC2AABAUMC1wABAR8C1gABAR8CsQABAR8CzwABAPoC2AABAUEC1wABAR0C1gABAR0CzwABASgC1wABAQQCzwABAQMAAAABATADbQABAVgDYwABAVgAAAABATcDbQABAQwC0AABAVkDUwABAR0CsQABAVkCnAABAVkAAAABAo4AAAABAR0B8AABARUAAAABAgYAAAABAS8DTQABARwCnAABAS8DYwABARwCzwABAS8CnAABARsB8AABAS8DbQABASIAAAABAgoAAAABARwC0AABARsAAAABAf0ACgABAZwDgwABAugCnAABAX4AAAABArAACgABAU8C1wABAikB8AABAR8AAAABAgQACgABAOcDTQABAKECnAABAOcCnAABAOUAAAABAUsAAAABAYwDdQABATwC1wABAWUDYwABARgCzwABAVcDdQABAUkAAAABAQsAAAABAUwDTQABAR0CnAABAUwDXQABAR0DIAABAWoDgwABAU0C1wABAiYB8AABAR0AAAABAgEACgABAUwCnAABAoMCnAABAUwAAAABAbgAAAABAZQDdQABAVUC1wABAW0DbQABAUIAAAABATEC0AABAR4AAAABAV4DdQABATAC1wABATcDYwABATcAAAABAQwCzwABAQIAAAABAXMCnAABAXMAAAABAXIDUwABASkCsQABAXIDYwABAV4AAAABASkCzwABASkB8AABAUQDBgABAPb/BQABAJD/BQABATUDbQABATUAAAABAIECqAABANUCmgABAKcAAAABAQ0CnAABATMCnAABAQ0AAAABAO8AAAABAW0CnAABASv/BQABATEB8AABAQf/BQABAVoDdQABAQEC1wABATMDbQABAUcAAAABAN0C0AABAIAAAAABAaQCnAABAa4AAAABAU0DbQABAVcAAAABASoDHAABAi4ClwABASoAAAABAUoCnAABASj/BQABARwDHAABAQX/BQABAXICnAABAUf/BQABAWUDbQABAWUAAAABARgC0AABARMAAAABAUn/RQABAQv/RQABATACnAABATL/BQABATUCnAABAR7/BQABAKEB8AABAJcAAAABAQ8ACgABAQwB8AABAPT/BQABAO//RQABAL4B8AABAQMCzwABANj/BQAGABAAAQAKAAAAAQAMAAwAAQAUACoAAQACAVQBVwACAAAACgAAABAAAQBsAAAAAQCFAAAAAgAGAAwAAQBs/0UAAQBu/wUABgAQAAEACgABAAEADAAMAAEAPAEIAAEAFgFMAU0BTgFPAVEBUwFVAVYBWAFZAVoBWwFeAV8BYAFhAWMBZAFoAWkBbwFwABYAAABaAAAAYAAAAGYAAABsAAAAcgAAAHgAAAByAAAAeAAAAH4AAACEAAAAigAAAJAAAACWAAAAnAAAAKIAAACoAAAArgAAAVoAAAC0AAAAugAAAMAAAADGAAEARAHwAAEARgKcAAEAkAHwAAEAkAKcAAEAxQHwAAEAxQKcAAEApwHwAAEApwKcAAEARwHwAAEARwKcAAEAiwHwAAEAkwKcAAEAngHwAAEAsAKcAAEAlgHwAAEAhQHwAAEAhQKcAAEAuQHwAAEAuQKcABYALgA0ADoAQABGAEwAUgBYAF4AZABqAHAAdgB8AIIAiACOAJQAmgCgAKYArAABAGgC1wABAG0DdQABAJACsQABAJADUwABAMUC0AABAMUDbQABAMUC1gABAMUDcwABAKcCzwABAKcDYwABAEcCzwABAEcDYwABAGgC2AABAG4DdQABAM4C1wABAM4DgwABAJYCnAABAJYDTQABAIUDIAABAIUDXQABALkCsQABALkDUQAAAAEAAAAKAEoAvAADREZMVAAUZ3JlawAUbGF0bgAYAA4AAAAKAAFNT0wgACAAAP//AAgAAAABAAIAAwAFAAYABwAIAAD//wABAAQACWFhbHQAOGNhc2UAQGNjbXAARmZyYWMATmxvY2wAVG9yZG4AWnBudW0AYHN1cHMAZnRudW0AbAAAAAIAAAABAAAAAQAPAAAAAgACAAUAAAABAAoAAAABAAgAAAABAAsAAAABAA0AAAABAAkAAAABAA4AEAAiAMQA8gJCAkICiAPgA+AC0gL0AxQDUAOOA7ADyAPgAAEAAAABAAgAAgBOACQA+QD+AP8BAAEBAQIAVwBmAFcAZgAFAAYABwAIAAkACgALAAwADQAOAUkBagFLAU0BTwFTAVYBWQFbAV8BYQFkAWcBaQFuAXAAAQAkAAUACgALAAwADQAOAA8AHQApADcA+QD6APsA/AD9AP4A/wEAAQEBAgFHAUgBSgFMAU4BUQFVAVgBWgFeAWABYwFmAWgBbQFvAAMAAAABAAgAAQI+AAQADgAUABoAIAACAGUA+gACAF4A+wACAF8A/AACAP0BXQAGAAAABAAOACAAXABuAAMAAAABACYAAQA0AAEAAAADAAMAAAABABQAAgAaACIAAQAAAAQAAQABADEAAQACAVQBZgABAAsBTAFOAVEBVQFYAVoBXgFgAWMBaAFvAAMAAQK4AAECuAAAAAEAAAADAAMAAQASAAECpgAAAAEAAAAEAAEAZgAPABAAEQASABMAFAAVABYAFwAYABkAGgAbABwAHQAeAB8AIAAhACIAIwAkACUAJgAnACgAlgCkAKcAsACzALQAugC8AL0AvgC/AMAAwQDCAMoAzADNAM4AzwDUANUA1gDXANwA3QDeAN8A4ADhAOIA4wDkAOUA5gDzAPUA9gD4AQMBBQEHAQkBCwENAQ8BEQETARUBFwEZARsBHQEfASEBIwElAScBKQErAS0BLwEyATQBNgE4AToBPAE+AUABQgFEAUUBRwFJAUoBSwABAAAAAQAIAAIAIAANAWIBTQFPAVMBVgFZAVsBXwFhAWQBZwFpAXAAAQANADEBTAFOAVEBVQFYAVoBXgFgAWMBZgFoAW8ABgAAAAIACgAcAAMAAAABAXQAAQAkAAEAAAAGAAMAAQASAAEBYgAAAAEAAAAHAAEADAFNAU8BUwFWAVkBWwFfAWEBZAFnAWkBcAABAAAAAQAIAAIADgAEAUkBagFLAW4AAQAEAUcBSAFKAW0AAQAAAAEACAACAA4ABABlAF4AXwFdAAIAAQAGAAkAAAAEAAAAAQAIAAEALAACAAoAIAACAAYADgBoAAMAhgAJAGkAAwCGAAcAAQAEAGoAAwCGAAkAAQACAAYACAAGAAAAAgAKACQAAwABAHwAAQASAAAAAQAAAAwAAQACAA8AKQADAAEAYgABABIAAAABAAAADAABAAIAHQA3AAEAAAABAAgAAgAOAAQAVwBmAFcAZgABAAQADwAdACkANwABAAAAAQAIAAEABv8MAAIAAQD5AQIAAAABAAAAAQAIAAEABgD0AAIAAQAFAA4AAAABAAAAAQAIAAIAHgAMAU0BTwFTAVYBWQFbAV8BYQFkAWcBaQFwAAEADAFMAU4BUQFVAVgBWgFeAWABYwFmAWgBbwAAAAAAAQAAAAA=';
if (isset($_GET['font'])) serve_font((string)$_GET['font']);

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
header("Content-Security-Policy: default-src 'self'; style-src 'unsafe-inline'; script-src 'nonce-$nonce'; connect-src 'self' https://geocoding-api.open-meteo.com; img-src 'self' blob:; media-src 'self' blob:");
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
// Risposte vocali dei telefoni, solo per chi è entrato nel portale.
if (!empty($_SESSION['ok']) && isset($_GET['voicefile'], $_GET['id']) && valid_id((string)$_GET['id']) && preg_match('/^\d+_\d*\.m4a$/', (string)$_GET['voicefile'])) {
    $vf = DATA . '/voice/' . $_GET['id'] . '/' . $_GET['voicefile'];
    if (!is_file($vf)) { http_response_code(404); exit; }
    header('Content-Type: audio/mp4'); header('Content-Length: ' . filesize($vf)); header('Cache-Control: private, max-age=86400');
    readfile($vf); exit;
}
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
            // Videomessaggio facoltativo: salvato come le foto (per hash), il telefono ne verifica l'hash.
            $video = '';
            $vf = $_FILES['video'] ?? null;
            if ($vf && $vf['error'] !== UPLOAD_ERR_NO_FILE) {
                $mime = $vf['error'] === UPLOAD_ERR_OK && function_exists('finfo_open') ? (string)finfo_file(finfo_open(FILEINFO_MIME_TYPE), $vf['tmp_name']) : '';
                if ($vf['error'] !== UPLOAD_ERR_OK || $vf['size'] > video_max()) $err[] = t('e_video_size', round(video_max() / 1048576));
                elseif (!in_array($mime, VIDEO_TYPES, true)) $err[] = t('e_video_type');
                else {
                    $vh = hash_file('sha256', $vf['tmp_name']);
                    if (!is_file(media_path($vh))) move_uploaded_file($vf['tmp_name'], media_path($vh));
                    $video = 'media:' . $vh;
                }
            } elseif (($_SERVER['CONTENT_LENGTH'] ?? 0) > 0 && empty($_POST)) $err[] = t('e_video_size', round(video_max() / 1048576));
            if ($video !== '' && $text === '') $text = t('video_message_text');
            if ($err) { /* errori già raccolti */ }
            elseif ($text === '' || mb_strlen($text) > MESSAGE_MAX) { $err[] = t('e_message', MESSAGE_MAX); }
            else {
                $meta = read_json(dev_path($id, 'meta.json'), []);
                // Il video del messaggio precedente non serve più (a meno che sia anche una foto della config).
                $oldV = substr((string)($meta['message']['video'] ?? ''), 6);
                $keep = phone_media_refs(read_json(dev_path($id, 'config.json'), []));
                if ($oldV !== '' && $oldV !== substr($video, 6) && !in_array($oldV, $keep, true) && ($op = media_path($oldV)) && is_file($op)) @unlink($op);
                $mid = (int)(microtime(true) * 1000);
                $payload = json_encode(['device' => $id, 'id' => $mid, 'text' => $text, 'from' => $from, 'at' => $mid] + ($video !== '' ? ['video' => $video] : []), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
                $sig = sodium_crypto_sign_detached($payload, sodium_crypto_sign_secretkey(keypair()));
                write_atomic(dev_path($id, 'message.json'), json_encode(['v' => 1, 'payload' => base64_encode($payload), 'sig' => base64_encode($sig)]));
                $meta['message'] = ['id' => $mid, 'text' => $text, 'from' => $from, 'at' => time()] + ($video !== '' ? ['video' => $video] : []);
                $meta['messageFrom'] = $from;
                write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
                header('Location: ?edit=' . urlencode($id) . '#family'); exit;
            }
        } elseif ($act === 'calendar') {
            $meta = read_json(dev_path($id, 'meta.json'), []);
            $u = trim((string)($_POST['calendarUrl'] ?? ''));
            $meta['calendarUrl'] = preg_match('#^(https?|webcal)://\S+$#i', $u) ? mb_substr($u, 0, 1000) : '';
            $meta['calendarLead'] = in_array((int)($_POST['calendarLead'] ?? 60), [0, 15, 30, 60, 120], true) ? (int)$_POST['calendarLead'] : 60;
            $meta['calendarEve'] = !empty($_POST['calendarEve']);
            write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
            calendar_sync($id, true);
            header('Location: ?edit=' . urlencode($id) . '#calendar'); exit;
        } elseif ($act === 'alertlang') {
            $meta = read_json(dev_path($id, 'meta.json'), []);
            $l = (string)($_POST['alertLang'] ?? '');
            $meta['alertLang'] = in_array($l, langs(), true) ? $l : '';
            write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
            header('Location: ?edit=' . urlencode($id) . '#alertlang'); exit;
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
    // La lingua di chi guarda non cambia più quella degli avvisi (scelta per telefono, vedi alert_lang()).
    if ($s['panelUrl'] !== base_url()) { $s['panelUrl'] = base_url(); save_settings($s); }
    run_checks();
}
?><!doctype html>
<html lang="<?= h($LANG) ?>"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title><?= th('title') ?></title>
<style>
:root{--ink:#1A1A1A;--mute:#4F4B45;--line:#d9d3c7;--bg:#F7F5F0;--card:#fff;--accent:#1F4FA8;--ok:#1B6E34;--warn:#A84300;--bad:#B71C1C}
*{box-sizing:border-box}
@font-face{font-family:Atkinson;src:url("?font=regular&v=1") format("truetype");font-weight:400;font-display:swap}
@font-face{font-family:Atkinson;src:url("?font=bold&v=1") format("truetype");font-weight:700;font-display:swap}
body{font:16px/1.45 Atkinson,system-ui,-apple-system,"Segoe UI",sans-serif;margin:0;background:var(--bg);color:var(--ink)}
header.top{background:var(--ink);color:#fff}
header.top .in{max-width:1080px;margin:0 auto;padding:12px 16px;display:flex;flex-wrap:wrap;align-items:center;gap:8px 12px}
header.top .brand{font-weight:700;font-size:19px;flex:1 1 auto;min-width:0}
/* Schermi stretti (o carattere grande): il nome sopra, menu ed «Esci» sotto, mai oltre il bordo. */
@media (max-width:560px){header.top .brand{flex-basis:100%}header.top .nav{flex:1}}
.card{overflow-x:auto}img,textarea,input,select{max-width:100%}code{overflow-wrap:anywhere;word-break:break-all}
header.top a{color:#fff}
.nav{display:flex;gap:4px}.nav a{padding:8px 12px;border-radius:12px;text-decoration:none;font-weight:600}.nav a[aria-current=page]{background:#ffffff26}
main{max-width:1080px;margin:0 auto;padding:16px}
h1{font-size:26px;margin:8px 0 4px}h2{font-size:20px;margin:26px 0 8px}h3{font-size:17px;margin:14px 0 6px}
p.lead{color:var(--mute);margin-top:0}
.card{background:var(--card);border:1px solid var(--line);border-radius:24px;padding:16px;margin:12px 0}
.msg{border-radius:12px;padding:12px 14px;margin:12px 0;font-weight:600}
.msg.ok{background:#E3F1E7;color:#124a24}.msg.err{background:#FCE4E4;color:#7a1010}.msg.warn{background:#FFF0DF;color:#6b2f00}
label{display:block;font-weight:600;margin:10px 0 4px}
.hint{font-weight:400;color:var(--mute);font-size:14px}
input[type=text],input[type=password],input[type=tel],input[type=url],input[type=time],input[type=number],select,textarea{font:inherit;padding:10px 12px;border:2px solid #bdb6a8;border-radius:12px;width:100%;background:#fff;color:var(--ink)}
input:focus,select:focus,textarea:focus{outline:3px solid #9db7e8;border-color:var(--accent)}
textarea.json{min-height:420px;font:13px/1.4 ui-monospace,Consolas,monospace}
button,.btn{font:inherit;font-weight:600;border:0;border-radius:12px;padding:10px 16px;min-height:44px;cursor:pointer;background:var(--ink);color:#fff;text-decoration:none;display:inline-flex;align-items:center;gap:6px}
button.sec,.btn.sec{background:#fff;color:var(--ink);border:2px solid var(--ink)}
button.danger{background:#fff;color:var(--bad);border:2px solid var(--bad)}
button.small{min-height:36px;padding:6px 12px;font-size:14px}
button:disabled{opacity:.45;cursor:not-allowed}
.row{display:flex;gap:12px;flex-wrap:wrap}.row>*{flex:1 1 200px}
.grid-devices{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:12px}
.dev{display:block;color:inherit;text-decoration:none;background:var(--card);border:1px solid var(--line);border-radius:24px;padding:14px}
.dev:hover{border-color:var(--ink)}
.dev .name{font-weight:700;font-size:18px}
.pill{display:inline-block;border-radius:999px;padding:2px 10px;font-size:13px;font-weight:600;margin:6px 6px 0 0}
.pill.ok{background:#E3F1E7;color:#124a24}.pill.warn{background:#FFF0DF;color:#7a3500}.pill.bad{background:#FCE4E4;color:#7a1010}.pill.mute{background:#eee;color:#444}
code.k{display:block;background:#f1eee7;border-radius:12px;padding:10px;word-break:break-all;font:14px ui-monospace,Consolas,monospace;margin:4px 0}
ol.steps{padding-left:22px}ol.steps li{margin:6px 0}
.tabs{display:flex;gap:6px;flex-wrap:wrap;border-bottom:2px solid var(--line);margin:16px 0 0}
.tabs button{background:transparent;color:var(--ink);border-radius:12px 12px 0 0;border:2px solid transparent;border-bottom:0;margin-bottom:-2px}
.tabs button[aria-selected=true]{background:var(--card);border-color:var(--line)}
.tabpanel{background:var(--card);border:1px solid var(--line);border-top:0;border-radius:0 0 24px 24px;padding:16px}
.editor{display:flex;gap:20px;flex-wrap:wrap;align-items:flex-start}
.phone{flex:0 0 300px;background:#F7F5F0;border:8px solid var(--ink);border-radius:24px;padding:12px}
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
.issues{background:#FCE4E4;color:#7a1010;border-radius:12px;padding:10px 14px;margin:10px 0}
.issues:empty{display:none}
.rem{border:1px solid var(--line);border-radius:12px;padding:12px;margin:10px 0}
.pagebar{display:flex;gap:6px;flex-wrap:wrap;align-items:center;margin-bottom:10px}
.pagebar button[aria-pressed=true]{background:var(--accent);color:#fff;border-color:var(--accent)}
.invite{border-color:#FFA726 !important;box-shadow:0 0 0 3px #FFE0B2}
.pagetabs{display:flex;gap:8px;flex-wrap:wrap;margin:18px 0 6px}
.pagetabs button{background:#fff;color:var(--ink);border:2px solid var(--line);border-radius:999px;padding:9px 16px;font-weight:700}
.pagetabs button[aria-pressed=true]{background:var(--ink);color:#fff;border-color:var(--ink)}
.tile .ti{display:block;font-size:20px;line-height:1.1;margin-bottom:2px}
.sym{width:1.25em;height:1.25em;flex:none;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round;vertical-align:-.25em}
.tile .sym.ti{width:28px;height:28px;margin:0 auto 2px}
.symgrid{display:flex;flex-wrap:wrap;gap:6px;max-width:560px}
.symbtn{width:48px;height:48px;min-height:48px;padding:0;justify-content:center;background:#fff;color:var(--ink);border:2px solid var(--line)}
.symbtn[aria-checked=true]{background:var(--ink);color:#fff;border-color:var(--ink)}
.symbtn .sym{width:28px;height:28px}
.tile img.ti{width:30px;height:30px;border-radius:50%;object-fit:cover;margin:0 auto 2px;border:2px solid #fff}
.phgroup{border:1px solid var(--line);border-radius:12px;padding:4px 14px 8px;margin:14px 0;background:#fff}
.phgroup h4{margin:10px 0 0;font-size:16px}
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
    <?php foreach (array_merge(['sos', 'reminder_missed', 'battery', 'offline', 'call_blocked', 'location', 'ring_done', 'message_read'], NOTIFY_0170) as $k): $label = t('n_' . $k); ?>
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
    if (!$loaded && ($lastH['source'] ?? '') === 'phone'): ?><p class="hint"><?= sym_svg('phone') ?> <?= th('phone_changed_info', date('d/m/Y H:i', (int)($lastH['at'] ?? 0)), implode('; ', (array)($lastH['changes'] ?? []))) ?></p><?php endif; ?>
  <?php if (isset($meta['badgesAccess']) && !$meta['badgesAccess']): ?><div class="msg warn"><?= th('no_notif_access') ?></div><?php endif; ?>
  <?php foreach (access_warnings($meta) as $w): ?><div class="msg warn"><?= h($w) ?></div><?php endforeach; ?>
  <div class="pagetabs">
    <button type="button" data-ptab="p-config"><?= sym_svg('edit') ?> <?= th('ptab_config') ?></button>
    <button type="button" data-ptab="p-talk"><?= sym_svg('message') ?> <?= th('ptab_talk') ?></button>
    <button type="button" data-ptab="p-status"><?= sym_svg('chart') ?> <?= th('ptab_status') ?></button>
    <button type="button" data-ptab="p-settings"><?= sym_svg('settings') ?> <?= th('ptab_settings') ?></button>
  </div>
  <section class="pagepanel" id="p-config">
  <?php $lastLocPre = null; foreach ($urgent as $ev) if (isset($ev['lat'], $ev['lon']) && $ev['lat'] !== null) { $lastLocPre = ['lat' => (float)$ev['lat'], 'lon' => (float)$ev['lon']]; break; } ?>
  <form method="post" id="pubform" data-dirty="<?= $loaded ? '1' : '0' ?>" data-edition="<?= h($meta['edition'] ?? '') ?>" data-app="<?= h($meta['appVersion'] ?? '') ?>" data-csrf="<?= h($csrf) ?>" data-stock="<?= h(json_encode((object)($meta['stock'] ?? []))) ?>" data-lastloc="<?= h(json_encode($lastLocPre ?? null)) ?>">
    <input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="publish"><input type="hidden" name="id" value="<?= h($edit) ?>">
    <div class="row">
      <div><label for="name"><?= th('name') ?> <span class="hint"><?= th('name_hint') ?></span></label><input id="name" type="text" name="name" value="<?= h($meta['name'] ?? '') ?>" <?= ($meta['name'] ?? '') === '' ? 'class="invite"' : '' ?>><?php if (($meta['name'] ?? '') === ''): ?><div class="hint">👆 <?= th('name_invite') ?></div><?php endif; ?></div>
      <div><label for="pin"><?= th('new_pin') ?> <span class="hint"><?= th('new_pin_hint') ?></span></label><input id="pin" type="text" name="pin" inputmode="numeric" pattern="\d{4,8}" autocomplete="off" placeholder="<?= th('pin_placeholder') ?>"></div>
    </div>

    <details class="card" id="wizard" <?= history($edit) ? '' : 'open' ?>><summary><?= sym_svg('check') ?> <?= th('wiz_title') ?></summary><div id="wizbody"></div></details>
    <div class="msg warn" id="warns" hidden></div>
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
  </section>
  <section class="pagepanel" id="p-talk" hidden>
  <h2 id="where"><?= sym_svg('pin') ?> <?= th('locate_title') ?></h2>
  <div class="card">
    <?php if ($lastLoc): $la = $lastLoc['lat'] ?? null; $lo = $lastLoc['lon'] ?? null; ?>
      <p><b><?= th('locate_last', date('d/m H:i', (int)(($lastLoc['at'] ?? 0) / 1000))) ?></b>
      <?php if ($la !== null && $lo !== null): ?> · <a href="https://www.openstreetmap.org/?mlat=<?= (float)$la ?>&amp;mlon=<?= (float)$lo ?>#map=17/<?= (float)$la ?>/<?= (float)$lo ?>" target="_blank" rel="noopener"><?= th('locate_map') ?></a><?php endif; ?></p>
      <p class="hint"><?= h(ev_text($lastLoc)) ?></p>
    <?php else: ?><p class="hint"><?= th('locate_none') ?></p><?php endif; ?>
    <?php if ($pending): ?><p class="msg warn"><?= th('locate_pending') ?></p><?php endif; ?>
    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="locate"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="sec"><?= th('locate_button') ?></button></form>
    <p class="hint"><?= th('locate_hint') ?></p>
  </div>

  <?php $fm = $meta['message'] ?? null; $ringing = time() - (int)($meta['ringRequest'] ?? 0) < 3600;
    $oldApp = !empty($meta['appVersion']) && version_compare((string)$meta['appVersion'], APP_FEATURES_0130, '<'); ?>
  <h2 id="family"><?= sym_svg('message') ?> <?= th('family_title') ?></h2>
  <div class="card">
    <?php if ($oldApp): ?><p class="msg warn"><?= th('family_old_app', $meta['appVersion']) ?></p><?php endif; ?>
    <form method="post" enctype="multipart/form-data" id="msgform" data-vmax="<?= video_max() ?>"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="message"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <label for="mtext"><?= th('message_label') ?> <span class="hint"><?= th('message_hint', MESSAGE_MAX) ?></span></label>
      <textarea id="mtext" name="text" maxlength="<?= MESSAGE_MAX ?>" rows="3" placeholder="<?= th('message_placeholder') ?>"></textarea>
      <label for="mvideo"><?= th('video_label') ?> <span class="hint"><?= th('video_hint', round(video_max() / 1048576)) ?></span></label>
      <input id="mvideo" type="file" name="video" accept="video/*">
      <?php if (!empty($meta['appVersion']) && version_compare((string)$meta['appVersion'], '0.15.0', '<')): ?><p class="msg warn"><?= th('video_old_app', $meta['appVersion']) ?></p><?php endif; ?>
      <?php if (video_max() < (20 << 20)): ?><p class="hint"><?= th('video_small_host', round(video_max() / 1048576)) ?></p><?php endif; ?>
      <p class="hint" id="vstatus"></p>
      <div class="row" style="align-items:flex-end">
        <div><label for="mfrom"><?= th('message_from_label') ?></label><input id="mfrom" type="text" name="from" maxlength="40" value="<?= h($meta['messageFrom'] ?? '') ?>" placeholder="<?= th('message_from_placeholder') ?>"></div>
        <div style="flex:0 0 auto"><button><?= th('message_send') ?></button></div>
      </div>
    </form>
    <script nonce="<?= h($nonce) ?>">
    // Videomessaggio troppo pesante per l'hosting: lo si rimpicciolisce nel browser (640 px, ~1 Mbit/s,
    // WebM) riproducendolo una volta; dura quanto il video. Senza testo né video non si invia.
    (() => {
      const f = document.getElementById('msgform'), inp = document.getElementById('mvideo'), st = document.getElementById('vstatus');
      const max = +f.dataset.vmax, TX = <?= json_encode(['need' => t('e_message_or_video'), 'shrink' => t('video_shrinking'), 'nosh' => t('video_cannot_shrink', round(video_max() / 1048576)), 'big' => t('e_video_size', round(video_max() / 1048576))], JSON_UNESCAPED_UNICODE) ?>;
      let busy = false;
      async function shrink(file) {
        const v = document.createElement('video'); v.src = URL.createObjectURL(file); v.playsInline = true;
        await new Promise((ok, ko) => { v.onloadedmetadata = ok; v.onerror = ko; });
        const sc = Math.min(1, (max / (v.duration || 60) < 100000 ? 480 : 640) / Math.max(v.videoWidth, v.videoHeight));
        const c = document.createElement('canvas'); c.width = Math.round(v.videoWidth * sc / 2) * 2; c.height = Math.round(v.videoHeight * sc / 2) * 2;
        const g = c.getContext('2d'), stream = c.captureStream(25);
        try {
          const ac = new AudioContext(), dest = ac.createMediaStreamDestination();
          ac.createMediaElementSource(v).connect(dest);
          dest.stream.getAudioTracks().forEach(t => stream.addTrack(t));
        } catch (e) { /* senza audio piuttosto che niente */ }
        const type = ['video/webm;codecs=vp8,opus', 'video/webm'].find(t => MediaRecorder.isTypeSupported(t));
        // Bitrate scelto per stare nel limite (con margine), tra 250 e 1200 kbit/s.
        const vbr = Math.max(250000, Math.min(1200000, Math.floor(max * 8 * 0.8 / (v.duration || 60)) - 64000));
        const rec = new MediaRecorder(stream, {mimeType: type, videoBitsPerSecond: vbr, audioBitsPerSecond: 64000}), parts = [];
        rec.ondataavailable = e => e.data.size && parts.push(e.data);
        const done = new Promise(ok => { rec.onstop = ok; });
        const draw = () => { if (!v.paused && !v.ended) { g.drawImage(v, 0, 0, c.width, c.height); st.textContent = TX.shrink + ' ' + Math.round(100 * v.currentTime / (v.duration || 1)) + '%'; requestAnimationFrame(draw); } };
        v.onended = () => rec.stop();
        rec.start(1000); await v.play(); draw();
        await done;
        return new File(parts, file.name.replace(/\.\w+$/, '') + '.webm', {type: 'video/webm'});
      }
      f.addEventListener('submit', async e => {
        const file = inp.files[0];
        if (!file && !document.getElementById('mtext').value.trim()) { e.preventDefault(); st.textContent = TX.need; return; }
        if (!file || file.size <= max || busy) return;
        e.preventDefault();
        if (!window.MediaRecorder || !HTMLCanvasElement.prototype.captureStream) { st.textContent = TX.nosh; return; }
        busy = true;
        try {
          const small = await shrink(file);
          if (small.size > max) { st.textContent = TX.big; return; }
          const dt = new DataTransfer(); dt.items.add(small); inp.files = dt.files;
          f.submit();
        } catch (err) { st.textContent = TX.nosh; } finally { busy = false; }
      });
    })();
    </script>
    <?php if ($fm): ?><p class="hint"><?= !empty($fm['video']) ? sym_svg('video') . ' ' : '' ?><?= th('message_last', date('d/m H:i', (int)$fm['at']), $fm['text']) ?> —
      <b><?= !empty($fm['readAt']) ? th('message_read_at', date('d/m H:i', (int)$fm['readAt'])) : th('message_waiting') ?></b></p><?php endif; ?>
    <hr style="border:0;border-top:1px solid #ddd;margin:16px 0">
    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="ring"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="sec"><?= sym_svg('bell') ?> <?= th('ring_button') ?></button></form>
    <?php if ($ringing): ?><p class="msg warn"><?= th('ring_pending') ?></p><?php endif; ?>
    <p class="hint"><?= th('family_hint') ?></p>
    <?php $voices = array_reverse(glob(DATA . "/voice/$edit/*.m4a") ?: []); if ($voices): ?>
      <h3 style="margin-top:18px"><?= sym_svg('mic') ?> <?= th('voice_replies') ?></h3>
      <?php foreach (array_slice($voices, 0, 10) as $vf): $vn = basename($vf); ?>
        <div style="display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:8px 0">
          <span class="hint" style="min-width:110px"><?= h(date('d/m H:i', (int)$vn)) ?></span>
          <audio controls preload="none" src="?voicefile=<?= h($vn) ?>&amp;id=<?= h($edit) ?>"></audio>
        </div>
      <?php endforeach; ?>
    <?php endif; ?>
  </div>

  </section>
  <section class="pagepanel" id="p-status" hidden>
  <h2><?= sym_svg('chart') ?> <?= th('weekly_card') ?></h2>
  <div class="card"><ul style="margin:0;padding-left:20px"><?php foreach (weekly_lines($edit) as $l): ?><li><?= h($l) ?></li><?php endforeach; ?></ul></div>

  <h2><?= sym_svg('bell') ?> <?= th('alerts_from_phone') ?></h2>
  <div class="card">
  <?php if (!$urgent): ?><p class="hint"><?= th('no_alerts_received') ?></p><?php else: ?>
    <table><tr><th><?= th('col_when') ?></th><th><?= th('col_what') ?></th></tr>
    <?php foreach ($urgent as $ev): ?><tr><td><?= h(date('d/m H:i', (int)(($ev['at'] ?? 0) / 1000))) ?></td><td><b><?= ev_title_html((string)($ev['type'] ?? '')) ?></b><br><span class="hint"><?= nl2br(h(ev_text($ev))) ?></span></td></tr><?php endforeach; ?>
    </table>
  <?php endif; ?>
  </div>

  <h2><?= sym_svg('pill') ?> <?= th('rem_what_happened') ?></h2>
  <div class="card">
  <?php if (!$events): ?><p class="hint"><?= th('no_news') ?></p><?php else: ?>
    <table><tr><th><?= th('col_when') ?></th><th><?= th('col_reminder') ?></th><th><?= th('col_result') ?></th></tr>
    <?php foreach ($events as $ev): $o = $ev['outcome'] ?? ''; ?><tr><td><?= h(date('d/m H:i', (int)(($ev['at'] ?? 0) / 1000))) ?></td><td data-rem="<?= h($ev['id'] ?? '') ?>"><?= h($ev['id'] ?? '') ?></td>
      <td><span class="pill <?= $o === 'preso' ? 'ok' : ($o === 'rinviato' ? 'warn' : 'bad') ?>"><?= h(['preso' => t('out_taken'), 'rinviato' => t('out_snoozed'), 'non_confermato' => t('out_missed')][$o] ?? $o) ?></span></td></tr><?php endforeach; ?>
    </table>
  <?php endif; ?>
  </div>

  </section>
  <section class="pagepanel" id="p-settings" hidden>
  <h2><?= sym_svg('history') ?> <?= th('prev_versions') ?></h2>
  <div class="card">
  <?php if (!$versions): ?><p class="hint"><?= th('no_publications') ?></p><?php else: ?>
    <table><tr><th><?= th('col_published') ?></th><th></th></tr>
    <?php foreach ($versions as $i => $v): $hv = read_json(DATA . "/history/$edit/$v.json", []); ?>
      <tr><td><?= h(date('d/m/Y H:i', (int)($hv['at'] ?? 0))) ?><?= $i === 0 ? ' <span class="pill ok">' . th('current') . '</span>' : '' ?><?= !empty($hv['pin']) ? ' <span class="pill mute">' . th('pin_changed') . '</span>' : '' ?><?= ($hv['source'] ?? '') === 'phone' ? ' <span class="pill warn">' . th('from_phone') . '</span>' : '' ?><?= ($hv['source'] ?? '') === 'calendar' ? ' <span class="pill mute">' . th('from_calendar') . '</span>' : '' ?>
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

  <h2><?= sym_svg('grid') ?> <?= th('installed_apps') ?></h2>
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

  <details class="card" id="calendar" <?= !empty($meta['calendarUrl']) ? 'open' : '' ?>><summary><?= sym_svg('calendar') ?> <?= th('cal_title') ?></summary>
    <p class="hint"><?= th('cal_hint') ?></p>
    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="calendar"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <label for="calurl"><?= th('cal_url') ?></label><input id="calurl" type="url" name="calendarUrl" value="<?= h($meta['calendarUrl'] ?? '') ?>" placeholder="https://calendar.google.com/calendar/ical/…/basic.ics">
      <div class="row"><div><label for="callead"><?= th('cal_lead') ?></label><select id="callead" name="calendarLead">
        <?php foreach ([0, 15, 30, 60, 120] as $n): ?><option value="<?= $n ?>" <?= (int)($meta['calendarLead'] ?? 60) === $n ? 'selected' : '' ?>><?= $n ? th('cal_lead_n', $n) : th('cal_lead_0') ?></option><?php endforeach; ?></select></div></div>
      <label style="font-weight:400"><input type="checkbox" name="calendarEve" <?= !empty($meta['calendarEve']) ? 'checked' : '' ?>> <?= th('cal_eve') ?></label>
      <p><button><?= th('cal_save') ?></button></p></form>
    <?php if (!empty($meta['calendarUrl'])): ?><p class="hint"><?= !empty($meta['calendarError']) ? '<span class="pill bad">' . th('cal_error') . '</span>' : th('cal_status', (int)($meta['calendarCount'] ?? 0), ago(isset($meta['calendarAt']) ? (int)$meta['calendarAt'] : null)) ?></p><?php endif; ?>
  </details>
  <details class="card" id="alertlang"><summary><?= sym_svg('web') ?> <?= th('alert_lang_title') ?></summary>
    <p class="hint"><?= th('alert_lang_hint') ?></p>
    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="alertlang"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <?php $al = (string)($meta['alertLang'] ?? ''); $pl = (string)($meta['lang'] ?? ''); ?>
      <div class="row"><div><label for="alertlangsel"><?= th('alert_lang_title') ?></label><select id="alertlangsel" name="alertLang">
        <option value="" <?= $al === '' ? 'selected' : '' ?>><?= in_array($pl, langs(), true) ? th('alert_lang_phone', lang_table($pl)['lang_name'] ?? $pl) : th('alert_lang_phone_unknown') ?></option>
        <?php foreach (langs() as $l): ?><option value="<?= $l ?>" <?= $al === $l ? 'selected' : '' ?>><?= h(lang_table($l)['lang_name'] ?? $l) ?></option><?php endforeach; ?></select></div></div>
      <p><button><?= th('alert_lang_save') ?></button></p></form>
  </details>
  <details class="card"><summary><?= sym_svg('refresh') ?> <?= th('reinstalled') ?></summary>
    <p><?= th('reinstalled_text') ?></p>
    <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="forget"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="danger"><?= th('accept_next') ?></button></form>
  </details>
  <details class="card"><summary><?= sym_svg('settings') ?> <?= th('other_actions') ?></summary>
    <form method="post" style="margin:10px 0"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="ignore"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="sec"><?= th(empty($meta['ignoreOffline']) ? 'ignore_offline' : 'unignore_offline') ?></button></form>
    <form method="post" id="delform"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="delete"><input type="hidden" name="id" value="<?= h($edit) ?>">
      <button class="danger"><?= th('remove_phone') ?></button></form>
  </details>
  </section>
  <script nonce="<?= h($nonce) ?>">
    // Schede della pagina: si apre quella che contiene l'ancora dell'indirizzo (#family, #calendar…) o l'ultima usata.
    (() => {
      const btns = document.querySelectorAll('[data-ptab]'), panels = document.querySelectorAll('.pagepanel');
      const open = id => { panels.forEach(p => p.hidden = p.id !== id); btns.forEach(b => b.setAttribute('aria-pressed', String(b.dataset.ptab === id))); try { sessionStorage.setItem('ptab', id); } catch (e) {} };
      btns.forEach(b => b.addEventListener('click', () => open(b.dataset.ptab)));
      const target = location.hash && document.querySelector(location.hash);
      const inPanel = target && target.closest('.pagepanel');
      let saved = null; try { saved = sessionStorage.getItem('ptab'); } catch (e) {}
      open(inPanel ? inPanel.id : (saved && document.getElementById(saved) ? saved : 'p-config'));
      if (target) target.scrollIntoView();
    })();
    window.DEVICE_APPS = <?= json_encode($devApps, JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT | JSON_UNESCAPED_UNICODE) ?>;
    document.getElementById('delform').addEventListener('submit', e => { if (!confirm(T('remove_phone_confirm'))) e.preventDefault(); });
  </script>
<?php endif; ?>
<footer class="hint" style="margin:32px 0 8px;text-align:center"><?= t('footer_html', h(VERSION), h(SOURCE_URL)) ?> · <a href="?font=license">Atkinson Hyperlegible (OFL)</a>
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

  // Tipi nell'ordine del menu «Cosa fa»; il simbolo di ciascuno è in TYPE_SYM (come sul telefono).
  const TYPE_ORDER = ['app', 'call', 'wa_video', 'tg_video', 'call_me', 'torch', 'magnifier', 'where', 'music', 'news', 'sms', 'url', 'shortcut', 'clock', 'weather', 'photos', 'missed_calls',
    'messages', 'search', 'radio', 'sos'];
  const TYPES = Object.fromEntries(TYPE_ORDER.map(k => [k, T('type_' + k)]));
  // Colore tipico di ogni tipo (stessa tavolozza del telefono) e colori predefiniti delle versioni vecchie.
  const TYPE_COLOR = {wa_video: '#4FC3F7', tg_video: '#4FC3F7', call_me: '#1B7A34', torch: '#FFD54F', magnifier: '#FFD54F', where: '#1A1A1A', news: '#1A1A1A', music: '#FFA726', weather: '#4FC3F7', clock: '#FFFFFF', photos: '#6A1B9A', missed_calls: '#FFD54F', search: '#1A1A1A', url: '#1A1A1A',
    radio: '#FFA726', sos: '#C62828'};
  const typeColor = k => TYPE_COLOR[k] || '#1F4FA8';
  const OLD_COLORS = {'#0B6E4F': '#1B7A34', '#00695C': '#4FC3F7', '#B71C1C': '#C62828', '#A84300': '#FFA726', '#FFF3C4': '#FFD54F', '#FFE7A8': '#FFD54F',
    '#37474F': '#1A1A1A', '#0277BD': '#4FC3F7'};
  const SYM_PATHS = <?= SYM_JSON ?>;
  // Simboli del telefono (stessi tracciati di Symbols.kt, copiati da tools/symbols/gen.py): l'anteprima e la
  // scelta mostrano esattamente quello che vedrà il telefono.
  const SYM_UI = new Set(<?= SYM_UI_JSON ?>);
  const TYPE_SYM = {call: 'phone', wa_video: 'video', tg_video: 'video', sms: 'message', url: 'web', sos: 'alert', weather: 'weather', photos: 'photo',
    missed_calls: 'missed', search: 'search', messages: 'message', radio: 'radio', torch: 'torch', magnifier: 'magnifier', where: 'pin', call_me: 'callme',
    music: 'music', news: 'news', clock: 'clock'};
  const ROLE_SYM = {'@dialer': 'phone', '@sms': 'message', '@camera': 'camera', '@gallery': 'photo', '@browser': 'web'};
  function sym(name, cls) {
    const NS = 'http://www.w3.org/2000/svg', s = document.createElementNS(NS, 'svg');
    s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('class', 'sym' + (cls ? ' ' + cls : '')); s.setAttribute('aria-hidden', 'true');
    (SYM_PATHS[name] || SYM_PATHS.grid || []).forEach(d => { const p = document.createElementNS(NS, 'path'); p.setAttribute('d', d); s.append(p); });
    return s;
  }
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
      el('img', {src: '?thumb=' + h, alt: '', style: 'width:84px;height:84px;object-fit:cover;border-radius:12px;border:2px solid #1A1A1A'}),
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
  /** Scritta automatica del tipo ('' = da scrivere, es. il nome del familiare). */
  const typeLabel = (k, target) => ({photos: T('def_album'), missed_calls: T('def_missed'), search: T('def_search'), messages: T('def_messages_unread'),
    radio: T('def_radio'), sos: 'SOS', clock: '', app: APP_LABEL[target] || '', call_me: T('def_call_me'), torch: T('def_torch'), magnifier: T('def_magnifier'),
    where: T('def_where'), music: T('def_music'), news: T('def_news')})[k] ?? '';
  const AUTO_LABELS = new Set([T('def_album'), T('def_photos'), T('def_missed'), T('def_search'), T('def_messages_unread'), T('def_radio'), 'SOS',
    T('def_call_me'), T('def_torch'), T('def_magnifier'), T('def_where'), T('def_music'), T('def_news'),
    ...Object.values(APP_LABEL), ...Object.values(TYPES)]);
  const ICONS = Object.fromEntries(['auto', 'app', 'symbol', 'photo', 'none'].map(k => [k, T('icon_' + k)]));
  const SYMBOLS = Object.fromEntries(Object.keys(SYM_PATHS).filter(k => !SYM_UI.has(k)).map(k => [k, T('sym_' + k)]));
  /** Scelta del simbolo a griglia, con i disegni veri (un menu a tendina mostrerebbe solo i nomi). */
  function symPicker(value, onpick) {
    return el('div', {class: 'symgrid', role: 'radiogroup'}, Object.keys(SYMBOLS).map(k =>
      el('button', {type: 'button', class: 'symbtn', role: 'radio', 'aria-checked': String(k === value), title: SYMBOLS[k], 'aria-label': SYMBOLS[k], onclick: () => onpick(k)}, sym(k))));
  }
  const PRESETS = [['pill', 'preset_medicine'], ['shower', 'preset_shower'], ['cart', 'preset_shopping'], ['doctor', 'preset_visit'],
    ['cards', 'preset_cards'], ['walk', 'preset_walk'], ['water', 'preset_water'], ['food', 'preset_meal'], ['thermometer', 'preset_measure'],
    ['syringe', 'preset_insulin'], ['trash', 'preset_trash'], ['plant', 'preset_plants'], ['moon', 'preset_bed']];
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
  cfg.phone = Object.assign({ringerLock: false, ringVolumePercent: 100, batteryAlertPercent: 15, badges: true, smsUpdateCommand: false, freshStart: true, remoteAppAlert: true, scamGuard: true}, cfg.phone || {});
  // Colori predefiniti delle versioni vecchie → quelli nuovi più vicini (una volta, poi si pubblica).
  let recolored = 0;
  cfg.pages.forEach(pg => (pg.tiles || []).forEach(t => { const n = OLD_COLORS[(t.color || '').toUpperCase()]; if (n) { t.color = n; recolored++; } }));
  if (cfg.phone.homeButtonColor && OLD_COLORS[cfg.phone.homeButtonColor.toUpperCase()]) { cfg.phone.homeButtonColor = OLD_COLORS[cfg.phone.homeButtonColor.toUpperCase()]; recolored++; }
  if (recolored) { ta.value = JSON.stringify(cfg, null, 2); dirty = true; }
  if (dirty) document.getElementById('dirty').textContent = recolored ? T('recolored', recolored) : T('unpublished');

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
        if (['call', 'wa_video', 'tg_video', 'sms', 'call_me'].includes(t.type) && !(t.target || '').trim()) out.push(T('p_no_number', name(t)));
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
      old0130 = (cfg.phone.autoAnswer || cfg.phone.announceCaller) && !newer(app, '0.13.0'),
      old0140 = (cfg.pages.some(p => p.tiles.some(t => ['messages', 'radio'].includes(t.type) || (t.icon && /^media:/.test(t.icon.ref || ''))))
        || cfg.phone.nightQuiet || cfg.phone.chargeReminder || cfg.phone.holdToOpen || (cfg.phone.theme && cfg.phone.theme !== 'normal')) && !newer(app, '0.14.0'),
      old0170 = (cfg.pages.some(p => p.tiles.some(t => ['tg_video', 'call_me', 'torch', 'magnifier', 'where', 'music', 'news'].includes(t.type)))
        || ['blockPremium', 'blockInternational', 'simAlert', 'fallDetection', 'heatAlert', 'morningBrief'].some(k => cfg.phone[k])
        || cfg.phone.longCallMinutes || cfg.phone.inactivityHours || cfg.phone.safeZoneRadius || cfg.phone.callVolumePercent || cfg.phone.fontScale
        || (cfg.emergency && Object.values(cfg.emergency).some(v => v))) && !newer(app, '0.17.0');
    warn.hidden = !(app && (old090 || old0100 || old0101 || old0120 || old0130 || old0140 || old0170));
    warn.textContent = old090 ? T('old_app_warn', app || '?') : old0100 ? T('old_app_warn_0100', app || '?') : old0101 ? T('old_app_warn_0101', app || '?') : old0120 ? T('old_app_warn_0120', app || '?') : old0130 ? T('old_app_warn_0130', app || '?') : old0140 ? T('old_app_warn_0140', app || '?') : T('old_app_warn_0170', app || '?');
    const p = problems(), box = document.getElementById('issues');
    box.replaceChildren(...(p.length ? [el('b', {}, T('p_title')), el('ul', {}, p.map(x => el('li', {}, x)))] : []));
    document.getElementById('pubbtn').disabled = p.length > 0;
  }
  /** Icona dell'anteprima come sul telefono: foto caricata, simbolo scelto, o simbolo del tipo/app. */
  function tileIcon(t) {
    const ic = t.icon || {};
    if (ic.kind === 'none') return '';
    const m = /^media:([0-9a-f]{64})$/.exec(ic.ref || '');
    if (m && ic.kind !== 'symbol') return el('img', {class: 'ti', src: '?thumb=' + m[1], alt: ''});
    if (ic.kind === 'symbol' && SYM_PATHS[ic.ref]) return sym(ic.ref, 'ti');
    return sym((t.type === 'app' && ROLE_SYM[t.target]) || TYPE_SYM[t.type] || 'grid', 'ti');
  }
  function name(t) { return t.type === 'clock' ? T('clock') : (t.label || T('type_' + t.type) || t.type); }

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
      }, el('span', {}, tileIcon(t), el('span', {}, t.type === 'clock' ? T('clock') : t.type === 'weather' ? (t.label || T('type_weather')) : name(t)))));
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
        const phoneTypes = ['call', 'wa_video', 'tg_video', 'sms', 'call_me'];
        const keep = (phoneTypes.includes(t.type) && phoneTypes.includes(v)) || t.type === v;
        if (!keep) t.target = '';
        // Scritta e colore seguono il nuovo tipo, a meno che siano stati scelti a mano.
        const autoLabel = !t.label || AUTO_LABELS.has(t.label), oldC = (t.color || '').toUpperCase();
        const autoColor = !t.color || oldC === typeColor(t.type) || OLD_COLORS[oldC];
        t.type = v;
        if (autoLabel) t.label = typeLabel(v, t.target);
        if (autoColor || v === 'clock' || v === 'sos') t.color = typeColor(v);
        if (v === 'clock') delete t.label;
        if (v === 'search') { t.target = ''; t.icon = {kind: 'auto', ref: ''}; }
        if (v === 'messages') { t.target = ''; t.icon = {kind: 'auto', ref: ''}; }
        if (v === 'radio') { t.target = ''; t.stations = t.stations || [{name: '', url: ''}]; }
        if (v === 'missed_calls') { t.target = ''; t.icon = {kind: 'auto', ref: ''}; }
        if (v === 'sos') { t.label = 'SOS'; t.w = Math.min(2, p.cols); }
        if (['call', 'wa_video', 'tg_video', 'sms', 'call_me'].includes(v) && t.icon.kind === 'symbol') t.icon = {kind: 'auto', ref: ''};
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
    if (['call', 'wa_video', 'tg_video', 'sms', 'call_me'].includes(t.type)) {
      parts.push(field(T('phone_number'), el('input', {type: 'tel', value: t.target || '', placeholder: T('phone_placeholder'), onchange: e => upd('target', e.target.value.replace(/[^\d+]/g, ''))})));
      if (t.type === 'wa_video') parts.push(el('p', {class: 'hint'}, T('wa_hint')));
      if (t.type === 'tg_video') parts.push(el('p', {class: 'hint'}, T('tg_hint')));
      if (t.type === 'call_me') parts.push(el('p', {class: 'hint'}, T('call_me_hint')));
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
      if ((t.folders || []).length) parts.push(el('p', {class: 'msg'}, T('album_phone_folders', t.folders.length)));
      parts.push(el('label', {}, T('photos_label')), el('p', {class: 'hint'}, T('photos_hint')),
        el('div', {}, t.photos.map(ref => thumb(ref, () => { t.photos = t.photos.filter(x => x !== ref); changed(); render(); }))),
        field(T('upload_photos'), fileInput(true, refs => { t.photos = [...new Set(t.photos.concat(refs))]; changed(); render(); })));
    }
    if (t.type === 'search') parts.push(el('p', {class: 'hint'}, T('search_hint')));
    if (['torch', 'magnifier', 'where'].includes(t.type)) parts.push(el('p', {class: 'hint'}, T(t.type + '_hint')));
    if (t.type === 'music') parts.push(el('p', {class: (t.folders || []).length ? 'msg' : 'hint'}, (t.folders || []).length ? T('music_folders_n', t.folders.length) : T('music_hint')));
    if (t.type === 'news') {
      const NEWS = <?= json_encode(NEWS_FEEDS, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) ?>;
      parts.push(el('p', {class: 'hint'}, T('news_hint')),
        field(T('news_source'), select(Object.assign(Object.fromEntries(NEWS.map(([n, u]) => [u, n])), {'': T('news_other')}), NEWS.some(f => f[1] === t.target) ? t.target : '',
          v => { t.target = v; changed(); render(); })),
        field(T('news_url'), el('input', {type: 'url', value: t.target || '', placeholder: 'https://…/feed.xml', onchange: e => { t.target = e.target.value.trim(); changed(); }})));
    }
    if (t.type === 'messages') parts.push(el('p', {class: 'hint'}, T(FORM.dataset.edition === 'play' ? 'messages_hint_basic' : 'messages_hint') + (FORM.dataset.edition === 'standard' ? ' ' + T('restricted_hint') : '')));
    if (t.type === 'radio') {
      t.stations = t.stations || [];
      parts.push(el('p', {class: 'hint'}, T('radio_hint')));
      t.stations.forEach((st, i) => parts.push(el('div', {class: 'card', style: 'padding:10px;margin:8px 0'},
        field(T('radio_name'), el('input', {type: 'text', value: st.name || '', maxlength: '40', onchange: e => { st.name = e.target.value.trim(); changed(); }})),
        field(T('radio_url'), el('input', {type: 'url', value: st.url || '', placeholder: 'https://…', onchange: e => { st.url = e.target.value.trim(); changed(); issues(); }})),
        el('button', {type: 'button', class: 'small danger', onclick: () => { t.stations.splice(i, 1); changed(); render(); }}, T('radio_remove')))));
      parts.push(el('p', {}, el('button', {type: 'button', class: 'sec', onclick: () => { t.stations.push({name: '', url: ''}); changed(); render(); }}, T('radio_add'))));
    }
    if (t.type === 'missed_calls') {
      parts.push(el('p', {class: 'hint'}, T('missed_hint')));
      if (FORM.dataset.edition === 'play') parts.push(el('p', {class: 'msg warn'}, T('missed_hint_play')));
    }
    if (t.type === 'url') parts.push(field(T('address'), el('input', {type: 'url', value: t.target || '', placeholder: 'https://', onchange: e => upd('target', e.target.value.trim())})));
    if (t.type === 'shortcut') parts.push(field(T('shortcut'), el('input', {type: 'text', value: t.target || '', placeholder: T('shortcut_placeholder'), onchange: e => upd('target', e.target.value.trim())}), T('experts')));
    if (t.type !== 'clock') parts.push(field(T('tile_text'), el('input', {type: 'text', value: t.label || '', maxlength: '30', onchange: e => upd('label', e.target.value)})));

    if (!['clock', 'sos', 'weather', 'photos', 'missed_calls', 'messages', 'search', 'torch', 'magnifier', 'where', 'music', 'news'].includes(t.type)) {
      parts.push(field(T('icon'), select(ICONS, t.icon.kind || 'auto', v => { t.icon = {kind: v, ref: v === 'symbol' ? (t.icon.ref && SYMBOLS[t.icon.ref] ? t.icon.ref : 'phone') : (v === 'photo' || v === 'auto' ? /^(contact|media):/.test(t.icon.ref || '') ? t.icon.ref : '' : '')}; changed(); render(); })));
      if (t.icon.kind === 'symbol') parts.push(field(T('symbol'), symPicker(t.icon.ref || 'phone', v => { t.icon.ref = v; changed(); render(); })));
      // Foto del familiare caricata da qui: non serve averla nella rubrica del telefono.
      if (['call', 'wa_video', 'tg_video', 'sms', 'call_me'].includes(t.type)) {
        const media = (t.icon.ref || '').startsWith('media:') ? t.icon.ref : '';
        parts.push(el('label', {}, T('family_photo')),
          media ? thumb(media, () => { t.icon = {kind: 'auto', ref: '', fill: t.icon.fill}; if (!t.icon.fill) delete t.icon.fill; changed(); render(); }) : '',
          fileInput(false, refs => { t.icon = Object.assign({}, t.icon, {kind: 'photo', ref: refs[0]}); changed(); render(); }),
          el('div', {class: 'hint'}, T('family_photo_hint')));
      }
      if (t.icon.kind === 'photo' || (t.icon.kind === 'auto' && ['call', 'wa_video', 'tg_video', 'sms', 'call_me'].includes(t.type)))
        parts.push(el('p', {class: 'hint'}, T('photo_hint')),
          el('label', {style: 'font-weight:400'}, el('input', {type: 'checkbox', checked: !!t.icon.fill,
            onchange: e => { t.icon.fill = e.target.checked; if (!t.icon.fill) delete t.icon.fill; changed(); render(); }}), ' ' + T('photo_fill')),
          el('div', {class: 'hint'}, T('photo_fill_hint')));
    }

    if (cfg.phone.homeButton && ['app', 'shortcut', 'url', 'sms', 'wa_video', 'tg_video'].includes(t.type))
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
      field(T('sms_text'), el('input', {type: 'text', value: s.message || '', onchange: e => { s.message = e.target.value; changed(); }}), T('sms_text_hint')),
      // Scheda per i soccorritori: compare sul telefono nella schermata SOS dopo l'invio.
      el('h3', {style: 'margin-top:22px'}, 'ℹ ' + T('em_title')), el('p', {class: 'hint'}, T('em_hint')),
      ...(() => {
        const e = cfg.emergency = cfg.emergency || {};
        const f = (k, multi) => field(T('em_' + k), multi
          ? el('textarea', {rows: '2', maxlength: '400', onchange: ev => { e[k] = ev.target.value.trim(); changed(); }}, e[k] || '')
          : el('input', {type: 'text', maxlength: '120', value: e[k] || '', onchange: ev => { e[k] = ev.target.value.trim(); changed(); }}));
        return [el('div', {class: 'row'}, f('name'), f('birth')), f('conditions', true), f('medications', true), f('allergies', true),
          el('div', {class: 'row'}, f('bloodType'), f('doctor')), f('notes', true)];
      })());
  }

  // -------- promemoria
  const STOCK = JSON.parse(document.getElementById('pubform').dataset.stock || '{}');
  function renderRem() {
    const box = document.getElementById('t-rem');
    const items = cfg.reminders.slice().sort((a, b) => (a.time || '').localeCompare(b.time || '')).map(r => {
      const set = (k, v) => { r[k] = v; changed(); };
      const all = !r.days || !r.days.length;
      const oneDay = !!r.date;
      return el('div', {class: 'rem'},
        el('div', {style: 'display:flex;gap:6px;flex-wrap:wrap;margin-bottom:8px'}, PRESETS.map(([ic, key]) =>
          el('button', {type: 'button', class: 'small sec', onclick: () => { r.icon = ic; r.title = T(key); changed(); renderRem(); }}, sym(ic), T(key)))),
        el('div', {class: 'row'},
          field(T('hour'), el('input', {type: 'time', value: r.time || '08:00', onchange: e => { set('time', e.target.value); renderRem(); }})),
          field(T('rem_title'), el('input', {type: 'text', value: r.title || '', onchange: e => set('title', e.target.value)})),
          field(T('detail'), el('input', {type: 'text', value: r.detail || '', placeholder: T('detail_placeholder'), onchange: e => set('detail', e.target.value)}))),
        el('div', {class: 'row'},
          field(T('rem_icon'), symPicker(r.icon || 'pill', v => { set('icon', v); renderRem(); })),
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
        // Scorta di pastiglie: il telefono conta le «Presa» e avvisa quando stanno finendo.
        el('div', {class: 'row'},
          field(T('stock_now'), el('input', {type: 'number', min: '0', max: '999', value: r.stock >= 0 ? String(STOCK[r.id] ?? r.stock) : '', placeholder: T('stock_off'),
            onchange: e => { const v = e.target.value.trim(); if (v === '') { r.stock = -1; } else { r.stock = Math.max(0, +v); r.stockSetAt = Date.now(); } changed(); renderRem(); }}),
            r.stock >= 0 ? (STOCK[r.id] !== undefined ? T('stock_left', STOCK[r.id]) : T('stock_hint')) : T('stock_hint')),
          r.stock >= 0 ? field(T('stock_per'), select({1: '1', 2: '2', 3: '3', 4: '4'}, String(r.perDose || 1), v => set('perDose', +v))) : '',
          r.stock >= 0 ? field(T('stock_low'), select({3: '3', 5: '5', 7: '7', 10: '10', 14: '14'}, String(r.lowAt || 5), v => set('lowAt', +v))) : ''),
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
    const GRP_SYM = {grp_calls: 'phone', grp_aids: 'heart', grp_home: 'home', grp_battery: 'battery', grp_safety: 'alert', grp_comfort: 'moon', grp_remote: 'web'};
    const group = (k, ...kids) => el('div', {class: 'phgroup'}, el('h4', {}, sym(GRP_SYM[k] || 'grid'), ' ', T(k)), ...kids);
    box.replaceChildren(
      group('grp_calls',
      check('blockUnknown', T('block_unknown'), T('block_unknown_hint')),
      check('speakerCalls', T('speaker_calls'), T('speaker_calls_hint')),
      check('autoAnswer', T('auto_answer'), T('auto_answer_hint')),
      p.autoAnswer ? field(T('auto_answer_after'), select({5: '5 s', 10: '10 s', 15: '15 s', 20: '20 s', 30: '30 s'}, String(p.autoAnswerSeconds || 10), v => set('autoAnswerSeconds', +v))) : '',
      check('announceCaller', T('announce_caller'), T('announce_caller_hint')),
      check('nightQuiet', T('night_quiet'), T('night_quiet_hint')),
      p.nightQuiet ? el('div', {class: 'row', style: 'margin:0 0 12px 24px'},
        field(T('night_from'), select(Object.fromEntries([18, 19, 20, 21, 22, 23].map(h => [`${h}:00`, `${h}:00`])), p.nightFrom || '22:00', v => set('nightFrom', v))),
        field(T('night_to'), select(Object.fromEntries([5, 6, 7, 8, 9, 10].map(h => [`${String(h).padStart(2, '0')}:00`, `${h}:00`])), p.nightTo || '07:00', v => set('nightTo', v)))) : '',
      ),
      group('grp_aids',
      check('speakTiles', T('speak_tiles'), T('speak_tiles_hint')),
      check('voiceConfirm', T('voice_confirm'), T('voice_confirm_hint')),
      check('bedsideClock', T('bedside'), T('bedside_hint')),
      check('holdToOpen', T('hold_to_open'), T('hold_to_open_hint')),
      field(T('theme'), select({normal: T('theme_normal'), contrast: T('theme_contrast'), night: T('theme_night')}, p.theme || 'normal', v => set('theme', v))),
      field(T('app_icons'), select({original: T('app_icons_original'), mono: T('app_icons_mono')}, p.appIcons || 'original', v => set('appIcons', v)), T('app_icons_hint')),
      ),
      group('grp_home',
      check('homeButton', T('home_button'), T('home_button_hint')),
      p.homeButton ? el('div', {style: 'margin:0 0 12px 24px'},
        field(T('home_side'), select({right: T('home_side_right'), left: T('home_side_left')}, p.homeButtonSide || 'right', v => set('homeButtonSide', v))),
        el('label', {style: 'font-weight:400'}, el('input', {type: 'checkbox', checked: p.homeButtonLabel !== false, onchange: e => set('homeButtonLabel', e.target.checked)}), ' ' + T('home_label')),
        el('label', {}, T('home_color')),
        el('div', {class: 'swatches'}, PALETTE.map(([hex, nm]) => el('button', {type: 'button', class: 'sw', title: nm, 'aria-label': nm,
          'aria-pressed': String((p.homeButtonColor || '#1F4FA8').toUpperCase() === hex), style: 'background:' + hex, onclick: () => set('homeButtonColor', hex)})))) : '',
      check('freshStart', T('fresh_start'), T('fresh_start_hint')),
      check('badges', T('badges'), T('badges_hint') + (document.getElementById('pubform').dataset.edition === 'standard' ? ' ' + T('restricted_hint') : '')),
      ),
      group('grp_battery',
      check('chargeReminder', T('charge_reminder'), T('charge_reminder_hint')),
      p.chargeReminder ? el('div', {class: 'row', style: 'margin:0 0 12px 24px'},
        field(T('charge_time'), select(Object.fromEntries([17, 18, 19, 20, 21, 22, 23].map(h => [`${h}:00`, `${h}:00`])), p.chargeTime || '20:00', v => set('chargeTime', v))),
        field(T('charge_below'), select(Object.fromEntries([20, 30, 40, 50, 60].map(n => [n, n + '%'])), String(p.chargeBelow || 40), v => set('chargeBelow', +v)))) : '',
      check('ringerLock', T('ringer_lock'), T('ringer_lock_hint')),
      p.ringerLock ? field(T('min_volume'), select({30: '30%', 50: '50%', 70: '70%', 80: '80%', 90: '90%', 100: '100%'}, String(p.ringVolumePercent), v => set('ringVolumePercent', +v))) : '',
      field(T('battery_alert'), select(Object.assign({0: T('never_cap')}, ...[10, 15, 20, 25, 30].map(n => ({[n]: T('below_pct', n)}))), String(p.batteryAlertPercent), v => set('batteryAlertPercent', +v)),
        T('battery_alert_hint')),
      ),
      group('grp_safety',
        check('remoteAppAlert', T('remote_apps'), T('remote_apps_hint')),
        check('scamGuard', T('scam_guard'), T('scam_guard_hint')),
        check('blockPremium', T('block_premium'), T('block_premium_hint')),
        check('blockInternational', T('block_international'), T('block_international_hint')),
        field(T('long_call'), select({0: T('never_cap'), 5: '5 min', 10: '10 min', 15: '15 min', 20: '20 min', 30: '30 min'}, String(p.longCallMinutes || 0), v => set('longCallMinutes', +v)), T('long_call_hint')),
        check('simAlert', T('sim_alert'), T('sim_alert_hint')),
        field(T('inactive'), select({0: T('never_cap'), 4: '4 h', 6: '6 h', 8: '8 h', 12: '12 h'}, String(p.inactivityHours || 0), v => set('inactivityHours', +v)), T('inactive_hint')),
        check('fallDetection', T('fall'), T('fall_hint')),
        check('heatAlert', T('heat'), T('heat_hint')),
        p.heatAlert ? field(T('heat_from'), select(Object.fromEntries([28, 30, 32, 34, 36].map(n => [n, n + '°'])), String(p.heatThreshold || 32), v => set('heatThreshold', +v))) : '',
        el('h4', {style: 'margin-top:14px'}, sym('pin'), ' ', T('zone')),
        document.getElementById('pubform').dataset.edition === 'completo'
          ? el('div', {},
              el('p', {class: 'hint'}, T('zone_hint')),
              el('div', {class: 'row'},
                field(T('zone_radius'), select({0: T('never_cap'), 200: '200 m', 500: '500 m', 1000: '1 km', 2000: '2 km', 5000: '5 km'}, String(p.safeZoneRadius || 0), v => set('safeZoneRadius', +v))),
                field(T('zone_lat'), el('input', {type: 'number', step: '0.00001', value: p.safeZoneLat || '', onchange: e => set('safeZoneLat', +e.target.value || 0)})),
                field(T('zone_lon'), el('input', {type: 'number', step: '0.00001', value: p.safeZoneLon || '', onchange: e => set('safeZoneLon', +e.target.value || 0)}))),
              (() => { const ll = JSON.parse(document.getElementById('pubform').dataset.lastloc || 'null');
                return ll ? el('button', {type: 'button', class: 'small sec', onclick: () => { p.safeZoneLat = +ll.lat.toFixed(5); p.safeZoneLon = +ll.lon.toFixed(5); changed(); renderPhone(); }}, T('zone_use_last')) : el('p', {class: 'hint'}, T('zone_no_last')); })())
          : el('p', {class: 'hint'}, T('zone_full_only'))
      ),
      group('grp_comfort',
        field(T('call_volume'), select({0: T('unchanged'), 70: '70%', 80: '80%', 90: '90%', 100: '100%'}, String(p.callVolumePercent || 0), v => set('callVolumePercent', +v))),
        field(T('font_scale'), select({0: T('unchanged'), 1.15: '115%', 1.3: '130%', 1.5: '150%', 1.8: '180%'}, String(p.fontScale || 0), v => set('fontScale', +v)), T('font_scale_hint')),
        el('div', {class: 'row'},
          field(T('screen_timeout'), select({0: T('unchanged'), 60: '1 min', 120: '2 min', 300: '5 min', 600: '10 min', 1800: '30 min'}, String(p.screenTimeoutSeconds || 0), v => set('screenTimeoutSeconds', +v))),
          field(T('brightness'), select({0: T('unchanged'), 50: '50%', 70: '70%', 90: '90%', 100: '100%'}, String(p.brightnessPercent || 0), v => set('brightnessPercent', +v)))),
        check('morningBrief', T('morning'), T('morning_hint')),
        p.morningBrief ? field(T('morning_time'), select(Object.fromEntries([6, 7, 8, 9, 10, 11].map(h => [`${String(h).padStart(2, '0')}:00`, `${h}:00`])), p.morningTime || '09:00', v => set('morningTime', v))) : ''
      ),
      group('grp_remote',
      ...(['standard', 'play'].includes(document.getElementById('pubform').dataset.edition)
        ? [el('p', {class: 'msg warn'}, document.getElementById('pubform').dataset.edition === 'play' ? T('edition_warn', T('edition_play')) : T('edition_warn_standard'))]
        : []),
      check('smsUpdateCommand', T('sms_command'), T('sms_command_hint')),
      ),
    );
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
      ['messages', T('def_messages_unread'), false], ['search', T('def_search'), false], ['photos', T('def_album'), false], ['@browser', T('def_internet'), false],
    ].map(([k, label, on]) => ({k, label, box: el('input', {type: 'checkbox', checked: on})}));
    // Modelli per situazione (gli stessi del telefono): preimpostano app, opzioni e altezza dei riquadri.
    const MODELS = {
      custom: {rows: 5},
      autonomous: {rows: 5, apps: ['@dialer', '@sms', 'com.whatsapp', 'missed_calls', 'messages', '@camera', 'photos', 'search', '@browser'],
        opts: {homeButton: true}, phone: {holdToOpen: false, theme: 'normal'}},
      calls: {rows: 4, apps: ['missed_calls'], opts: {speakerCalls: true, speakTiles: true, autoAnswer: true, announceCaller: true, blockUnknown: true, bedsideClock: true, homeButton: false}},
      memory: {rows: 5, apps: ['missed_calls', 'photos'], opts: {speakerCalls: true, speakTiles: true, voiceConfirm: true, autoAnswer: true, announceCaller: true, blockUnknown: true, bedsideClock: true, homeButton: true},
        phone: {holdToOpen: true, nightQuiet: true, chargeReminder: true}},
      sight: {rows: 4, apps: ['@dialer', 'missed_calls', 'com.whatsapp'], opts: {speakerCalls: true, speakTiles: true, voiceConfirm: true, announceCaller: true, homeButton: true},
        phone: {theme: 'contrast', homeButtonLabel: true}},
      hearing: {rows: 5, apps: ['com.whatsapp', '@sms', 'messages', 'missed_calls', '@camera'], video: true, opts: {speakerCalls: true, speakTiles: false, announceCaller: false, homeButton: true}},
      tremor: {rows: 4, apps: ['@dialer', '@sms', 'com.whatsapp', 'missed_calls', '@camera'], opts: {speakerCalls: true, speakTiles: true, voiceConfirm: true, autoAnswer: true, announceCaller: true, homeButton: true},
        phone: {holdToOpen: true}},
    };
    let model = 'custom';
    const modelDesc = el('p', {class: 'hint'});
    const modelSel = select(Object.fromEntries(Object.keys(MODELS).map(k => [k, T('tpl_' + k)])), 'custom', v => {
      model = v; const m = MODELS[v];
      modelDesc.textContent = v === 'custom' ? '' : T('tpl_' + v + '_desc');
      if (m.apps) appsOpts.forEach(a => { a.box.checked = m.apps.includes(a.k); });
      if (m.opts) OPTS.forEach(o => { o.box.checked = !!m.opts[o.k]; });
      fam.forEach(f => { f.video.checked = !!m.video; });
    });
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
      field(T('tpl_situation'), modelSel), modelDesc,
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
        if (f.video) items.push({w: 1, t: {type: 'wa_video', target: f.num, label: T('wiz_video_of', f.name), icon: {kind: 'auto', ref: ''}, color: '#4FC3F7'}});
      });
      let place = null;
      if (city.value.trim()) {
        out.textContent = T('loading');
        place = (await searchPlace(city.value.trim()).catch(() => []))[0] || null;
        if (!place) { out.textContent = T('weather_no_results'); return; }
      }
      if (place) items.push({w: 1, t: {type: 'weather', target: place.target, label: place.name, color: '#4FC3F7'}});
      appsOpts.filter(a => a.box.checked).forEach(a => {
        if (a.k === 'missed_calls') items.push({w: 2, t: {type: 'missed_calls', target: '', label: T('def_missed'), color: '#FFD54F'}});
        else if (a.k === 'messages') items.push({w: 2, t: {type: 'messages', target: '', label: T('def_messages_unread'), color: '#1F4FA8'}});
        else if (a.k === 'search') items.push({w: 2, t: {type: 'search', target: '', label: T('def_search'), color: '#1A1A1A'}});
        else if (a.k === 'photos') items.push({w: 1, t: {type: 'photos', target: '', label: T('def_album'), photos: [], color: '#6A1B9A'}});
        else items.push({w: 1, t: {type: 'app', target: a.k, label: APP_LABEL[a.k] || a.label, icon: {kind: 'auto', ref: ''},
          color: ['@camera', '@gallery'].includes(a.k) ? '#6A1B9A' : a.k === 'com.whatsapp' ? '#1B7A34' : a.k === '@browser' ? '#1A1A1A' : '#1F4FA8'}});
      });
      // Ordine di importanza: familiari, chiamate perse, meteo, app, ricerca. Un riquadro largo
      // che capiterebbe a metà riga aspetta l'inizio della riga successiva, così non restano buchi.
      const prio = t => ({call: 0, wa_video: 0, tg_video: 0, missed_calls: 1, messages: 1, weather: 2, app: 3, photos: 4, search: 5})[t.type] ?? 3;
      items.sort((a, b) => prio(a.t) - prio(b.t));
      const ROWS = MODELS[model].rows, pages = [{cols: 2, rows: ROWS, tiles: [{id: 'clock', col: 0, row: 0, w: 2, h: 1, type: 'clock', color: '#FFFFFF'}]}];
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
      pages[0].tiles.push({id: 'sos', col: 0, row: ROWS - 1, w: 2, h: 1, type: 'sos', label: 'SOS', color: '#C62828'});
      cfg.pages = pages;
      cfg.sos.numbers = people.filter(f => f.sos).map(f => f.num);
      const rems = rem.filter(r => r.time.value).map(r => ({id: uid('r'), time: r.time.value, days: [],
        title: r.title.value.trim() || KINDS[r.kind], detail: '', icon: r.kind, snoozeMinutes: 10, repeatMinutes: 5, maxRepeats: 6, enabled: true}));
      if (rems.length) cfg.reminders = rems;
      OPTS.forEach(o => { cfg.phone[o.k] = o.box.checked; });
      if (cfg.phone.autoAnswer && !cfg.phone.autoAnswerSeconds) cfg.phone.autoAnswerSeconds = 10;
      Object.assign(cfg.phone, MODELS[model].phone || {});
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
