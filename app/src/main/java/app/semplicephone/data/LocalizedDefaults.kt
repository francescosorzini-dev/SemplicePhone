package app.semplicephone.data

import android.content.Context
import android.content.res.Configuration
import app.semplicephone.R
import app.semplicephone.model.LauncherConfig
import java.util.Locale

/**
 * Testi predefiniti salvati nella config («Telefono», «Messaggi», «È ora della medicina»,
 * «Ho bisogno di aiuto.»…) nella lingua in cui sono stati creati, sul telefono o nel portale.
 * Se un testo coincide con un predefinito in una qualsiasi delle lingue dell'app, si mostra (e si
 * legge ad alta voce) nella lingua attuale; i testi scritti a mano restano come sono.
 */
object LocalizedDefaults {
    private val LANGS = listOf("it", "en", "de", "fr", "es")

    /** Nomi predefiniti dei riquadri, sul telefono e nel portale («Famiglia» per le foto). */
    private val TILE_LABELS = listOf(
        R.string.label_phone, R.string.label_messages, R.string.label_camera, R.string.label_photos, R.string.label_album,
        R.string.label_family, R.string.label_internet, R.string.label_app, R.string.label_call,
        R.string.label_video, R.string.label_message, R.string.label_shortcut, R.string.label_missed_calls,
        R.string.label_search, R.string.label_messages_unread, R.string.label_radio,
    )
    private val REMINDER_TITLES = listOf(
        R.string.default_reminder_title, R.string.preset_medicine, R.string.preset_shower, R.string.preset_shopping,
        R.string.preset_visit, R.string.preset_cards, R.string.preset_walk, R.string.preset_water, R.string.preset_meal,
    )

    /** Testo in tutte le lingue → risorsa. Le lingue non cambiano a runtime: si calcola una volta. */
    @Volatile private var index: Map<String, Int>? = null

    private fun index(context: Context): Map<String, Int> = index ?: synchronized(this) {
        index ?: HashMap<String, Int>().also { map ->
            for (lang in LANGS) {
                val conf = Configuration(context.resources.configuration).apply { setLocale(Locale(lang)) }
                val res = context.createConfigurationContext(conf).resources
                for (id in TILE_LABELS + REMINDER_TITLES + R.string.default_sos_message) {
                    map.putIfAbsent(key(res.getString(id)), id)
                }
            }
        }.also { index = it }
    }

    private fun key(s: String) = s.trim().lowercase(Locale.ROOT)

    /** [text] nella lingua attuale se è un predefinito di uno dei gruppi [allowed], altrimenti invariato. */
    private fun localize(context: Context, text: String, allowed: Collection<Int>): String {
        if (text.isBlank()) return text
        val id = index(context)[key(text)] ?: return text
        return if (id in allowed) context.getString(id) else text
    }

    fun tileLabel(context: Context, label: String) = localize(context, label, TILE_LABELS)

    /** Config con i testi predefiniti nella lingua attuale (per Home, voce, allarmi, SMS SOS ed editor). */
    fun apply(context: Context, cfg: LauncherConfig): LauncherConfig = cfg.copy(
        pages = cfg.pages.map { p -> p.copy(tiles = p.tiles.map { t -> t.copy(label = tileLabel(context, t.label)) }) },
        reminders = cfg.reminders.map { r -> r.copy(title = localize(context, r.title, REMINDER_TITLES)) },
        sos = cfg.sos.copy(message = localize(context, cfg.sos.message, listOf(R.string.default_sos_message))),
    )
}
