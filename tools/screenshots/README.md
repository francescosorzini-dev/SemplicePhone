# Screenshot per gli store

1. Avvia il workflow **screenshots** (Actions › screenshots › Run workflow). Su un emulatore
   Android 14 installa l'app, carica una configurazione dimostrativa e scatta 6 schermate in
   ogni lingua (Home, SOS, promemoria, editor, modifica riquadro, pulsante «Casa»). Le immagini grezze finiscono
   sul ramo `screenshots`.
2. Impagina le immagini per gli store (sfondo, didascalia, cornice del telefono, 1080×1920):

   ```
   git fetch origin screenshots && mkdir -p /tmp/shots
   for f in $(git ls-tree --name-only origin/screenshots); do git show origin/screenshots:$f > /tmp/shots/$f; done
   python3 tools/screenshots/frame.py /tmp/shots fastlane/metadata/android
   ```

Le didascalie stanno in `frame.py`, la configurazione dimostrativa in `demo_config.py`.
Gli screenshot del portale nel README (`docs/images/portal-*.png`) si fanno con il portale in
locale e Playwright, con i telefoni dimostrativi «Nonna Maria» e «Grandma Mary».
Da rifare quando cambia l'aspetto delle schermate.
