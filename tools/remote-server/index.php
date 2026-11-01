<?php
/**
 * SemplicePhone – server della configurazione remota (single file, PHP 8.1+ con ext-sodium).
 *
 * Telefono:  GET  index.php?device=<id>            → busta firmata {v, payload, sig}
 *            POST index.php?device=<id>&report=1   → stato (batteria, esiti promemoria)
 *            Header X-Device-Secret: segreto del telefono, registrato al primo contatto (TOFU).
 * Gestione:  index.php senza parametri → pannello con login.
 *
 * Dati in ./data (protetta da .htaccess). La chiave privata Ed25519 non esce mai dal server.
 */
declare(strict_types=1);

const DATA = __DIR__ . '/data';
const PBKDF2_ITER = 60000;
const MAX_REPORT = 262144;
const TILE_TYPES = ['app', 'call', 'wa_video', 'sms', 'url', 'shortcut', 'clock', 'sos'];

// ---------------------------------------------------------------- bootstrap
if (!extension_loaded('sodium')) { http_response_code(500); exit('ext-sodium mancante'); }
foreach ([DATA, DATA . '/devices', DATA . '/reports'] as $d) if (!is_dir($d)) mkdir($d, 0700, true);
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

    if (isset($_GET['report']) && $_SERVER['REQUEST_METHOD'] === 'POST') {
        $body = file_get_contents('php://input', false, null, 0, MAX_REPORT + 1);
        if ($body === false || strlen($body) > MAX_REPORT) { http_response_code(413); exit('{"error":"size"}'); }
        $r = json_decode($body, true);
        if (!is_array($r)) { http_response_code(400); exit('{"error":"json"}'); }
        $r['receivedAt'] = time();
        file_put_contents(DATA . "/reports/$id.jsonl", json_encode($r, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . "\n", FILE_APPEND | LOCK_EX);
        $meta['battery'] = $r['battery'] ?? null;
        $meta['charging'] = $r['charging'] ?? null;
        $meta['appliedSeq'] = $r['seq'] ?? null;
        $meta['appVersion'] = $r['appVersion'] ?? null;
        $meta['lastResult'] = $r['lastResult'] ?? null;
        write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
        http_response_code(204);
        exit;
    }

    write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
    $env = dev_path($id, 'envelope.json');
    if (!is_file($env)) { http_response_code(404); exit('{"error":"none"}'); }
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

/** Firma e pubblica: seq monotono, payload = byte esatti firmati. */
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
    return $seq;
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
  "sos": { "numbers": ["112"], "holdSeconds": 3, "countdownSeconds": 5, "message": "Ho bisogno di aiuto." },
  "reminders": []
}
JSON;

// ---------------------------------------------------------------- pannello di gestione
session_set_cookie_params(['httponly' => true, 'samesite' => 'Strict', 'secure' => !empty($_SERVER['HTTPS'])]);
session_start();
header('X-Frame-Options: DENY');
header("Content-Security-Policy: default-src 'self'; style-src 'unsafe-inline'");
$adminFile = DATA . '/admin.json';
$admin = read_json($adminFile);
$csrf = $_SESSION['csrf'] ??= bin2hex(random_bytes(16));
$msg = ''; $err = [];

