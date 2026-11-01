package app.semplicephone.data

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
        val collator = Collator.getInstance(Locale.ITALIAN).apply { strength = Collator.PRIMARY }
        val apps = la.getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != context.packageName }
            .map { AppEntry(it.componentName.packageName, it.label.toString()) }
            .distinctBy { it.target }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
        return ROLES + apps
    }

    companion object {
        val ROLES = listOf(
            AppEntry("@dialer", "Telefono (app predefinita)", "Telefono"),
            AppEntry("@sms", "Messaggi (app predefinita)", "Messaggi"),
            AppEntry("@camera", "Fotocamera (app predefinita)", "Fotocamera"),
            AppEntry("@gallery", "Galleria foto (app predefinita)", "Foto"),
            AppEntry("@browser", "Internet (app predefinita)", "Internet"),
        )

        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

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
