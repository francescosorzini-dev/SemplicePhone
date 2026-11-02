package app.semplicephone.launch

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.os.Process
import android.util.Log
import app.semplicephone.data.AppCatalog
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.TileType

/** Scorciatoia di un'app (es. «Chat con Marco» di WhatsApp) proposta nell'editor. */
data class ShortcutEntry(val pkg: String, val id: String, val label: String, val appLabel: String) {
    val target get() = "$pkg/$id"
}

/**
 * Scorciatoie delle app: elenco per l'editor e «pin» di quelle usate dai riquadri, così l'app
 * non le può far sparire. Funziona solo se SemplicePhone è il launcher predefinito.
 */
object ShortcutPins {

    private const val TAG = "ShortcutPins"
    private const val FLAGS = ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_MANIFEST or ShortcutQuery.FLAG_MATCH_PINNED

    fun canUse(context: Context): Boolean =
        runCatching { context.getSystemService(LauncherApps::class.java).hasShortcutHostPermission() }.getOrDefault(false)

    /** Tutte le scorciatoie disponibili, raggruppate per app (chiamare fuori dal main thread). */
    fun available(context: Context): List<ShortcutEntry> {
        if (!canUse(context)) return emptyList()
        val la = context.getSystemService(LauncherApps::class.java)
        val user = Process.myUserHandle()
        return AppCatalog(context).load().filterNot { it.target.startsWith("@") }.flatMap { app ->
            runCatching {
                la.getShortcuts(ShortcutQuery().setPackage(app.target).setQueryFlags(FLAGS), user).orEmpty()
                    .filter { it.isEnabled }
                    .map { ShortcutEntry(app.target, it.id, (it.shortLabel ?: it.longLabel ?: it.id).toString(), app.label) }
            }.getOrDefault(emptyList())
        }.distinctBy { it.target }
    }

    /** Fissa le scorciatoie usate dalla config e libera quelle non più usate. */
    fun sync(context: Context, cfg: LauncherConfig) {
        if (!canUse(context)) return
        val la = context.getSystemService(LauncherApps::class.java)
        val user = Process.myUserHandle()
        val wanted = cfg.pages.flatMap { it.tiles }
            .filter { it.type == TileType.SHORTCUT && '/' in it.target }
            .groupBy({ it.target.substringBefore('/') }, { it.target.substringAfter('/') })
        val pinnedPkgs = runCatching {
            la.getShortcuts(ShortcutQuery().setQueryFlags(ShortcutQuery.FLAG_MATCH_PINNED), user).orEmpty().map { it.`package` }.toSet()
        }.getOrDefault(emptySet())
        (pinnedPkgs + wanted.keys).forEach { pkg ->
            runCatching { la.pinShortcuts(pkg, wanted[pkg].orEmpty().distinct(), user) }
                .onFailure { Log.w(TAG, "pin scorciatoie $pkg non riuscito", it) }
        }
    }
}
