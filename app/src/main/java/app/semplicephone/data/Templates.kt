package app.semplicephone.data

import android.content.Context
import app.semplicephone.R
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.Page
import app.semplicephone.model.PhoneConfig
import app.semplicephone.model.Tile
import app.semplicephone.model.TileType

/**
 * Modelli pronti per situazione: impostano riquadri e opzioni, lasciando com'erano familiari
 * (chiamate, videochiamate, SMS), SOS, promemoria, meteo e album. Stessi modelli della
 * configurazione guidata del portale.
 */
enum class Template(
    val id: String,
    val title: Int,
    val description: Int,
    /** Righe della griglia: 4 = riquadri più alti (vista, tremore, solo telefonate). */
    val rows: Int,
    /** Riquadri oltre ai familiari, in ordine: target di un'app ("@dialer") o tipo ("missed_calls"). */
    val tiles: List<String>,
    val phone: (PhoneConfig) -> PhoneConfig,
) {
    AUTONOMOUS(
        "autonomous", R.string.tpl_autonomous, R.string.tpl_autonomous_desc, 5,
        listOf("@dialer", "@sms", "com.whatsapp", "missed_calls", "messages", "@camera", "photos", "search", "@browser"),
        { it.copy(homeButton = true, holdToOpen = false, theme = "normal") },
    ),
    CALLS(
        "calls", R.string.tpl_calls, R.string.tpl_calls_desc, 4, listOf("missed_calls"),
        { it.copy(speakerCalls = true, speakTiles = true, autoAnswer = true, announceCaller = true, blockUnknown = true, bedsideClock = true, homeButton = false) },
    ),
    MEMORY(
        "memory", R.string.tpl_memory, R.string.tpl_memory_desc, 5, listOf("missed_calls", "photos"),
        {
            it.copy(
                speakerCalls = true, speakTiles = true, voiceConfirm = true, autoAnswer = true, announceCaller = true, blockUnknown = true,
                bedsideClock = true, homeButton = true, holdToOpen = true, nightQuiet = true, chargeReminder = true,
            )
        },
    ),
    SIGHT(
        "sight", R.string.tpl_sight, R.string.tpl_sight_desc, 4, listOf("@dialer", "missed_calls", "com.whatsapp"),
        { it.copy(speakerCalls = true, speakTiles = true, voiceConfirm = true, announceCaller = true, homeButton = true, homeButtonLabel = true, theme = "contrast") },
    ),
    HEARING(
        "hearing", R.string.tpl_hearing, R.string.tpl_hearing_desc, 5, listOf("com.whatsapp", "@sms", "messages", "missed_calls", "@camera"),
        { it.copy(speakerCalls = true, speakTiles = false, announceCaller = false, homeButton = true) },
    ),
    TREMOR(
        "tremor", R.string.tpl_tremor, R.string.tpl_tremor_desc, 4, listOf("@dialer", "@sms", "com.whatsapp", "missed_calls", "@camera"),
        { it.copy(speakerCalls = true, speakTiles = true, voiceConfirm = true, autoAnswer = true, announceCaller = true, homeButton = true, holdToOpen = true) },
    );

    companion object {
        private val PEOPLE = setOf(TileType.CALL, TileType.WA_VIDEO, TileType.TG_VIDEO, TileType.SMS)
        private val APP_LABELS = mapOf(
            "@dialer" to R.string.label_phone, "@sms" to R.string.label_messages, "@camera" to R.string.label_camera,
            "@gallery" to R.string.label_photos, "@browser" to R.string.label_internet,
        )

        /** Config con la disposizione del modello: [cfg] fornisce familiari, SOS, promemoria e il resto. */
        fun apply(context: Context, t: Template, cfg: LauncherConfig, installed: (String) -> Boolean = { true }): LauncherConfig {
            val old = cfg.pages.flatMap { it.tiles }
            val sos = old.firstOrNull { it.type == TileType.SOS }
            val people = old.filter { it.type in PEOPLE }
            val weather = old.firstOrNull { it.type == TileType.WEATHER }
            val album = old.firstOrNull { it.type == TileType.PHOTOS }
            var n = 0
            fun id(prefix: String) = "$prefix${System.currentTimeMillis().toString(36)}${n++}"
            fun extra(key: String): Tile? = when (key) {
                "missed_calls" -> Tile(id("t"), 0, 0, w = 2, type = TileType.MISSED_CALLS, label = context.getString(R.string.label_missed_calls), color = "#FFD54F")
                "messages" -> Tile(id("t"), 0, 0, w = 2, type = TileType.MESSAGES, label = context.getString(R.string.label_messages_unread), color = "#1F4FA8")
                "search" -> Tile(id("t"), 0, 0, w = 2, type = TileType.SEARCH, label = context.getString(R.string.label_search), color = "#1A1A1A")
                "photos" -> album?.copy(w = 1, h = 1) ?: Tile(id("t"), 0, 0, type = TileType.PHOTOS, label = context.getString(R.string.label_album), color = "#6A1B9A")
                else -> if (!installed(key)) null else Tile(
                    id("t"), 0, 0, type = TileType.APP, target = key,
                    label = APP_LABELS[key]?.let(context::getString) ?: if (key == "com.whatsapp") "WhatsApp" else key,
                    color = when (key) { "@camera", "@gallery" -> "#6A1B9A"; "com.whatsapp" -> "#1B7A34"; "@browser" -> "#1A1A1A"; else -> "#1F4FA8" },
                )
            }
            val items = people.map { it.copy(w = 1, h = 1) } + listOfNotNull(weather?.copy(w = 1, h = 1)) + t.tiles.mapNotNull(::extra)
            return cfg.copy(pages = layout(items, t.rows, sos), phone = t.phone(cfg.phone))
        }

        /**
         * Griglia a 2 colonne: orologio in alto, SOS in fondo alla prima pagina, il resto in ordine;
         * un riquadro largo che cadrebbe a metà riga aspetta l'inizio della riga dopo (niente buchi).
         */
        fun layout(items: List<Tile>, rows: Int, sos: Tile?): List<Page> {
            val pages = mutableListOf(mutableListOf(Tile("clock", 0, 0, w = 2, type = TileType.CLOCK, color = "#FFFFFF")))
            var row = 1; var col = 0
            fun lastRow() = if (pages.size == 1) rows - 2 else rows - 1
            fun put(t: Tile) {
                if (row > lastRow()) { pages += mutableListOf<Tile>(); row = 0; col = 0 }
                pages.last() += t.copy(col = col, row = row, h = 1)
                col += t.w
                if (col >= 2) { col = 0; row++ }
            }
            val waiting = ArrayDeque<Tile>()
            for (t in items) {
                if (t.w == 2 && col == 1) { waiting += t; continue }
                put(t)
                while (col == 0 && waiting.isNotEmpty()) put(waiting.removeFirst())
            }
            if (col == 1 && waiting.isNotEmpty()) { col = 0; row++ }
            waiting.forEach(::put)
            pages[0] += (sos ?: Tile("sos", 0, 0, type = TileType.SOS, label = "SOS", color = "#C62828")).copy(col = 0, row = rows - 1, w = 2, h = 1)
            return pages.map { Page(cols = 2, rows = rows, tiles = it) }
        }
    }
}
