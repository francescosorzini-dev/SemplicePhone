# SemplicePhone

**The simple phone for grandparents.** An Android launcher with a few big tiles, SOS, reminders
and a web page where the family sets everything up from afar. Free, no ads, free software.

[![Latest release](https://img.shields.io/github/v/release/francescosorzini-dev/SemplicePhone?label=release)](../../releases/latest)
[![Build](https://github.com/francescosorzini-dev/SemplicePhone/actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)
[![License GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Languages](https://img.shields.io/badge/languages-en%20%C2%B7%20it%20%C2%B7%20de%20%C2%B7%20fr%20%C2%B7%20es-orange)

*[Versione italiana](README.md)*

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_home.png" width="260" alt="Home with big tiles, weather and missed calls">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_reminder.png" width="260" alt="Full-screen reminder">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6_home_button.png" width="260" alt="Home button over other apps">
</p>

**[⬇ Download the APK](../../releases/latest/download/SemplicePhone.apk)** · [How to install](#download-and-install) · [Family portal](#for-the-family-the-portal) · [Privacy](PRIVACY.md)

## For the person using it

- **A few big, clear tiles.** Each tile does one thing: call Maria, open WhatsApp, send an SMS,
  open a website. Large text, strong colours and a highly legible font.
- **SOS.** Just hold it: a cancellable countdown starts, then a call to the first family member
  and an SMS with the location to everyone.
- **Reminders for medicines and activities** (medicine, shower, shopping, appointments, card
  games…): full-screen alarm even on the lock screen, with a photo of the pill. It rings until
  someone presses "TAKEN" or "DONE".
- **Missed calls.** The tile shows who called and was not called back: tap the name to call back.
- **No getting lost.** A "HOME" button stays visible inside other apps: one tap and you are back.
  Apps open on their first screen, not on the last page left half-way.
- **Search apps.** A tile opens search inside the launcher: type or say part of the name (even
  the initials, "gm" for Google Maps) and one tap opens the app.
- **Voice.** A long press reads a tile aloud, or the time on the clock. Before calling, the phone
  says "Calling Maria".
- **Weather, family photos and a bedside clock** while charging.
- **Safer, easier calls.** Unknown numbers can be blocked: only contacts and family get through.
  Automatic speakerphone, the phone says who is calling and, if a family member calls and nobody
  answers, it answers by itself on speakerphone (for someone who has fallen or cannot find the phone).
- **Messages from the family** full screen and read aloud, until someone taps "GOT IT".
- **Unread messages.** A tile lists unread SMS and WhatsApp messages: a tap opens the
  conversation, a long press reads it aloud.
- **WhatsApp and Telegram video calls** with one tap on the family member's photo.
- **Tools**: torch, magnifier (camera with zoom and freeze), "Where am I?" read aloud, "Call me?" to a
  family member, music from the phone's folders, news read aloud.
- **Against scams**: alert when a remote-control app is installed (AnyDesk, TeamViewer…), links from
  strangers flagged and not openable, premium and foreign calls blocked, alert on long calls with strangers.
- **Safety**: fall detection with "Are you OK?" and adjustable sensitivity, alerts for SIM removed and phone unused, extreme heat,
  safe zone (Full edition), rescuers' card on the SOS screen.
- **Comfort**: voice replies to family messages, pill stock, spoken good morning, loud calls, large text
  in all apps.
- **Album.** Uploaded photos plus the phone's folders (Camera, WhatsApp) change by themselves; a tap on a
  video opens it full screen, loud, with a single button to close.
- **Radio.** A tile with favourite stations: huge PLAY/STOP buttons, next station, volume.
- **Quiet night.** At night the phone does not ring, except for SOS numbers. In the evening, if the
  battery is low, it reminds aloud to put it on charge.
- **Against accidental touches**: tiles can be set to open only when held. High-contrast theme, or
  dark in the evening.
- **One way to use everything.** Every large screen has its title with symbol at the top and CLOSE
  at the bottom; colours have a meaning (red only for SOS, green for "yes", orange for "careful");
  purpose-drawn symbols instead of emoji, the same on the phone and in the portal. Optionally,
  single-colour app icons in the tile colour.
- **Backup** of the settings on the phone, exportable to a file and importable on a new phone, even
  without the portal.
- **Android's own SOS**: on many phones pressing the power button 5 times calls emergency services
  (Settings › Safety & emergency › Emergency SOS). Worth turning on.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_sos.png" width="200" alt="SOS countdown">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_editor.png" width="200" alt="Screen editor">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5_tile.png" width="200" alt="Editing a tile">
</p>

## For the family: the portal

A single PHP file for your own web space ([`tools/remote-server`](tools/remote-server)). From
there you set up tiles, SOS and reminders with a visual editor and publish them to the phone.
Changes are signed: the phone only accepts those from its own portal.

<p align="center"><img src="docs/images/portal-en.png" width="720" alt="Portal editor"></p>

- **Alerts** on Telegram or email: SOS with location, unconfirmed reminder, low battery, phone
  out of contact, blocked calls.
- **Family photos** on call tiles, uploaded from the portal.
- **Video messages**: a short video from the portal appears full screen and starts by itself until "SEEN IT"
  is tapped. If the file is too big for the hosting, the browser shrinks it before sending.
- **Ready-made layouts by situation** (independent, calls only, memory difficulties, poor eyesight, hard of
  hearing, shaky hands), on the phone the first time and in the guided setup.
- **Family calendar**: appointments from a Google, Outlook or iCloud calendar (iCal address) become
  reminders on the phone, also the evening before.
- **Guided setup**: a few questions (who they call, what they need, which medicines) and the screen is ready.
- **Message to the phone** (full screen, read aloud, with read receipt) and **"Ring the phone"** at full
  volume even on silent, to find it at home.
- **"Where is it?"**: phone location on request, only if allowed on the phone.
- **Weekly summary** every Monday and a "Last 7 days" card for each phone.
- **Changes made on the phone** (photos included) become the current version in the portal too,
  with a list of what changed; it flags missing permissions.
- **Version history**, copy a configuration from one phone to another.

Settings can also be changed on the phone: 5 quick taps (within 3 seconds) on the clock or on an empty spot
of the Home screen, then the PIN.

## Download and install

| Edition | Download | Install |
|---|---|---|
| **Standard** | [SemplicePhone.apk](../../releases/latest/download/SemplicePhone.apk) | from the phone's browser |
| **Full** (adds the `#UPDATE` SMS command and silent updates) | [SemplicePhone-completo.apk](../../releases/latest/download/SemplicePhone-completo.apk) | with ADB |

1. Open the APK link on the phone and allow the browser to install apps.
2. If Play Protect says *"Harmful app blocked"*, it is a false positive caused by the SOS
   permissions (SMS, location, calls): choose *Install anyway*. The source code is right here.
3. Open SemplicePhone and make it the Home app.
4. For unread messages and badges on Android 13+: Settings › Apps › SemplicePhone › ⋮ menu ›
   *Allow restricted settings*, then grant notification access.
5. Tap **5 times quickly** on the clock or on an empty spot of the Home screen (also between tiles), choose a PIN, set up tiles, SOS numbers and reminders, then **Save**.

If Android says *"For your security, this setting is currently unavailable"*, open App info › ⋮ ›
*Allow restricted settings*, or run `adb shell appops set app.semplicephone ACCESS_RESTRICTED_SETTINGS allow`.

All editions share the package name and signing key: install one over another without losing
settings. Google Play and F-Droid builds are prepared ([`tools/play`](tools/play),
[`tools/fdroid`](tools/fdroid)).

Optional: `adb shell dpm set-device-owner app.semplicephone/.admin.AdminReceiver` on a freshly
reset phone locks the notification shade, blocks installs and factory reset, grants permissions
automatically and enables silent updates (full edition).

## Languages and privacy

- App and portal in English, Italian, German, French and Spanish. The app follows the phone's
  language; Android 13+ can set one just for SemplicePhone. Family alerts come in the phone's
  language, or in the one chosen in the portal for that phone. To translate: [TRANSLATING.md](TRANSLATING.md).
- No account, no analytics, no data to the developer. With remote management, data only goes to
  the family's own portal: [PRIVACY.md](PRIVACY.md) (in Italian).

## For developers

Kotlin + Jetpack Compose, minSdk 29 / targetSdk 36, Atkinson Hyperlegible font (OFL). Build and
release instructions, tile model and milestones are in the [Italian README](README.md#per-sviluppatori);
the release checklist is [RELEASING.md](RELEASING.md).

```
./gradlew assembleStandardDebug testStandardDebugUnitTest
```

## License

Free software, Copyright (C) 2026 Francesco Sorzini. App: [GPL-3.0-or-later](LICENSE); portal:
[AGPL-3.0-or-later](tools/remote-server/LICENSE). See [`NOTICE.md`](NOTICE.md). The SOS button
contacts the numbers you set: it does not replace emergency services.
