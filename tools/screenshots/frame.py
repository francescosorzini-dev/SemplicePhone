"""Impagina gli screenshot per gli store: sfondo, didascalia e telefono, formato 9:16 (1080×1920).

python3 frame.py <cartella screenshot grezzi> <cartella fastlane/metadata/android>
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SRC, DEST = Path(sys.argv[1]), Path(sys.argv[2])
ROOT = Path(__file__).resolve().parents[2]
BOLD = str(ROOT / 'app/src/main/res/font/atkinson_bold.ttf')
LOCALES = {'it': 'it-IT', 'en': 'en-US', 'de': 'de-DE', 'fr': 'fr-FR', 'es': 'es-ES'}
SHOTS = ['1_home', '2_sos', '3_reminder', '4_editor', '5_tile']
COLORS = {'1_home': ('#1F4FA8', '#163A7D'), '2_sos': ('#B71C1C', '#7F1313'), '3_reminder': ('#1B6E34', '#124A24'),
          '4_editor': ('#6A1B9A', '#4A126C'), '5_tile': ('#00695C', '#00473E')}
CAPTIONS = {
    'it': ['Pochi riquadri grandi e chiari', 'SOS: chiamata e posizione ai familiari', 'Promemoria dei farmaci a tutto schermo',
           'Ogni riquadro si personalizza', 'Chiamate, app, WhatsApp, siti, SOS'],
    'en': ['A few big, clear tiles', 'SOS: call and location to family', 'Full-screen medication reminders',
           'Every tile is customizable', 'Calls, apps, WhatsApp, websites, SOS'],
    'de': ['Wenige große, klare Kacheln', 'SOS: Anruf und Standort an die Familie', 'Medikamenten-Erinnerung im Vollbild',
           'Jede Kachel ist anpassbar', 'Anrufe, Apps, WhatsApp, Webseiten, SOS'],
    'fr': ['Quelques grandes tuiles claires', 'SOS : appel et position à la famille', 'Rappels de médicaments en plein écran',
           'Chaque tuile est personnalisable', 'Appels, applis, WhatsApp, sites, SOS'],
    'es': ['Pocos mosaicos grandes y claros', 'SOS: llamada y ubicación a la familia', 'Recordatorios de medicamentos a pantalla completa',
           'Cada mosaico se personaliza', 'Llamadas, apps, WhatsApp, webs, SOS'],
}
W, H, S = 1080, 1920, 2  # disegno a 2× e riduco alla fine per bordi morbidi


def gradient(top, bottom):
    t, b = Image.new('RGB', (1, 2)), None
    t.putpixel((0, 0), Image.new('RGB', (1, 1), top).getpixel((0, 0)))
    t.putpixel((0, 1), Image.new('RGB', (1, 1), bottom).getpixel((0, 0)))
    return t.resize((W * S, H * S), Image.BILINEAR)


def wrap(draw, text, font, width):
    words, lines, cur = text.split(), [], ''
    for w in words:
        test = (cur + ' ' + w).strip()
        if draw.textlength(test, font=font) <= width or not cur:
            cur = test
        else:
            lines.append(cur); cur = w
    return lines + [cur]


def frame(shot_path, caption, colors):
    img = gradient(*colors)
    d = ImageDraw.Draw(img)
    # Didascalia: fino a 2 righe, il carattere si riduce se serve.
    for size in range(84, 50, -4):
        font = ImageFont.truetype(BOLD, size * S)
        lines = wrap(d, caption, font, (W - 140) * S)
        if len(lines) <= 2:
            break
    y = 110 * S
    for line in lines:
        tw = d.textlength(line, font=font)
        d.text(((W * S - tw) / 2, y), line, font=font, fill='white')
        y += int(size * 1.22 * S)
    # Telefono: cornice scura arrotondata con lo screenshot dentro.
    top = max(y + 50 * S, 330 * S)
    ph_h = H * S - top - 70 * S
    shot = Image.open(shot_path).convert('RGB')
    sc_h = ph_h - 2 * 22 * S
    sc_w = int(shot.width * sc_h / shot.height)
    ph_w = sc_w + 2 * 22 * S
    x = (W * S - ph_w) // 2
    shadow = Image.new('L', img.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle([x + 10 * S, top + 24 * S, x + ph_w + 10 * S, top + ph_h + 24 * S], 70 * S, fill=110)
    img.paste((0, 0, 0), mask=shadow.filter(ImageFilter.GaussianBlur(30 * S)))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([x, top, x + ph_w, top + ph_h], 70 * S, fill='#121212')
    shot = shot.resize((sc_w, sc_h), Image.LANCZOS)
    mask = Image.new('L', shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, sc_w, sc_h], 50 * S, fill=255)
    img.paste(shot, (x + 22 * S, top + 22 * S), mask)
    return img.resize((W, H), Image.LANCZOS)


for lang, folder in LOCALES.items():
    out = DEST / folder / 'images' / 'phoneScreenshots'
    out.mkdir(parents=True, exist_ok=True)
    for i, name in enumerate(SHOTS):
        src = SRC / f'{lang}_{name}.png'
        if not src.exists():
            print('manca', src); continue
        frame(src, CAPTIONS[lang][i], COLORS[name]).save(out / f'{name}.png', optimize=True)
    print(lang, 'ok')
