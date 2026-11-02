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

Il telefono controlla ogni 15 minuti, con rete. Quando non ci sono novità il server risponde
`304` e la richiesta pesa pochi byte. Per applicare subito ci sono «Aggiorna ora» sul telefono e,
se attivato nella scheda **Telefono** (solo edizione completa dell'app), l'SMS «#AGGIORNA» inviato da uno dei numeri SOS.

## Avvisi immediati

Dalla pagina **Avvisi** del pannello puoi ricevere un messaggio su Telegram (consigliato) o via
email in questi casi:
- viene premuto SOS (il messaggio include la posizione);
- un promemoria non viene confermato;
- la batteria scende sotto la soglia;
- un telefono non si fa sentire da N ore, e poi quando torna raggiungibile.

C'è il pulsante «Invia un messaggio di prova». Il controllo dei telefoni silenziosi parte da solo
a ogni richiesta, al massimo ogni 10 minuti. Se l'hosting lo permette conviene aggiungere un cron:

```
*/10 * * * * php /percorso/semplicephone/index.php cron
```

## Altro

- **App reali**: il telefono invia l'elenco delle app installate, quindi il menu «Quale app»
  mostra quelle.
- **Versioni precedenti**: le ultime 30 pubblicazioni di ogni telefono si possono ricaricare
  nell'editor e ripubblicare.
- **Copia**: la configurazione di un telefono si può copiare su un altro.
- **Telefoni spenti apposta**: per ciascuno si può disattivare l'avviso «non raggiungibile».
  Un telefono si può anche rimuovere dal pannello.

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
