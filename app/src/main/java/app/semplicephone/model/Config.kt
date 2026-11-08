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
    /** Scheda per i soccorritori, dalla schermata SOS. */
    val emergency: EmergencyInfo = EmergencyInfo(),
)

/** Informazioni per i soccorritori (restano sul telefono e nel portale della famiglia). */
@Serializable
data class EmergencyInfo(
    val name: String = "",
    val birth: String = "",
    val conditions: String = "",
    val medications: String = "",
    val allergies: String = "",
    val bloodType: String = "",
    val doctor: String = "",
    val notes: String = "",
) {
    val isEmpty: Boolean get() = listOf(name, birth, conditions, medications, allergies, bloodType, doctor, notes).all { it.isBlank() }
}

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
    /**
     * Icone delle app sui riquadri: "original" (come le disegna chi fa l'app) o "mono" (a tinta unita
     * nel colore del riquadro, solo se l'app fornisce la sua versione monocromatica, Android 13+).
     */
    val appIcons: String = "original",
    /** Avviso se viene installata un'app di controllo remoto (AnyDesk, TeamViewer…). */
    val remoteAppAlert: Boolean = true,
    /** Messaggi da sconosciuti con link segnalati come possibile truffa e non apribili col tocco. */
    val scamGuard: Boolean = true,
    /** Chiamate in uscita bloccate verso numeri a pagamento (899, 144, 166…) ed estere. */
    val blockPremium: Boolean = false,
    val blockInternational: Boolean = false,
    /** Avviso alla famiglia se una chiamata con un numero non in rubrica dura più di N minuti (0 = no). */
    val longCallMinutes: Int = 0,
    /** Avviso se la SIM viene tolta o cambiata. */
    val simAlert: Boolean = false,
    /** Avviso se di giorno (8–21) il telefono non viene usato per N ore (0 = no). */
    val inactivityHours: Int = 0,
    /** Rilevamento caduta: urto forte e immobilità → «Stai bene?» → SOS. */
    val fallDetection: Boolean = false,
    /**
     * Sensibilità della caduta: "low", "medium", "high" (vedi FallDetector.Level). Più bassa = servono
     * una caduta più lunga, un colpo più forte e un'immobilità più completa (meno allarmi in auto).
     */
    val fallSensitivity: String = "medium",
    /** Zona sicura (solo edizione completa): raggio in metri attorno al punto indicato, 0 = no. */
    val safeZoneRadius: Int = 0,
    val safeZoneLat: Double = 0.0,
    val safeZoneLon: Double = 0.0,
    /** Caldo: con massima prevista ≥ soglia, promemoria «bevi» e avviso alla famiglia. */
    val heatAlert: Boolean = false,
    val heatThreshold: Int = 32,
    /** Volume delle chiamate tenuto almeno a questa percentuale (0 = non toccare). */
    val callVolumePercent: Int = 0,
    /** Testo di sistema (tutte le app): 0 = non toccare, altrimenti 1.15/1.3/1.5. */
    val fontScale: Float = 0f,
    /** Durata dello schermo acceso in secondi (0 = non toccare). */
    val screenTimeoutSeconds: Int = 0,
    /** Luminosità fissa in percentuale (0 = non toccare). */
    val brightnessPercent: Int = 0,
    /** «Buongiorno» parlato con data, meteo e appuntamenti all'ora scelta. */
    val morningBrief: Boolean = false,
    val morningTime: String = "09:00",
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
    /** Scorta: pastiglie nella scatola al momento [stockSetAt] (-1 = non contare). */
    val stock: Int = -1,
    val stockSetAt: Long = 0,
    /** Pastiglie per ogni «Presa» e soglia per l'avviso «stanno finendo». */
    val perDose: Int = 1,
    val lowAt: Int = 5,
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
     * - CALL / WA_VIDEO / TG_VIDEO / SMS: numero di telefono
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
    /** Solo per PHOTOS (Album): cartelle del telefono scelte col selettore di Android (URI dell'albero). */
    val folders: List<String> = emptyList(),
    /** Solo per RADIO: stazioni (nome e indirizzo dello stream). */
    val stations: List<Station> = emptyList(),
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
    APP("app"), CALL("call"), WA_VIDEO("wa_video"), TG_VIDEO("tg_video"), SMS("sms"), URL("url"), SHORTCUT("shortcut"),
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
    /** Radio: una o più stazioni in streaming, con pulsanti grandi dentro il launcher. */
    RADIO("radio"),
    TORCH("torch"),
    MAGNIFIER("magnifier"),
    WHERE_AM_I("where"),
    CALL_ME("call_me"),
    MUSIC("music"),
    NEWS("news"),
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

/** Stazione radio di un riquadro RADIO. */
@Serializable
data class Station(val name: String = "", val url: String = "")

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
