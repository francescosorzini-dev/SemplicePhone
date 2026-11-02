"""Tocca un elemento trovato nell'albero di uiautomator per testo o descrizione.

python3 tap.py [--long MS] [--regex] TESTO [INDICE]
"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

args = sys.argv[1:]
long_ms = None
regex = False
while args and args[0].startswith('--'):
    if args[0] == '--long': long_ms = args[1]; args = args[2:]
    elif args[0] == '--regex': regex = True; args = args[1:]
want, idx = args[0], int(args[1]) if len(args) > 1 else 0

def dump():
    for _ in range(5):
        subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml'], capture_output=True)
        out = subprocess.run(['adb', 'shell', 'cat', '/sdcard/ui.xml'], capture_output=True, text=True).stdout
        if '<hierarchy' in out:
            return ET.fromstring(out[out.index('<hierarchy'):])
        time.sleep(1)
    sys.exit('uiautomator dump non riuscito')

def match(n):
    vals = [n.get('text') or '', n.get('content-desc') or '']
    return any(re.search(want, v) for v in vals) if regex else want in vals

for attempt in range(10):
    hits = [n for n in dump().iter('node') if match(n)]
    if len(hits) > idx:
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', hits[idx].get('bounds')))
        x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
        cmd = ['input', 'swipe', x, y, x, y, long_ms] if long_ms else ['input', 'tap', x, y]
        subprocess.run(['adb', 'shell', *cmd])
        print(f'{want!r} -> {x},{y}')
        sys.exit(0)
    time.sleep(1)
sys.exit(f'non trovato: {want!r}')
