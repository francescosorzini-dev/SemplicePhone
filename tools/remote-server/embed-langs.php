<?php
/**
 * Incorpora i testi delle lingue (lang/*.json) in index.php, che così resta un file unico.
 * Controlla anche che ogni lingua abbia le stesse chiavi e gli stessi segnaposto dell'inglese.
 * Uso: php embed-langs.php <index.php da modificare> <cartella lang>
 */
declare(strict_types=1);
[$self, $target, $dir] = $argv + [null, null, null];
if (!$target || !$dir) { fwrite(STDERR, "Uso: php embed-langs.php index.php lang/\n"); exit(2); }
$langs = [];
foreach (glob("$dir/*.json") as $f) $langs[basename($f, '.json')] = json_decode((string)file_get_contents($f), true, 512, JSON_THROW_ON_ERROR);
if (!isset($langs['en'])) { fwrite(STDERR, "manca lang/en.json\n"); exit(1); }
$ph = function (string $s): array { preg_match_all('/\{\d\}/', $s, $m); $u = array_unique($m[0]); sort($u); return $u; };
$errors = [];
foreach ($langs as $code => $t) {
    $diff = array_merge(array_diff_key($langs['en'], $t), array_diff_key($t, $langs['en']));
    if ($diff) $errors[] = "$code: chiavi diverse: " . implode(', ', array_keys($diff));
    foreach ($langs['en'] as $k => $v) if (isset($t[$k]) && $ph($t[$k]) !== $ph($v)) $errors[] = "$code: segnaposto diversi in $k";
}
if ($errors) { fwrite(STDERR, implode("\n", $errors) . "\n"); exit(1); }
$src = (string)file_get_contents($target);
$out = str_replace('const LANG_DATA = null;', 'const LANG_DATA = ' . var_export($langs, true) . ';', $src, $n);
if ($n !== 1) { fwrite(STDERR, "segnaposto «const LANG_DATA = null;» non trovato\n"); exit(1); }
file_put_contents($target, $out);
echo 'Lingue incorporate: ' . implode(', ', array_keys($langs)) . "\n";
