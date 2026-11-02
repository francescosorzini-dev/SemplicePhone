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
)

/** Promemoria (farmaci o altro) a orario fisso, con ripetizione finché non viene confermato. */
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
)

@Serializable
enum class TileType {
    @SerialName("app") APP,
    @SerialName("call") CALL,
    @SerialName("wa_video") WA_VIDEO,
    @SerialName("sms") SMS,
    @SerialName("url") URL,
    @SerialName("shortcut") SHORTCUT,
    @SerialName("clock") CLOCK,
    @SerialName("sos") SOS,
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
