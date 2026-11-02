# SemplicePhone

Launcher Android per anziani. La Home è fatta solo di tile grandi e ogni tile è configurabile:
app, chiamata, videochiamata WhatsApp, SMS, link, scorciatoie, orologio, SOS.
Si distribuisce via APK (sideload), non dal Play Store. La configurazione si può gestire anche da
remoto, con firma Ed25519.

- Kotlin + Jetpack Compose, minSdk 29 / targetSdk 35, font Atkinson Hyperlegible (OFL)
- Config JSON: `assets/default_config.json` (predefinita) → `filesDir/config.json` (locale) →
  config remota firmata (sostituisce quella locale quando arriva una versione più nuova)

## Installazione su un telefono

1. Scarica l'APK dall'ultima [Release](../../releases) oppure dagli artifact della CI (build debug).
2. Installalo e tocca il banner «usa SemplicePhone come schermata Home».
3. Apri le impostazioni con **5 tocchi rapidi sull'orologio** (o sullo sfondo della griglia) e
   scegli il PIN.
4. Configura tile, numeri SOS e promemoria, poi premi **Salva**. A quel punto vengono chiesti
   solo i permessi che servono ai tile presenti.

### Protezione avanzata (facoltativa, Device Owner)

Su un telefono appena ripristinato e senza account aggiunti:

```
adb shell dpm set-device-owner app.semplicephone/.admin.AdminReceiver
```

Così si sbloccano:
- **Blocchi**: Home fissa, tendina bloccata, niente installazioni o disinstallazioni, niente
  ripristino di fabbrica.
- **Permessi** concessi in automatico.
- **Aggiornamenti** dell'app silenziosi.

Ogni opzione si toglie da Impostazioni › Protezione, compreso il ruolo di Device Owner.

## Funzioni

| | |
|---|---|
| **Home** | griglia per pagine (1–4 colonne × 1–8 righe), tile w×h, swipe tra pagine, tasto Home → pagina 1 |
| **Tile** | `app` (package o ruoli `@dialer @sms @camera @gallery @browser`), `call`, `wa_video`, `sms`, `url`, `shortcut`, `clock`, `sos` |
| **Contatti** | scelta dalla rubrica, foto in cerchio (o iniziali), chiamata diretta con `CALL_PHONE` |
| **SOS** | pressione prolungata → conto alla rovescia annullabile → SMS con posizione a tutti i numeri → chiamata al primo |
| **Pallini** | chiamate perse e messaggi non letti sui riquadri delle app (accesso alle notifiche) |
| **Suoneria protetta** | silenzioso e volume basso tornano al livello scelto, anche per le sveglie |
| **Batteria** | avviso in Home e SMS ai numeri SOS sotto soglia, una volta per scarica |
| **Promemoria** | allarme a schermo intero anche a telefono bloccato, «PRESO» / rinvio, ripetizione fino a conferma, registro esiti |
| **Editor** | protetto da PIN (PBKDF2, blocco dopo 5 errori); bozza applicata solo dopo validazione; riquadri trascinabili; scorciatoie guidate; esporta/importa su file |
| **Remoto** | busta firmata Ed25519, anti-replay, PIN impostabile, controllo ogni 15 min (ETag), SMS «#AGGIORNA», eventi urgenti; portale con editor visuale, avvisi Telegram/email, storico versioni: [`tools/remote-server`](tools/remote-server) |
| **Aggiornamenti** | `update.json` + verifica SHA-256 + PackageInstaller |

## Build

- Ogni push su `main` esegue la CI: APK debug come artifact, unit test e build release R8 di controllo.
- Release firmata: esegui una volta [`tools/release/new-keystore.ps1`](tools/release/new-keystore.ps1),
  che crea la chiave e i secrets GitHub. Poi `git tag v0.2.0 && git push origin v0.2.0`.
  La GitHub Release conterrà l'APK firmato e `update.json`.
- In locale: `./gradlew assembleDebug testDebugUnitTest` (JDK 17, Android SDK 35).

Valori di default sovrascrivibili con `-P` o in `gradle.properties`: `semplice.remoteUrl`,
`semplice.remotePubkey`, `semplice.updateUrl`.

## Modello tile

```json
{ "id": "t1", "col": 0, "row": 1, "w": 2, "h": 1,
  "type": "app | call | wa_video | sms | url | shortcut | clock | sos",
  "target": "package | @ruolo | numero | https://… | pacchetto/shortcutId",
  "label": "Telefono",
  "icon": { "kind": "auto | app | symbol | photo | none", "ref": "phone | contact:<lookupKey>" },
  "color": "#1F4FA8", "body": "testo SMS" }
```

Una config con tile fuori griglia, sovrapposti, id duplicati o orari non validi viene scartata
e resta in uso l'ultima valida. L'app, il server PHP e gli unit test applicano la stessa
validazione.

## Milestone

| | Stato |
|---|---|
| M0 – scheletro, ruolo HOME, CI | ✅ |
| M1 – home a tile da config, orologio, editor tile con PIN | ✅ |
| M2 – contatti con foto, chiamata diretta, video WhatsApp | ✅ |
| M3 – blocco modifiche, Device Owner | ✅ |
| M4 – SOS con countdown, SMS con posizione | ✅ |
| M5 – promemoria farmaci | ✅ |
| M6 – config remota firmata Ed25519 + server PHP | ✅ |
| M7 – release firmata, aggiornamento in-app | ✅ |

## Limiti noti

- **Videochiamata WhatsApp**: usa la data row del contatto, che non è documentata da WhatsApp.
  Se manca apre la chat.
- **Tendina e impostazioni di sistema**: bloccabili solo con Device Owner.
- **Pallini**: richiedono l'«Accesso alle notifiche», da concedere a mano (nemmeno il Device Owner può concederlo da solo).
- **Controllo ogni 15 minuti**: è il minimo consentito da Android; in risparmio energetico profondo (Doze) può slittare. Per applicare subito: «Aggiorna ora» o SMS «#AGGIORNA».

## Licenza

Software libero e gratuito, Copyright (C) 2026 Francesco Sorzini:
- **App Android**: [GPL-3.0-or-later](LICENSE).
- **Portale** (`tools/remote-server`): [AGPL-3.0-or-later](tools/remote-server/LICENSE).

Chi lo modifica e lo ridistribuisce, o offre il portale ad altri via rete, deve pubblicare il
codice sorgente con la stessa licenza. Componenti di terze parti e avvertenze in
[`NOTICE.md`](NOTICE.md). Il pulsante SOS contatta i numeri impostati e non sostituisce il 112.
