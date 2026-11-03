package app.semplicephone.data

import app.semplicephone.R
import android.content.Context
import android.util.Log
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.Page
import app.semplicephone.model.Tile
import app.semplicephone.model.TileType
import app.semplicephone.reminders.ReminderTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Legge/scrive la config. Ordine di caricamento: filesDir/config.json → assets/default_config.json.
 * Una config non valida non viene mai applicata: si resta sull'ultima valida.
 */
class ConfigRepository(private val context: Context) {

    private val file: File get() = File(context.filesDir, CONFIG_FILE)

    private val _config = MutableStateFlow(LauncherConfig())
    val config: StateFlow<LauncherConfig> = _config.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val local = file.takeIf { it.exists() }?.let { parseOrNull(it.readText(), "locale") }
        val cfg = local ?: localizeDefault(
            context,
            parseOrNull(context.assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() }, "default") ?: LauncherConfig(),
        )
        _config.value = cfg
    }

    suspend fun save(cfg: LauncherConfig) = withContext(Dispatchers.IO) {
        val problems = validate(cfg)
        require(problems.isEmpty()) { problems.joinToString("; ") }
        // Scrittura atomica: tmp + rename, così un crash non lascia un file troncato.
        val tmp = File(context.filesDir, "config.json.tmp")
        tmp.writeText(json.encodeToString(LauncherConfig.serializer(), cfg))
        if (!tmp.renameTo(file)) error("rename config fallito")
        _config.value = cfg
    }

    /** Config predefinita degli asset (per "Ripristina predefiniti" nell'editor). */
    suspend fun defaultConfig(): LauncherConfig = withContext(Dispatchers.IO) {
        localizeDefault(
            context,
            parseOrNull(context.assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() }, "default") ?: LauncherConfig(),
        )
    }

    private fun parseOrNull(text: String, origin: String): LauncherConfig? = parse(text, origin)

    companion object {
        private const val TAG = "ConfigRepository"
        private const val DEFAULT_ASSET = "default_config.json"
        private const val CONFIG_FILE = "config.json"

        @Volatile private var instance: ConfigRepository? = null

        /** Istanza unica di processo: Home, editor e sync remota condividono lo stesso StateFlow. */
        fun get(context: Context): ConfigRepository = instance ?: synchronized(this) {
            instance ?: ConfigRepository(context.applicationContext).also { instance = it }
        }

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** Parse + validazione; null (con log) se il testo non è una config valida. */
        fun parse(text: String, origin: String): LauncherConfig? = try {
            val cfg = json.decodeFromString(LauncherConfig.serializer(), text)
            val problems = validate(cfg)
            if (problems.isEmpty()) cfg else {
                Log.w(TAG, "config $origin scartata: ${problems.joinToString("; ")}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "config $origin non leggibile", e)
            null
        }

        /** Lettura sincrona per receiver e activity secondarie (file piccolo, ok fuori dal main thread o al boot). */
        fun readCurrent(context: Context): LauncherConfig {
            val f = File(context.filesDir, CONFIG_FILE)
            return f.takeIf { it.exists() }?.let { parse(it.readText(), "locale") }
                ?: localizeDefault(
                    context,
                    parse(context.assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() }, "default") ?: LauncherConfig(),
                )
        }

        /** Config predefinita con etichette e messaggio SOS nella lingua del telefono. */
        fun localizeDefault(context: Context, cfg: LauncherConfig): LauncherConfig {
            val labels = mapOf(
                "dialer" to R.string.label_phone, "sms" to R.string.label_messages, "camera" to R.string.label_camera,
                "gallery" to R.string.label_photos, "browser" to R.string.label_internet,
            )
            return cfg.copy(
                pages = cfg.pages.map { p -> p.copy(tiles = p.tiles.map { t -> labels[t.id]?.let { t.copy(label = context.getString(it)) } ?: t }) },
                sos = cfg.sos.copy(message = context.getString(R.string.default_sos_message)),
            )
        }

        /** Controlli strutturali: tile dentro la griglia, nessuna sovrapposizione, id unici. */
        fun validate(cfg: LauncherConfig): List<String> {
            val out = mutableListOf<String>()
            if (cfg.pages.isEmpty()) out += "no pages"
            val ids = cfg.pages.flatMap { p -> p.tiles.map(Tile::id) }
            ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { out += "duplicate id $it" }
            cfg.pages.forEachIndexed { pi, page -> out += validatePage(pi, page) }
            cfg.reminders.forEach { r ->
                if (ReminderTime.parse(r.time) == null) out += "reminder \"${r.title}\": invalid time"
                if (r.date.isNotBlank() && ReminderTime.parseDate(r.date) == null) out += "reminder \"${r.title}\": invalid date"
            }
            cfg.reminders.groupingBy { it.id }.eachCount().filter { it.value > 1 }.keys.forEach { out += "duplicate reminder $it" }
            return out
        }

        /** Problema di [tile] dentro [page] (fuori griglia o sovrapposto ad altri), null se va bene. */
        fun conflict(page: Page, tile: Tile): TileConflict? {
            if (tile.col < 0 || tile.row < 0 || tile.col + tile.w > page.cols || tile.row + tile.h > page.rows) {
                return TileConflict.OutOfGrid(page.cols, page.rows)
            }
            val other = page.tiles.firstOrNull { o ->
                o.id != tile.id &&
                    tile.col < o.col + o.w && o.col < tile.col + tile.w &&
                    tile.row < o.row + o.h && o.row < tile.row + tile.h
            }
            return other?.let { TileConflict.Overlap(it) }
        }

        /** Prima cella libera di [page], o null se piena. */
        fun firstFreeCell(page: Page): Pair<Int, Int>? {
            for (r in 0 until page.rows) for (c in 0 until page.cols) {
                val probe = Tile(id = "\u0000probe", col = c, row = r, type = TileType.APP)
                if (conflict(page, probe) == null) return c to r
            }
            return null
        }

        private fun validatePage(pi: Int, page: Page): List<String> {
            val out = mutableListOf<String>()
            if (page.cols !in 1..4 || page.rows !in 1..8) out += "page ${pi + 1}: grid ${page.cols}x${page.rows} out of range"
            val occupied = Array(page.rows.coerceAtLeast(0)) { arrayOfNulls<String>(page.cols.coerceAtLeast(0)) }
            for (t in page.tiles) {
                if (t.w < 1 || t.h < 1 || t.col < 0 || t.row < 0 ||
                    t.col + t.w > page.cols || t.row + t.h > page.rows
                ) {
                    out += "page ${pi + 1}: tile ${t.id} outside the grid"
                    continue
                }
                for (r in t.row until t.row + t.h) for (c in t.col until t.col + t.w) {
                    val prev = occupied[r][c]
                    if (prev != null) out += "page ${pi + 1}: ${t.id} overlaps $prev"
                    occupied[r][c] = t.id
                }
            }
            return out
        }
    }
}

/** Conflitto di un tile con la griglia o con un altro tile (vedi [ConfigRepository.conflict]). */
sealed interface TileConflict {
    data class OutOfGrid(val cols: Int, val rows: Int) : TileConflict
    data class Overlap(val other: Tile) : TileConflict
}
