package app.semplicephone.data

import android.content.Context
import android.util.Log
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.Page
import app.semplicephone.model.Tile
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

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val file: File get() = File(context.filesDir, "config.json")

    private val _config = MutableStateFlow(LauncherConfig())
    val config: StateFlow<LauncherConfig> = _config.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val local = file.takeIf { it.exists() }?.let { parseOrNull(it.readText(), "locale") }
        val cfg = local ?: parseOrNull(
            context.assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() },
            "default",
        ) ?: LauncherConfig()
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

    private fun parseOrNull(text: String, origin: String): LauncherConfig? = try {
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

    companion object {
        private const val TAG = "ConfigRepository"
        private const val DEFAULT_ASSET = "default_config.json"

        /** Controlli strutturali: tile dentro la griglia, nessuna sovrapposizione, id unici. */
        fun validate(cfg: LauncherConfig): List<String> {
            val out = mutableListOf<String>()
            if (cfg.pages.isEmpty()) out += "nessuna pagina"
            val ids = cfg.pages.flatMap { p -> p.tiles.map(Tile::id) }
            ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { out += "id duplicato $it" }
            cfg.pages.forEachIndexed { pi, page -> out += validatePage(pi, page) }
            return out
        }

        private fun validatePage(pi: Int, page: Page): List<String> {
            val out = mutableListOf<String>()
            if (page.cols !in 1..4 || page.rows !in 1..8) out += "pagina $pi: griglia ${page.cols}x${page.rows} fuori limiti"
            val occupied = Array(page.rows.coerceAtLeast(0)) { arrayOfNulls<String>(page.cols.coerceAtLeast(0)) }
            for (t in page.tiles) {
                if (t.w < 1 || t.h < 1 || t.col < 0 || t.row < 0 ||
                    t.col + t.w > page.cols || t.row + t.h > page.rows
                ) {
                    out += "pagina $pi: tile ${t.id} fuori griglia"
                    continue
                }
                for (r in t.row until t.row + t.h) for (c in t.col until t.col + t.w) {
                    val prev = occupied[r][c]
                    if (prev != null) out += "pagina $pi: ${t.id} sovrapposto a $prev"
                    occupied[r][c] = t.id
                }
            }
            return out
        }
    }
}
