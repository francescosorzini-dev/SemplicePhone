# Informativa sulla privacy – SemplicePhone

*Ultimo aggiornamento: 3 novembre 2026 (0.10.0)*

SemplicePhone è un launcher (schermata Home) per Android, software libero distribuito
gratuitamente da Francesco Sorzini. Non ha pubblicità, analisi statistiche o account e non
invia dati allo sviluppatore.

## Dati usati sul telefono

| Dato | A cosa serve | Esce dal telefono? |
|---|---|---|
| Contatti (nome, numero, foto) | riquadri di chiamata, SMS e videochiamata | no |
| Posizione | solo quando si preme SOS, per indicarla ai numeri di emergenza | solo ai numeri SOS (SMS o app Messaggi) e, se attiva, al portale del familiare |
| Elenco delle app installate | scegliere l'app da mettere in un riquadro | solo se è attiva la gestione da remoto, verso il portale del familiare |
| Promemoria ed esiti (preso, rinviato, non confermato) | allarmi dei farmaci | solo se è attiva la gestione da remoto |
| Livello della batteria, versione dell'app | avviso di batteria scarica | solo se è attiva la gestione da remoto |
| Numeri delle chiamate bloccate | blocco dei numeri sconosciuti (se attivato) | solo se è attiva la gestione da remoto, verso il portale del familiare |
| Foto scelte per i riquadri o i promemoria | foto di famiglia, foto del farmaco | no (le foto caricate dal portale vengono scaricate dal telefono) |
| Registro chiamate (numero, tipo, ora) | riquadro «Chiamate perse» (edizioni fuori da Google Play) | no |
| Coordinate della località del meteo | riquadro meteo | sì, al servizio Open-Meteo (open-meteo.com) per ottenere le previsioni; nessun dato personale |

## Gestione da remoto (facoltativa, disattivata all'installazione)

Se un familiare la attiva, il telefono si collega a un indirizzo scelto da lui: un portale che
installa e gestisce sul proprio spazio web. Lo sviluppatore di SemplicePhone non lo gestisce e
non ne riceve i dati.

Il telefono invia al portale:
- un identificativo casuale del telefono;
- i dati della tabella marcati «gestione da remoto»;
- quali accessi mancano alle funzioni attive (es. «Mostra sopra altre app»), per avvisare il familiare;
- gli avvisi di SOS (con la posizione), di promemoria non confermati e di chiamate bloccate;
- la posizione su richiesta del familiare («Dov'è?»), solo se sul telefono è stata consentita.

Il collegamento usa HTTPS. Le configurazioni ricevute devono essere firmate con la chiave del
portale. Per interrompere l'invio basta spegnere «Gestione da remoto» o disinstallare l'app; i
dati già inviati si cancellano dal portale.

## Permessi

- Telefono: per chiamare.
- Contatti: per i riquadri e per riconoscere i contatti nel filtro chiamate.
- Registro chiamate: per il riquadro «Chiamate perse» (non nell'edizione Google Play).
- Mostra sopra altre app: per il pulsante «Casa», se attivato.
- Posizione: per l'SOS.
- Notifiche e sveglie: per i promemoria.
- «Non disturbare»: per togliere il silenzioso.
- Amministratore del dispositivo: solo se un familiare la attiva, per bloccare le modifiche
  accidentali.

Le edizioni distribuite fuori da Google Play possono usare anche SMS e lettura delle notifiche,
sempre e solo sul telefono.

## Minori

L'app non è rivolta ai minori.

## Contatti

Domande: apri una segnalazione su
<https://github.com/francescosorzini-dev/SemplicePhone/issues>.
