#!/usr/bin/env python3
"""
Genera dai tracciati di Symbols.kt (sorgente unica):
  - app/src/main/res/drawable/sym_<nome>.xml  (icone delle notifiche, tratto bianco)
  - app/src/main/java/app/semplicephone/ui/SymbolDrawables.kt  (nome -> R.drawable)
  - il blocco SYM_PATHS nel portale (tools/remote-server/index.php, tra /*SYM-BEGIN*/ e /*SYM-END*/)
Uso: python3 tools/symbols/gen.py [--check]   (--check: errore se qualcosa non è aggiornato)
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/java/app/semplicephone/ui/Symbols.kt'
DRAW = ROOT / 'app/src/main/res/drawable'
KT = ROOT / 'app/src/main/java/app/semplicephone/ui/SymbolDrawables.kt'
PHP = ROOT / 'tools/remote-server/index.php'

def symbols():
    s = SRC.read_text()
    start = s.index('private val paths: Map<String, List<String>> = mapOf(')
    body = s[start:s.index('\n    )\n', start)]
    out = {}
    for name, paths in re.findall(r'^\s+"([a-z_]+)" to listOf\((.*)\),$', body, re.M):
        out[name] = re.findall(r'"([^"]+)"', paths)
    return out

def drawable(paths):
    p = ''.join(f'''
    <path android:pathData="{d}" android:strokeColor="#FFFFFFFF" android:strokeWidth="2"
        android:strokeLineCap="round" android:strokeLineJoin="round" />''' for d in paths)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generato da tools/symbols/gen.py: non modificare a mano. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">{p}
</vector>
'''

def main(check):
    sym = symbols()
    files = {DRAW / f'sym_{n}.xml': drawable(p) for n, p in sym.items()}
    entries = '\n'.join(f'        "{n}" to R.drawable.sym_{n},' for n in sym)
    files[KT] = f'''package app.semplicephone.ui

import app.semplicephone.R

// Generato da tools/symbols/gen.py: non modificare a mano.
/** Simboli come risorse (icone delle notifiche, che non accettano un ImageVector). */
object SymbolDrawables {{
    private val ids = mapOf(
{entries}
    )

    fun get(name: String): Int = ids[name] ?: R.drawable.sym_grid
}}
'''
    php = PHP.read_text()
    block = '/*SYM-BEGIN*/ const SYM_PATHS = ' + json.dumps(sym, separators=(',', ':')) + '; /*SYM-END*/'
    php2 = re.sub(r'/\*SYM-BEGIN\*/.*?/\*SYM-END\*/', lambda m: block, php, flags=re.S)
    if php2 == php and block not in php: sys.exit('marcatori SYM-BEGIN/SYM-END mancanti in index.php')
    files[PHP] = php2
    stale = [f for f in DRAW.glob('sym_*.xml') if f not in files]
    bad = [str(f.relative_to(ROOT)) for f, c in files.items() if not f.exists() or f.read_text() != c] + [str(f.relative_to(ROOT)) for f in stale]
    if check:
        if bad: sys.exit('simboli non aggiornati (lancia tools/symbols/gen.py): ' + ', '.join(bad))
        print(f'simboli aggiornati ({len(sym)})'); return
    for f in stale: f.unlink()
    for f, c in files.items():
        if not f.exists() or f.read_text() != c: f.write_text(c)
    print(f'{len(sym)} simboli, {len(bad)} file scritti')

main('--check' in sys.argv)
