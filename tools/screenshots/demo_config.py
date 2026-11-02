"""Configurazione dimostrativa per gli screenshot, con etichette nella lingua richiesta."""
import json, sys

L = {
    'it': dict(phone='Telefono', sms='Messaggi', camera='Fotocamera', photos='Foto', sos_msg='Ho bisogno di aiuto.',
               rem='Pastiglia della pressione', rem_detail='1 compressa dopo colazione'),
    'en': dict(phone='Phone', sms='Messages', camera='Camera', photos='Photos', sos_msg='I need help.',
               rem='Blood pressure pill', rem_detail='1 tablet after breakfast'),
    'de': dict(phone='Telefon', sms='Nachrichten', camera='Kamera', photos='Fotos', sos_msg='Ich brauche Hilfe.',
               rem='Blutdrucktablette', rem_detail='1 Tablette nach dem Frühstück'),
    'fr': dict(phone='Téléphone', sms='Messages', camera='Appareil photo', photos='Photos', sos_msg="J'ai besoin d'aide.",
               rem='Comprimé pour la tension', rem_detail='1 comprimé après le petit-déjeuner'),
    'es': dict(phone='Teléfono', sms='Mensajes', camera='Cámara', photos='Fotos', sos_msg='Necesito ayuda.',
               rem='Pastilla de la tensión', rem_detail='1 comprimido después del desayuno'),
}[sys.argv[1]]

sym = lambda r: {'kind': 'symbol', 'ref': r}
cfg = {
    'version': 1,
    'pages': [{'cols': 2, 'rows': 5, 'tiles': [
        {'id': 'clock', 'col': 0, 'row': 0, 'w': 2, 'h': 1, 'type': 'clock', 'color': '#FFFFFF'},
        {'id': 'maria', 'col': 0, 'row': 1, 'type': 'call', 'target': '+393331234567', 'label': 'Maria', 'icon': sym('person'), 'color': '#0B6E4F'},
        {'id': 'luca', 'col': 1, 'row': 1, 'type': 'call', 'target': '+393477654321', 'label': 'Luca', 'icon': sym('person'), 'color': '#00695C'},
        {'id': 'dialer', 'col': 0, 'row': 2, 'type': 'app', 'target': '@dialer', 'label': L['phone'], 'icon': sym('phone'), 'color': '#1F4FA8'},
        {'id': 'sms', 'col': 1, 'row': 2, 'type': 'app', 'target': '@sms', 'label': L['sms'], 'icon': sym('message'), 'color': '#1F4FA8'},
        {'id': 'camera', 'col': 0, 'row': 3, 'type': 'app', 'target': '@camera', 'label': L['camera'], 'icon': sym('camera'), 'color': '#6A1B9A'},
        {'id': 'gallery', 'col': 1, 'row': 3, 'type': 'app', 'target': '@gallery', 'label': L['photos'], 'icon': sym('photo'), 'color': '#6A1B9A'},
        {'id': 'sos', 'col': 0, 'row': 4, 'w': 2, 'h': 1, 'type': 'sos', 'label': 'SOS', 'color': '#B71C1C'},
    ]}],
    'sos': {'numbers': ['+393331234567', '+393477654321'], 'holdSeconds': 2, 'countdownSeconds': 10, 'message': L['sos_msg']},
    'reminders': [{'id': 'r1', 'time': '08:00', 'days': [], 'title': L['rem'], 'detail': L['rem_detail'],
                   'snoozeMinutes': 10, 'repeatMinutes': 5, 'maxRepeats': 6, 'enabled': True}],
    'phone': {'ringerLock': False, 'ringVolumePercent': 100, 'batteryAlertPercent': 15, 'badges': False, 'smsUpdateCommand': False},
}
print(json.dumps(cfg, ensure_ascii=False))
