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
- **Medication reminders**: full-screen alarm even on the lock screen, "TAKEN" / snooze, repeats
  until confirmed.
- **Protected ringer**, **low-battery alert**, **PIN-protected settings**.
- **Remote management**: a single-file PHP portal ([`tools/remote-server`](tools/remote-server))
  with a visual editor, Telegram/email alerts and version history.

## License

Free software, Copyright (C) 2026 Francesco Sorzini. App: [GPL-3.0-or-later](LICENSE); portal:
[AGPL-3.0-or-later](tools/remote-server/LICENSE). See [`NOTICE.md`](NOTICE.md). The SOS button
contacts the numbers you set: it does not replace emergency services.
