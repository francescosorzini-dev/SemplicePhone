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
)
