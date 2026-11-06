package app.semplicephone.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configurazione completa del launcher. Stesso formato in locale (filesDir/config.json),
 * negli asset (default) e, da M6, nella config remota firmata.
 */
@Serializable
data class LauncherConfig(
    val version: Int = 1,
    val pages: List<Page> = emptyList(),
    val sos: SosConfig = SosConfig(),
    val reminders: List<Reminder> = emptyList(),
    val phone: PhoneConfig = PhoneConfig(),
)

/** Comportamento del telefono attorno alla Home: suoneria, batteria, pallini, comandi SMS. */
@Serializable
data class PhoneConfig(
    /** Riporta suoneria (e volume sveglie) al livello impostato se vengono abbassati o silenziati. */
    val ringerLock: Boolean = false,
    /** Volume garantito per suoneria e sveglie, in percento del massimo. */
    val ringVolumePercent: Int = 100,
    /** Sotto questa percentuale, SMS ai numeri SOS e avviso in Home. 0 = disattivato. */
    val batteryAlertPercent: Int = 15,
    /** Pallino con chiamate perse e messaggi non letti sui riquadri delle app. */
    val badges: Boolean = true,
    /** Un SMS «#AGGIORNA» da un numero SOS fa scaricare subito la configurazione remota. */
    val smsUpdateCommand: Boolean = false,
    /** Le app si aprono sempre dalla schermata iniziale (chat, messaggi…), non dall'ultima pagina vista. */
    val freshStart: Boolean = true,
    /** Squillano solo rubrica, numeri SOS e numeri dei riquadri; gli altri vengono rifiutati (filtro chiamate). */
    val blockUnknown: Boolean = false,
    /** Le chiamate dai riquadri partono in vivavoce e con il volume della chiamata al massimo. */
    val speakerCalls: Boolean = false,
    /** Pressione lunga su un riquadro: il telefono ne legge il nome ad alta voce. */
    val speakTiles: Boolean = false,
    /** Prima di aprire un riquadro il telefono dice cosa sta per fare («Chiamo Maria»). */
    val voiceConfirm: Boolean = false,
    /** In carica e senza tocchi per un minuto: orologio grande a tutto schermo, schermo acceso. */
    val bedsideClock: Boolean = false,
    /** Le chiamate dai numeri SOS ricevono risposta da sole, in vivavoce, dopo [autoAnswerSeconds]. */
    val autoAnswer: Boolean = false,
    val autoAnswerSeconds: Int = 10,
    /** Durante lo squillo il telefono dice chi chiama («Chiama Maria»). */
    val announceCaller: Boolean = false,
    /** Di notte il telefono non squilla, tranne per i numeri SOS (orari «HH:mm», anche a cavallo della mezzanotte). */
    val nightQuiet: Boolean = false,
    val nightFrom: String = "22:00",
    val nightTo: String = "07:00",
    /** «Metti in carica» la sera: all'ora [chargeTime], se la batteria è sotto [chargeBelow]%. */
    val chargeReminder: Boolean = false,
    val chargeTime: String = "20:00",
    val chargeBelow: Int = 40,
    /** Riquadri che si aprono solo tenendo premuto (tremore): il tocco breve legge il nome. */
    val holdToOpen: Boolean = false,
    /** Tema: "normal", "contrast" (alto contrasto), "night" (scuro la sera, dalle 21 alle 7). */
    val theme: String = "normal",
    /** Pulsante «Casa» sempre visibile sopra le altre app: un tocco riporta al launcher. */
    val homeButton: Boolean = false,
    /** Colore del pulsante «Casa» (uno dei colori dei riquadri). */
    val homeButtonColor: String = "#1F4FA8",
    /** Scritta «CASA» sotto l'icona; spenta = solo icona, più grande. */
    val homeButtonLabel: Boolean = true,
    /** Bordo di partenza: "right" o "left" (poi lo si può trascinare). */
    val homeButtonSide: String = "right",
)

/** Promemoria (farmaci, attività, appuntamenti) a orario fisso, con ripetizione finché non viene confermato. */
@Serializable
data class Reminder(
    val id: String,
    /** "HH:mm", ora locale del telefono. */
    val time: String,
    /** 1 = lunedì … 7 = domenica (ISO); vuoto = tutti i giorni. */
    val days: List<Int> = emptyList(),
    val title: String = "È ora della medicina",
    val detail: String = "",
    val snoozeMinutes: Int = 10,
    /** Se non si risponde, risuona ogni N minuti fino a maxRepeats volte. */
    val repeatMinutes: Int = 5,
    val maxRepeats: Int = 6,
    val enabled: Boolean = true,
    /** Simbolo mostrato nell'allarme (vedi Symbols): "pill" per i farmaci, "shower", "cart", "cards"… */
    val icon: String = "pill",
    /** "AAAA-MM-GG": promemoria di un solo giorno (visita, torneo); vuoto = ricorrente secondo [days]. */
    val date: String = "",
    /** Foto mostrata nell'allarme (la pastiglia, il luogo…): "media:<sha256>", vedi Media. */
    val photo: String = "",
)

