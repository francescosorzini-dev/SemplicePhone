package app.semplicephone.data

import app.semplicephone.R
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Voce selezionabile come destinazione di un tile "app". */
data class AppEntry(val target: String, val label: String, val defaultLabel: String = label)

/**
 * Elenco app avviabili + ruoli "@xxx" (l'app predefinita del telefono per quella funzione).
 * La ricerca riprende quanto fatto sul fork di Discreet Launcher: per contenuto, senza accenti,
 * e per iniziali delle parole ("gm" → Google Maps).
 */
class AppCatalog(private val context: Context) {

    fun load(): List<AppEntry> {
        val la = context.getSystemService(LauncherApps::class.java)
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        val apps = la.getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != context.packageName }
            .map { AppEntry(it.componentName.packageName, it.label.toString()) }
            .distinctBy { it.target }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
        return roles() + apps
    }

    /** Ruoli (app predefinita del sistema), con le etichette nella lingua del telefono. */
    fun roles(): List<AppEntry> = listOf(
        Triple("@dialer", R.string.role_dialer, R.string.label_phone),
        Triple("@sms", R.string.role_sms, R.string.label_messages),
        Triple("@camera", R.string.role_camera, R.string.label_camera),
        Triple("@gallery", R.string.role_gallery, R.string.label_photos),
        Triple("@browser", R.string.role_browser, R.string.label_internet),
    ).map { (t, l, d) -> AppEntry(t, context.getString(l), context.getString(d)) }

    companion object {

        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

        /**
         * App che corrispondono a [query], prima quelle il cui nome (o una parola del nome) inizia
         * con la ricerca, poi le altre; a parità resta l'ordine alfabetico di [entries].
         */
        fun search(query: String, entries: List<AppEntry>): List<AppEntry> {
            val q = normalize(query.trim())
            if (q.isEmpty()) return entries
            fun startsWord(l: String) = normalize(l).split(Regex("[^\\p{L}\\p{N}]+")).any { it.startsWith(q) }
            return entries.filter { matches(query, it.label) }
                .sortedBy { e -> if (normalize(e.label).startsWith(q)) 0 else if (startsWord(e.label)) 1 else 2 }
        }

        fun matches(query: String, label: String): Boolean {
            val q = normalize(query.trim())
            if (q.isEmpty()) return true
            val l = normalize(label)
            if (l.contains(q)) return true
            val initials = l.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("") { it.take(1) }
            return initials.startsWith(q)
        }
    }
}
