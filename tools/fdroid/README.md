# Pubblicare su F-Droid

SemplicePhone va su F-Droid nell'**edizione completa**: chi installa da uno store non incontra i
blocchi di Play Protect sugli APK scaricati. La build è *riproducibile*. F-Droid compila dal
sorgente e, se il risultato coincide con il nostro APK, pubblica il nostro, firmato con la chiave
di SemplicePhone. Chi ha già l'APK del sito può quindi aggiornarlo da F-Droid, e viceversa.

## Cosa è già pronto nel repository

- Versione scritta in `app/build.gradle.kts` (`versionName`, `versionCode`), che F-Droid legge da lì.
- `fastlane/metadata/android/` con titolo, descrizioni, icona e novità di ogni versione
  (`changelogs/<versionCode>.txt`).
- Nessuna libreria proprietaria e nessun blob delle dipendenze nell'APK (`dependenciesInfo`).
- L'aggiornamento in-app si spegne da solo se l'app è installata da uno store.
- Il workflow di release verifica la riproducibilità con `apksigcopier`, lo stesso controllo
  di F-Droid.
- [`app.semplicephone.yml`](app.semplicephone.yml): i metadati per fdroiddata.

## Passi (una volta)

1. Crea un account su <https://gitlab.com> e fai il fork di <https://gitlab.com/fdroid/fdroiddata>.
2. Nel fork crea un ramo `semplicephone` e copia `app.semplicephone.yml` in `metadata/`.
3. Facoltativo ma consigliato: prova la build in locale (Linux o WSL, con Docker):

   ```
   docker run --rm -v $PWD:/repo registry.gitlab.com/fdroid/fdroidserver:buildserver \
     build --verbose --latest app.semplicephone
   ```

   In alternativa la pipeline di GitLab del tuo fork fa lo stesso controllo.
4. Apri la merge request verso `fdroid/fdroiddata` con il modello «App inclusion» e spunta le
   voci richieste. Rispondi alle domande dei revisori, che di solito arrivano in qualche giorno o
   settimana.
5. Dopo l'accettazione l'app compare in F-Droid entro un paio di giorni.

## Aggiornamenti successivi

Non serve fare niente su F-Droid. Per ogni nuova versione:
1. in `app/build.gradle.kts`: `versionName = "X.Y.Z"` e `versionCode` +10;
2. aggiungi `fastlane/metadata/android/it-IT/changelogs/<versionCode>.txt` (e `en-US`);
3. commit e avvio del workflow **release** con la stessa versione.

F-Droid vede il nuovo tag `vX.Y.Z` (`UpdateCheckMode: Tags`), compila, confronta e pubblica.

## Se la riproducibilità si rompe

Il workflow di release fallisce allo step «Verifica riproducibilità». Le cause tipiche sono
date o percorsi finiti nell'APK, oppure una dipendenza con file generati in ordine casuale. Lo
step mostra le differenze tra i due APK. Per un'analisi completa: `diffoscope a.apk b.apk`.
