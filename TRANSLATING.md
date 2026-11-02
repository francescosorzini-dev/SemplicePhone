# Tradurre SemplicePhone / Translating SemplicePhone

*English below.*

SemplicePhone è in italiano, inglese, tedesco, francese e spagnolo. Per correggere una traduzione
o aggiungere una lingua non serve scrivere codice: bastano tre gruppi di file di testo.

| Cosa | File | Formato |
|---|---|---|
| App Android | `app/src/main/res/values-<lingua>/strings.xml` (base: `values/strings.xml`, inglese) | risorse Android |
| Portale web | `tools/remote-server/lang/<lingua>.json` (base: `en.json`) | JSON chiave → testo |
| Schede degli store | `fastlane/metadata/android/<lingua-PAESE>/` | testo semplice |

Regole:
- Lascia identici i segnaposto: `%1$s` e `%1$d` nell'app, `{0}` e `{1}` nel portale.
- I testi che finiscono in `_html` (portale) possono contenere `<b>`, `<code>` e `<a>`.
- Il comando SMS dell'app (`sms_keyword`) deve restare uno di quelli accettati dal telefono:
  `#AGGIORNA`, `#UPDATE`, `#AKTUALISIEREN`, `#ACTUALISER` o `#ACTUALIZAR`. Per una lingua nuova si
  aggiunge la parola anche in `remote/SmsCommand.kt`.
- I controlli automatici (unit test dell'app e `embed-langs.php` del portale) bloccano la build
  se a una lingua mancano chiavi o segnaposto.

## Weblate (consigliato)

[Hosted Weblate](https://hosted.weblate.org) è gratuito per i progetti liberi e permette di
tradurre dal browser. Chi gestisce il repository:
1. crea il progetto «SemplicePhone» (piano *Libre*) e collega `https://github.com/francescosorzini-dev/SemplicePhone`;
2. aggiunge tre componenti:

   | Componente | Formato | Maschera file | File base |
   |---|---|---|---|
   | App | Android String Resource | `app/src/main/res/values-*/strings.xml` | `app/src/main/res/values/strings.xml` |
   | Portale | JSON file | `tools/remote-server/lang/*.json` | `tools/remote-server/lang/en.json` |
   | Store | App store metadata files | `fastlane/metadata/android/*` | `fastlane/metadata/android/en-US` |

3. in GitHub › Settings › Webhooks aggiunge l'URL che Weblate indica, per ricevere le modifiche;
4. lascia a Weblate il compito di aprire le pull request con le traduzioni.

Una lingua aggiunta su Weblate compare da sola nell'app (Android 13+: «Lingua dell'app») e nel
portale (elenco in fondo alla pagina).

## Senza Weblate

Copia i file della base inglese con il codice della lingua (es. `values-pt/strings.xml`,
`lang/pt.json`, `fastlane/metadata/android/pt-PT/`), traduci i testi e apri una pull request.

---

## English

SemplicePhone ships in Italian, English, German, French and Spanish. Fixing a translation or
adding a language needs no code, only the text files listed above. English is the base language.

Keep placeholders unchanged: `%1$s` and `%1$d` in the app, `{0}` and `{1}` in the portal. Portal
keys ending in `_html` may contain `<b>`, `<code>` and `<a>`. Automated checks fail the build if a
language is missing keys or placeholders.

**Weblate**: create a *Libre* project on hosted.weblate.org with the three components in the
table above. Add Weblate's webhook in the GitHub repository settings and let Weblate open pull
requests. A new language then appears automatically in the app (Android 13+ per-app language) and
in the portal (language links at the bottom of the page).

**Without Weblate**: copy the English files under the new language code, translate them and open
a pull request.
