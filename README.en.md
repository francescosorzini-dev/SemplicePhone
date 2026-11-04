# SemplicePhone

*[Versione italiana](README.md)*

An Android launcher for seniors. The Home screen is made only of large tiles, and every tile can
be configured: app, call, WhatsApp video call, SMS, link, shortcut, clock, SOS. The configuration
can also be managed remotely from a self-hosted portal, with Ed25519 signatures.

- Kotlin + Jetpack Compose, minSdk 29 / targetSdk 36, Atkinson Hyperlegible font (OFL)
- Languages: English, Italian, German, French, Spanish. The app follows the phone's language;
  Android 13+ can set a language just for SemplicePhone. See [TRANSLATING.md](TRANSLATING.md).

## Download

| Edition | Download | Install |
|---|---|---|
| **Standard** | [SemplicePhone.apk](../../releases/latest/download/SemplicePhone.apk) | from the phone's browser |
| **Full** (unread badges, `#UPDATE` SMS command, silent updates) | [SemplicePhone-completo.apk](../../releases/latest/download/SemplicePhone-completo.apk) | with ADB |

Google Play and F-Droid builds are prepared (see [`tools/play`](tools/play) and
[`tools/fdroid`](tools/fdroid)). All editions share the package name and signing key, so you can
install one over another without losing settings.

Google Play Protect may warn about apps downloaded from the web. The standard edition
avoids the permissions it blocks. If Android says *"For your security, this setting is currently
unavailable"*, open App info › ⋮ › *Allow restricted settings*, or run
`adb shell appops set app.semplicephone ACCESS_RESTRICTED_SETTINGS allow`.

## First setup

1. Install, open SemplicePhone and make it the Home app.
2. Tap the clock **5 times** and choose a PIN.
3. Set up tiles, SOS numbers and reminders, then press **Save**.

Optional: `adb shell dpm set-device-owner app.semplicephone/.admin.AdminReceiver` on a freshly
reset phone unlocks the locked notification shade, install blocking and factory-reset protection.

## Features

- **SOS**: long press → cancellable countdown → SMS with location to all numbers → call to the
  first one. The Play edition opens Messages ready to send instead of sending SMS by itself.
- **Reminders** for medicines and activities (shower, shopping, appointments, card games…), recurring
  or on one date, with symbol and photo: full-screen alarm even on the lock screen, repeats until confirmed.
- **Calls**: block unknown numbers (system call screening), automatic speakerphone at full volume.
- **Voice**: long press reads a tile aloud; optional spoken confirmation before calling or opening.
- **Weather** tile (Open-Meteo, no account), **family photos** tile, **bedside clock** while charging.
- **Missed calls** tile: who called and was not called back, one tap to call back. **"Home" button** over other apps to get back to the launcher.
- **"Where is it?"**: the family can ask for the phone location from the portal, if allowed on the phone.
- **Apps from the start**: tapping a tile opens the app on its first screen (chat list, messages…), not the last page viewed (phone-wide setting, overridable per tile). Contact photos can fill the whole tile.
- **Protected ringer**, **low-battery alert**, **PIN-protected settings**.
- **Remote management**: a single-file PHP portal ([`tools/remote-server`](tools/remote-server))
  with a visual editor (it also sees changes made on the phone, photos included), Telegram/email alerts, a weekly summary and version history.

## License

Free software, Copyright (C) 2026 Francesco Sorzini. App: [GPL-3.0-or-later](LICENSE); portal:
[AGPL-3.0-or-later](tools/remote-server/LICENSE). See [`NOTICE.md`](NOTICE.md). The SOS button
contacts the numbers you set: it does not replace emergency services.
