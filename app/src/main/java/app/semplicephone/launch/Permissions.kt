package app.semplicephone.launch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.semplicephone.model.IconKind
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.TileType

/**
 * Permessi runtime derivati dalla config: si chiedono solo quelli che servono ai tile presenti,
 * al salvataggio dell'editor (mai alla persona anziana mentre usa la Home).
 */
object Permissions {

    data class Need(val permission: String, val why: String)

    fun required(cfg: LauncherConfig): List<Need> {
        val tiles = cfg.pages.flatMap { it.tiles }
        val out = linkedMapOf<String, String>()
        if (tiles.any { it.type == TileType.CALL }) out[Manifest.permission.CALL_PHONE] = "chiamare direttamente dai tile"
        if (tiles.any { it.type == TileType.WA_VIDEO || it.icon.kind == IconKind.PHOTO ||
                ((it.type == TileType.CALL || it.type == TileType.SMS) && it.icon.kind == IconKind.AUTO) }
        ) out[Manifest.permission.READ_CONTACTS] = "foto dei contatti e videochiamate WhatsApp"
        if (tiles.any { it.type == TileType.SOS }) {
            out.putIfAbsent(Manifest.permission.CALL_PHONE, "chiamata SOS")
            out[Manifest.permission.SEND_SMS] = "SMS di emergenza"
            out[Manifest.permission.ACCESS_FINE_LOCATION] = "posizione nell'SMS di emergenza"
            out[Manifest.permission.ACCESS_COARSE_LOCATION] = "posizione nell'SMS di emergenza"
        }
        if (cfg.reminders.isNotEmpty() && Build.VERSION.SDK_INT >= 33) {
            out[Manifest.permission.POST_NOTIFICATIONS] = "promemoria farmaci"
        }
        return out.map { (p, w) -> Need(p, w) }
    }

    fun missing(context: Context, cfg: LauncherConfig): List<Need> =
        required(cfg).filter { !granted(context, it.permission) }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