@Serializable
data class Page(
    val cols: Int = 2,
    val rows: Int = 5,
    val tiles: List<Tile> = emptyList(),
)

@Serializable
data class Tile(
    val id: String,
    val col: Int,
    val row: Int,
    val w: Int = 1,
    val h: Int = 1,
    val type: TileType,
    /**
     * Destinazione, interpretata in base a [type]:
     * - APP: package name oppure ruolo "@dialer", "@sms", "@camera", "@gallery", "@browser"
     * - CALL / WA_VIDEO / SMS: numero di telefono
     * - URL: indirizzo https
     * - SHORTCUT: "package/shortcutId"
     * - CLOCK / SOS: ignorato
     */
    val target: String = "",
    val label: String = "",
    val icon: TileIcon = TileIcon(),
    /** Colore di sfondo "#RRGGBB". Testo e icona bianchi o neri in base alla luminanza. */
    val color: String = "#1F4FA8",
    /** Solo per SMS: testo precompilato. */
    val body: String = "",
    /** Solo per PHOTOS: immagini che scorrono ("media:<sha256>", vedi Media). */
    val photos: List<String> = emptyList(),
    /**
     * Solo per APP: «default» segue l'impostazione del telefono (PhoneConfig.freshStart),
     * «fresh» riparte sempre dalla schermata iniziale, «resume» riprende dall'ultima pagina.
     */
    val open: String = OPEN_DEFAULT,
    /** Pulsante «Casa» (se attivo sul telefono) dentro l'app aperta da questo riquadro. */
    val homeButton: Boolean = true,
)

const val OPEN_DEFAULT = "default"
const val OPEN_FRESH = "fresh"
const val OPEN_RESUME = "resume"

/**
 * Tipo di riquadro. Un tipo sconosciuto (introdotto da una versione più recente) diventa UNKNOWN
 * invece di far scartare tutta la configurazione: il riquadro resta vuoto finché l'app si aggiorna.
 */
@Serializable(with = TileTypeSerializer::class)
enum class TileType(val code: String) {
    APP("app"), CALL("call"), WA_VIDEO("wa_video"), SMS("sms"), URL("url"), SHORTCUT("shortcut"),
    CLOCK("clock"), SOS("sos"),
    /** target = "lat,lon", label = località (Open-Meteo). */
    WEATHER("weather"),
    /** Foto che scorrono, da [Tile.photos]. */
    PHOTOS("photos"),
    /** Chiamate perse non ancora richiamate, con richiamata in un tocco. */
    MISSED_CALLS("missed_calls"),
    /** Ricerca delle app dentro il launcher: si scrive parte del nome e un tocco la apre. */
    SEARCH("search"),
    /** Messaggi da leggere (SMS, WhatsApp…) dalle notifiche; nelle edizioni senza accesso alle notifiche apre l'app Messaggi. */
    MESSAGES("messages"),
    UNKNOWN("unknown"),
}

object TileTypeSerializer : kotlinx.serialization.KSerializer<TileType> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("TileType", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: TileType) = encoder.encodeString(value.code)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): TileType {
        val s = decoder.decodeString()
        return TileType.entries.firstOrNull { it.code == s } ?: TileType.UNKNOWN
    }
}

@Serializable
data class TileIcon(
    val kind: IconKind = IconKind.AUTO,
    /** SYMBOL: nome simbolo (vedi Symbols); PHOTO: "contact:<id>" (da M2). */
    val ref: String = "",
    /**
     * Foto del contatto grande quanto il riquadro, con il nome sopra. Campo a parte (non un nuovo
     * IconKind) così le versioni precedenti dell'app lo ignorano invece di scartare la config.
     */
    val fill: Boolean = false,
)

@Serializable
enum class IconKind {
    /** Icona dell'app per APP/SHORTCUT, simbolo di default per gli altri tipi. */
    @SerialName("auto") AUTO,
    @SerialName("app") APP,
    @SerialName("symbol") SYMBOL,
    @SerialName("photo") PHOTO,
    @SerialName("none") NONE,
}

@Serializable
data class SosConfig(
    /** Il primo numero viene chiamato; a tutti viene inviato l'SMS (da M4). */
    val numbers: List<String> = emptyList(),
    val holdSeconds: Int = 3,
    /** Secondi di conto alla rovescia annullabile dopo la pressione, prima di chiamare. */
    val countdownSeconds: Int = 5,
    /** Testo dell'SMS; la posizione viene aggiunta in fondo. */
    val message: String = "Ho bisogno di aiuto.",
)