function h($s): string { return htmlspecialchars((string)$s, ENT_QUOTES); }
function check_csrf(string $csrf): void { if (!hash_equals($csrf, (string)($_POST['csrf'] ?? ''))) { http_response_code(400); exit('CSRF'); } }

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    check_csrf($csrf);
    $act = $_POST['act'] ?? '';
    if ($act === 'setup' && !$admin) {
        $pw = (string)($_POST['password'] ?? '');
        if (strlen($pw) < 10) $err[] = 'Password di almeno 10 caratteri';
        else { write_atomic($adminFile, json_encode(['hash' => password_hash($pw, PASSWORD_DEFAULT)])); $_SESSION['ok'] = true; header('Location: ?'); exit; }
    } elseif ($act === 'login' && $admin) {
        usleep(300000);
        if (password_verify((string)($_POST['password'] ?? ''), $admin['hash'])) { session_regenerate_id(true); $_SESSION['ok'] = true; header('Location: ?'); exit; }
        $err[] = 'Password errata';
    } elseif ($act === 'logout') {
        session_destroy(); header('Location: ?'); exit;
    } elseif (!empty($_SESSION['ok'])) {
        $id = (string)($_POST['id'] ?? '');
        if (!valid_id($id)) $err[] = 'ID dispositivo non valido (6–32 caratteri a-z, 0-9)';
        elseif ($act === 'publish') {
            $src = (string)($_POST['config'] ?? '');
            $cfg = json_decode($src);
            if (json_last_error() !== JSON_ERROR_NONE) $err[] = 'JSON non valido: ' . json_last_error_msg();
            else $err = validate_config($cfg);
            $pin = trim((string)($_POST['pin'] ?? ''));
            if ($pin !== '' && !preg_match('/^\d{4,8}$/', $pin)) $err[] = 'Il PIN deve avere da 4 a 8 cifre';
            if (!$err) {
                write_atomic(dev_path($id, 'config.json'), json_encode($cfg, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
                $meta = read_json(dev_path($id, 'meta.json'), []);
                $meta['name'] = trim((string)($_POST['name'] ?? ($meta['name'] ?? '')));
                write_atomic(dev_path($id, 'meta.json'), json_encode($meta));
                $seq = publish($id, $cfg, $pin !== '' ? $pin : null);
                $msg = "Pubblicata versione $seq per $id. Il telefono la applica al prossimo controllo (o con «Aggiorna ora»).";
            }
        } elseif ($act === 'forget') {
            @unlink(dev_path($id, 'secret'));
            $msg = "Segreto di $id dimenticato: il prossimo telefono che si presenta con questo ID viene registrato.";
        }
    }
}

$logged = !empty($_SESSION['ok']);
$edit = $logged && isset($_GET['edit']) && valid_id((string)$_GET['edit']) ? (string)$_GET['edit'] : null;
?><!doctype html>
<html lang="it"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SemplicePhone – gestione</title>
<style>
body{font:16px/1.45 system-ui,sans-serif;margin:0;background:#F7F5F0;color:#1A1A1A}
main{max-width:980px;margin:0 auto;padding:16px}
h1{font-size:24px}h2{font-size:19px;margin-top:28px}
.card{background:#fff;border:1px solid #d9d3c7;border-radius:12px;padding:14px;margin:10px 0}
input,textarea,button{font:inherit}input[type=text],input[type=password]{padding:10px;border:2px solid #1A1A1A;border-radius:8px;width:100%;box-sizing:border-box}
textarea{width:100%;box-sizing:border-box;min-height:460px;font:13px/1.4 ui-monospace,monospace;border:2px solid #1A1A1A;border-radius:8px;padding:10px}
button{background:#1A1A1A;color:#fff;border:0;border-radius:10px;padding:11px 18px;min-height:44px;cursor:pointer}
button.sec{background:#fff;color:#1A1A1A;border:2px solid #1A1A1A}
.ok{background:#E3F1E7;border-color:#1B6E34}.err{background:#FCE4E4;border-color:#B71C1C;color:#7a1010}
code{background:#eee;padding:2px 5px;border-radius:4px;word-break:break-all}
table{border-collapse:collapse;width:100%}td,th{text-align:left;padding:8px;border-bottom:1px solid #e5e0d6;vertical-align:top}
.row{display:flex;gap:10px;flex-wrap:wrap}.row>*{flex:1 1 220px}
a{color:#1F4FA8}
</style></head><body><main>
<h1>SemplicePhone – gestione remota</h1>
<?php if ($msg): ?><div class="card ok"><?= h($msg) ?></div><?php endif; ?>
<?php foreach ($err as $e): ?><div class="card err"><?= h($e) ?></div><?php endforeach; ?>

<?php if (!$admin): ?>
  <form method="post" class="card"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="setup">
    <p>Primo avvio: scegli la password del pannello.</p>
    <input type="password" name="password" autocomplete="new-password" required minlength="10" aria-label="Password"><p><button>Imposta password</button></p>
  </form>
<?php elseif (!$logged): ?>
  <form method="post" class="card"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="login">
    <label>Password<br><input type="password" name="password" autocomplete="current-password" required></label><p><button>Entra</button></p>
  </form>
<?php else: ?>
  <form method="post" style="float:right"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="logout"><button class="sec">Esci</button></form>
  <div class="card">
    <strong>Chiave pubblica</strong> (da inserire nel telefono, Impostazioni › Gestione da remoto):<br><code><?= h(public_key_b64()) ?></code><br>
    <strong>Indirizzo</strong>: <code><?= h((!empty($_SERVER['HTTPS']) ? 'https' : 'http') . '://' . ($_SERVER['HTTP_HOST'] ?? 'host') . strtok($_SERVER['REQUEST_URI'] ?? '/', '?')) ?>?device={device}</code>
  </div>

<?php if ($edit):
    $meta = read_json(dev_path($edit, 'meta.json'), []);
    $src = $_POST['config'] ?? (is_file(dev_path($edit, 'config.json')) ? file_get_contents(dev_path($edit, 'config.json')) : DEFAULT_CONFIG);
?>
  <p><a href="?">← Tutti i telefoni</a></p>
  <h2>Telefono <code><?= h($edit) ?></code></h2>
  <form method="post"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="publish"><input type="hidden" name="id" value="<?= h($edit) ?>">
    <div class="row">
      <label>Nome (solo per te)<br><input type="text" name="name" value="<?= h($meta['name'] ?? '') ?>"></label>
      <label>Nuovo PIN impostazioni (vuoto = invariato)<br><input type="text" name="pin" inputmode="numeric" pattern="\d{4,8}" autocomplete="off"></label>
    </div>
    <p><label>Configurazione (JSON, stesso formato dell'app)<br><textarea name="config" spellcheck="false"><?= h($src) ?></textarea></label></p>
    <p><button>Firma e pubblica</button></p>
  </form>
  <?php
    $lines = is_file(DATA . "/reports/$edit.jsonl") ? array_slice(file(DATA . "/reports/$edit.jsonl", FILE_IGNORE_NEW_LINES), -20) : [];
    $events = [];
    foreach ($lines as $l) foreach ((json_decode($l, true)['reminderEvents'] ?? []) as $ev) $events[] = $ev;
    $events = array_slice(array_reverse($events), 0, 30);
  ?>
  <h2>Promemoria recenti</h2>
  <?php if (!$events): ?><p>Nessun evento ricevuto.</p><?php else: ?>
  <table><tr><th>Quando</th><th>Promemoria</th><th>Esito</th></tr>
  <?php foreach ($events as $ev): ?><tr><td><?= h(date('d/m H:i', (int)(($ev['at'] ?? 0) / 1000))) ?></td><td><?= h($ev['id'] ?? '') ?></td><td><?= h(str_replace('_', ' ', $ev['outcome'] ?? '')) ?></td></tr><?php endforeach; ?>
  </table><?php endif; ?>
  <form method="post" class="card"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="act" value="forget"><input type="hidden" name="id" value="<?= h($edit) ?>">
    Telefono reinstallato o sostituito? <button class="sec">Dimentica il segreto registrato</button>
  </form>

<?php else:
    $ids = [];
    foreach (glob(DATA . '/devices/*.{secret,meta.json,config.json}', GLOB_BRACE) ?: [] as $f) $ids[explode('.', basename($f))[0]] = true;
    ksort($ids);
?>
  <h2>Telefoni</h2>
  <?php if (!$ids): ?><p>Nessun telefono ancora. Attiva la gestione da remoto sul telefono e premi «Aggiorna ora»: comparirà qui col suo ID.</p><?php else: ?>
  <table><tr><th>ID</th><th>Nome</th><th>Ultimo contatto</th><th>Batteria</th><th>Versione pubblicata / applicata</th></tr>
  <?php foreach (array_keys($ids) as $id): $m = read_json(dev_path($id, 'meta.json'), []); ?>
    <tr><td><a href="?edit=<?= h($id) ?>"><code><?= h($id) ?></code></a></td><td><?= h($m['name'] ?? '') ?></td>
    <td><?= isset($m['lastSeen']) ? h(date('d/m/Y H:i', (int)$m['lastSeen'])) : '—' ?></td>
    <td><?= isset($m['battery']) && $m['battery'] >= 0 ? h($m['battery'] . '%' . (!empty($m['charging']) ? ' ⚡' : '')) : '—' ?></td>
    <td><?= h(($m['seq'] ?? '—') . ' / ' . ($m['appliedSeq'] ?? '—')) ?></td></tr>
  <?php endforeach; ?></table><?php endif; ?>
  <form method="get" class="card"><label>Prepara un telefono per ID<br><input type="text" name="edit" pattern="[a-z0-9]{6,32}" required></label><p><button>Apri</button></p></form>
<?php endif; endif; ?>
</main></body></html>
