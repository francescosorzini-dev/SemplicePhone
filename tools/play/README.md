# Pubblicare su Google Play

Su Play va l'**edizione play** (flavor `play`). È uguale alla standard tranne che per tre punti:
- **non invia SMS da sola**: la policy di Play riserva il permesso agli SMS predefiniti. Durante
  l'SOS parte la chiamata, poi lo schermo mostra «MANDA LA POSIZIONE», che apre Messaggi già
  compilato;
- **promemoria con `SCHEDULE_EXACT_ALARM`** invece di `USE_EXACT_ALARM`, che Play riserva a
  sveglie e calendari. Da Android 14 va concesso «Sveglie e promemoria», e l'editor dei
  promemoria lo chiede;
- **niente aggiornamento in-app**: aggiorna Play.

Stesso nome pacchetto (`app.semplicephone`) e stessa chiave delle altre edizioni: chi ha l'APK
del sito può passare a Play e viceversa, senza perdere le impostazioni.

Ogni release GitHub contiene `SemplicePhone-play-X.Y.Z.aab`, già firmato con la nostra chiave:
è il file da caricare.

## 1. Account sviluppatore

1. <https://play.google.com/console/signup>: account **personale**, 25 USD una tantum, documento
   d'identità e verifica del telefono.
2. Da account personale nuovo, prima della produzione serve un **test chiuso con almeno 12
   tester per 14 giorni consecutivi**. Familiari e amici con un account Google vanno benissimo:
   devono accettare l'invito e tenere l'app installata.

## 2. Chiave di firma (una volta)

Alla creazione dell'app la Console propone **Firma dell'app di Play**. Scegli di usare la
chiave esistente (*Use existing app signing key → Export and upload a key from Java keystore*):
- scarica `pepk.jar` dalla Console;
- esegui il comando mostrato sul keystore di SemplicePhone (quello dei secrets
  `SIGNING_KEYSTORE_B64`) e carica il file cifrato che produce.

Così Play firma con la stessa chiave degli APK del sito. La chiave di caricamento resta la
stessa.

> Se si salta questo passo, Play genera una chiave sua. Gli utenti dovranno disinstallare
> l'APK del sito per passare a Play, e viceversa.

## 3. Scheda dello Store

I testi e le immagini sono in `fastlane/metadata/android/it-IT/` (e `en-US/`):

| Campo | File |
|---|---|
| Nome (max 30) | `title.txt` |
| Breve descrizione (max 80) | `short_description.txt` |
| Descrizione completa | `full_description.txt` (la Console accetta `<b>`, `<ul>`, `<li>`) |
| Icona 512×512 | `images/icon.png` |
| Grafica in evidenza 1024×500 | `images/featureGraphic.png` |
| Screenshot (2–8, telefono) | da fare: catture dell'app in uso, 9:16 |

- Categoria: **Personalizzazione**.
- Email di contatto: obbligatoria e visibile.
- Norme sulla privacy:
  <https://github.com/francescosorzini-dev/SemplicePhone/blob/main/PRIVACY.md>

## 4. Contenuti dell'app (Policy › Contenuti dell'app)

- **Classificazione dei contenuti**: questionario IARC, categoria «Utilità»; nessun contenuto
  sensibile.
- **Target**: 18+. Non rivolta ai bambini.
- **Annunci**: no.
- **Accesso all'app**: tutto è accessibile senza login. Indica che le impostazioni si aprono
  toccando 5 volte l'orologio e che il PIN si sceglie al primo accesso.
- **App governative / finanziarie / sanitarie**: no. I promemoria sono generici: li scrive il
  familiare e l'app non tratta dati sanitari.

### Sicurezza dei dati (risposte suggerite)

Dati inviati fuori dal telefono **solo se** un familiare attiva la gestione da remoto, verso un
server suo (non dello sviluppatore). Per prudenza dichiararli come raccolti, facoltativi:

