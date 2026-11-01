# Server config remota

`index.php` è un file unico (PHP 8.1+, estensione `sodium`). Va copiato in una cartella del sito,
per esempio `https://example.com/semplicephone/`. Al primo accesso crea `data/`, protetta da
`.htaccess`, e dentro genera:
- la coppia di chiavi Ed25519 (`signing.key`, permessi 0600);
- la password del pannello, scelta da te.

## Collegare un telefono

1. Sul telefono: impostazioni (5 tocchi sull'orologio) › Gestione da remoto. Lì trovi l'**ID del telefono**.
2. Sempre sul telefono, inserisci l'indirizzo `https://…/semplicephone/?device={device}` e la
   **chiave pubblica** mostrata in cima al pannello. Poi attiva e premi «Aggiorna ora».
3. Il telefono compare nel pannello. Aprilo e configura tutto senza toccare codice:
   - **Schermata**: anteprima della griglia; tocca un riquadro per modificarlo o un «+» per aggiungerne uno.
   - **SOS**: numeri, secondi da tenere premuto, testo dell'SMS.
   - **Promemoria**: ora, giorni, titolo e dettaglio, ripetizioni.
   - **Avanzato**: la stessa configurazione in JSON, per chi preferisce scriverla a mano.

   Il pannello segnala cosa manca prima di poter premere **Pubblica sul telefono**. Facoltativo:
   imposta un nuovo PIN.

Il telefono controlla ogni 6 ore. Per applicare subito c'è «Aggiorna ora».

## Sicurezza

- Il telefono applica solo buste firmate con la chiave del server e con `seq` più alto
  dell'ultima applicata. Una busta vecchia rimandata indietro (replay) viene ignorata.
- Ogni telefono si presenta con un segreto casuale, registrato al primo contatto (TOFU). Senza
  quel segreto non si scarica la config, che contiene numeri di telefono, e non si inviano report.
- Se un telefono viene reinstallato, nel pannello usa «Dimentica il segreto registrato».
- Se perdi `data/signing.key` devi reinserire la nuova chiave pubblica su ogni telefono.
  Fanne una copia sicura.

## Report

A ogni controllo il telefono invia: livello della batteria, versione applicata, esito
dell'ultimo aggiornamento ed esiti dei promemoria (preso / rinviato / non confermato). Il
pannello li mostra nella pagina del telefono.
