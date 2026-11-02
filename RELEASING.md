# Checklist di release

Da seguire a **ogni** versione, su tutti i livelli di pubblicazione.

## 1. Codice e versione
- [ ] `app/build.gradle.kts`: `versionName = "X.Y.Z"`, `versionCode` +10.
- [ ] `fastlane/metadata/android/<lingua>/changelogs/<versionCode>.txt` per **tutte** le lingue
  (it-IT, en-US, de-DE, fr-FR, es-ES). Massimo 500 caratteri.
- [ ] Testi nuovi dell'app tradotti in tutte le `values-*/strings.xml` (il test `I18nTest` blocca
  la build se ne manca uno).
- [ ] Testi nuovi del portale in tutti i `tools/remote-server/lang/*.json` (`embed-langs.php`
  blocca la release se ne manca uno).
- [ ] Commit e push su `main`. La CI di build deve essere verde.

## 2. GitHub Release (workflow «release», versione = `X.Y.Z`)
Il workflow pubblica e verifica:
- APK standard e completo, con i nomi fissi per i link `latest/download/...`;
- AAB per Google Play;
- `update.json` e `update-completo.json` per l'aggiornamento interno;
- `index.php` del portale con versione e lingue incorporate;
- firma con la chiave di SemplicePhone, permessi delle edizioni e riproducibilità (come F-Droid).

## 3. Portale
- [ ] Sui siti dove è installato, sostituire `index.php` con quello della release
  (`.../releases/latest/download/index.php`), **mai** quello del repository. `data/` resta.

## 4. F-Droid
- [ ] `tools/fdroid/app.semplicephone.yml`: nel blocco `Builds` lasciare solo la nuova versione
  (`versionName`, `versionCode`, `commit` = **hash completo** del tag), più `CurrentVersion` e
  `CurrentVersionCode`.
- [ ] Se l'app è già su F-Droid, l'aggiornamento è automatico (`UpdateCheckMode: Tags`).
  Altrimenti aggiornare il file anche nel fork GitLab (`francesco.sorzini/fdroiddata`, ramo
  `app.semplicephone`).
- Stato: richiesta !55463 chiusa per la regola dei 3 mesi; si può riaprire dal 2027-02-01.

## 5. Google Play
- [ ] Caricare `SemplicePhone-play-X.Y.Z.aab` nella traccia in uso (test chiuso o produzione).
- [ ] Testo «Novità» da `changelogs/<versionCode>.txt`.
- [ ] Nuovi permessi: controllare `app/src/play/AndroidManifest.xml` e, se serve, le
  dichiarazioni nella Console (`tools/play/README.md`).

## 6. Documentazione
- [ ] `README.md` e `README.en.md` (funzioni, edizioni, milestone), `PRIVACY.md` se cambiano i dati
  trattati, le guide in `tools/` se cambia la procedura.
- [ ] Link all'APK: `https://github.com/francescosorzini-dev/SemplicePhone/releases/latest/download/SemplicePhone.apk`.