| Tipo | Raccolto | Condiviso | Scopo | Facoltativo |
|---|---|---|---|---|
| Posizione › Posizione precisa (SOS e «Dov'è?» su richiesta del familiare, mai in background nell'edizione Play) | sì | no | Funzionalità dell'app | sì |
| Info app e prestazioni › Altro (batteria, versione) | sì | no | Funzionalità dell'app | sì |
| Attività nelle app › App installate | sì | no | Funzionalità dell'app | sì |
| Attività nelle app › Altre azioni (esiti promemoria) | sì | no | Funzionalità dell'app | sì |
| ID dispositivo o altri ID (ID casuale) | sì | no | Funzionalità dell'app | sì |
| Telefono › Registro chiamate (numeri sconosciuti bloccati, solo se il blocco è attivo) | sì | no | Funzionalità dell'app | sì |

- Dati crittografati in transito: **sì** (solo HTTPS).
- Richiesta di eliminazione: **sì**, si spegne la gestione da remoto o si disinstalla l'app; i
  dati si cancellano dal portale.
- Contatti: usati solo sul telefono, **non** raccolti.
- Meteo: il riquadro meteo chiede a Open-Meteo solo le coordinate del luogo scelto dal familiare, senza ID; dichiararlo non è necessario (nessun dato utente).
- Foto di famiglia/pastiglie: scelte dal familiare, scaricate dal suo portale; restano sul telefono.

### Dichiarazioni dei permessi

| Permesso | Cosa scrivere |
|---|---|
| Notifiche a schermo intero (`USE_FULL_SCREEN_INTENT`) | «Funzionalità sveglia: i promemoria (farmaci e attività) suonano a schermo intero anche a telefono bloccato, come una sveglia, e restano finché la persona conferma.» |
| Sveglie esatte (`SCHEDULE_EXACT_ALARM`) | Di solito non serve dichiarazione. Se richiesta: «Promemoria impostati dall'utente a un orario preciso». |
| Amministratore del dispositivo / Device Owner | «Facoltativo, attivato via ADB da un familiare per impedire disinstallazioni e modifiche accidentali su un telefono usato da un anziano. Nessuna attivazione automatica.» |
| Ruolo «Filtro chiamate» (`CallScreeningService`) | Nessun permesso sensibile: il ruolo si concede dal dialogo di sistema. Nella scheda: «blocca le chiamate da numeri non in rubrica, per proteggere gli anziani dalle truffe telefoniche». |
| Mostra sopra altre app (`SYSTEM_ALERT_WINDOW`) | Nessuna dichiarazione: è un accesso speciale che l'utente concede da Impostazioni. Nella scheda: «pulsante Casa sopra le altre app, perché una persona anziana possa sempre tornare alla schermata semplice; facoltativo, attivato dal familiare». |
| Rispondere alle chiamate (`ANSWER_PHONE_CALLS`) e stato del telefono (`READ_PHONE_STATE`) | «Facoltativo: risponde da solo, in vivavoce, alle chiamate dei numeri di emergenza impostati dalla famiglia, per una persona anziana caduta o che non trova il telefono.» |
| Registro chiamate (`READ_CALL_LOG`) | **Non presente** nell'edizione Play (riservato alle app telefono predefinite): il riquadro «Chiamate perse» apre il registro chiamate del telefono. Il controllo nella release blocca l'AAB se compare. |
| Accesso a «Non disturbare» | «Riattiva la suoneria se la persona anziana mette il telefono in silenzioso per errore; attivabile solo dalle impostazioni protette da PIN.» |

## 5. Pubblicazione

1. **Test › Test chiuso**: crea la traccia, carica l'AAB, aggiungi la lista dei 12+ tester e
   invia per la revisione.
2. Dopo 14 giorni con almeno 12 tester attivi, sotto **Dashboard** compare «Richiedi accesso
   alla produzione». Si risponde a qualche domanda sul test e si attende l'approvazione.
3. **Produzione**: promuovi la release del test o carica di nuovo l'AAB. Prima revisione: da
   qualche giorno a un paio di settimane.

Aggiornamenti: a ogni release carica il nuovo `.aab` (il `versionCode` è già +10). Testo
«Novità»: `changelogs/<versionCode>.txt`.

## Requisiti che cambiano

- **Target API**: da agosto 2026 nuove app e aggiornamenti devono avere `targetSdk` 36
  (impostato).
- **Policy permessi**: se una revisione rifiuta un permesso, la Console indica quale. Va tolto
  in `app/src/play/AndroidManifest.xml` e il workflow di release controlla che non ritorni.
