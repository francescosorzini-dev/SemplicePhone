# SemplicePhone

Launcher Android per anziani: home fatta solo di tile grandi, tutti configurabili
(app, chiamata, videochiamata WhatsApp, SMS, link, scorciatoie, orologio, SOS).
Distribuzione via APK (sideload), non Play Store.

- Kotlin + Jetpack Compose, minSdk 29 / targetSdk 35
- Config in JSON: `app/src/main/assets/default_config.json` (default),
  `filesDir/config.json` (locale), config remota firmata (M6)

## Build

La CI (GitHub Actions) produce l'APK debug come artifact a ogni push su `main`.
In locale: `./gradlew assembleDebug` (JDK 17, Android SDK 35).

## Modello tile

```json
{ "id": "t1", "col": 0, "row": 1, "w": 2, "h": 1,
  "type": "app | call | wa_video | sms | url | shortcut | clock | sos",
  "target": "package | @dialer @sms @camera @gallery @browser | numero | url | pkg/shortcutId",
  "label": "Telefono",
  "icon": { "kind": "auto | app | symbol | photo | none", "ref": "phone" },
  "color": "#1F4FA8" }
```

Ogni pagina ha `cols` (1–4) e `rows` (1–8); tile fuori griglia o sovrapposti
fanno scartare la config (si resta sull'ultima valida).

## Milestone

| | Stato |
|---|---|
| M0 – scheletro, ruolo HOME, CI | ✅ |
| M1 – home a tile da config, orologio | ✅ (editor tile + PIN: prossimo) |
| M2 – contatti con foto, chiamata diretta, video WhatsApp | |
| M3 – blocco modifiche | |
| M4 – SOS con countdown, SMS con posizione | |
| M5 – promemoria farmaci | |
| M6 – config remota firmata Ed25519 | |
| M7 – build release firmata, Device Owner opzionale | |
