package app.semplicephone.launch

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.semplicephone.BuildConfig
import app.semplicephone.model.IconKind
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.TileType
import app.semplicephone.phone.Badges

/**
 * Permessi derivati dalla config: si chiedono solo quelli che servono, al salvataggio dell'editor
 * (mai alla persona anziana mentre usa la Home).
 */
object Permissions {

    data class Need(val permission: String, val why: String)

    /** Accessi speciali che non sono permessi runtime: si concedono da una pagina di sistema. */
    data class Special(val key: String, val label: String, val why: String, val settingsAction: String)

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
        if (cfg.phone.batteryAlertPercent > 0 && cfg.sos.numbers.isNotEmpty()) {
            out.putIfAbsent(Manifest.permission.SEND_SMS, "SMS ai familiari con batteria scarica")
        }
        if (cfg.phone.smsUpdateCommand && BuildConfig.FULL) out[Manifest.permission.RECEIVE_SMS] = "comando SMS «#AGGIORNA» dai familiari"
        if (cfg.reminders.isNotEmpty() && Build.VERSION.SDK_INT >= 33) {
            out[Manifest.permission.POST_NOTIFICATIONS] = "promemoria farmaci"
        }
        return out.map { (p, w) -> Need(p, w) }
    }

    fun missing(context: Context, cfg: LauncherConfig): List<Need> =
        required(cfg).filter { !granted(context, it.permission) }

    fun requiredSpecial(cfg: LauncherConfig): List<Special> = buildList {
        if (cfg.phone.badges && BuildConfig.FULL) add(
            Special("badges", "Accesso alle notifiche", "pallino con chiamate perse e messaggi sui riquadri",
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )
        if (cfg.phone.ringerLock) add(
            Special("dnd", "Accesso a «Non disturbare»", "togliere il silenzioso se viene attivato per errore",
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
        )
    }

    fun missingSpecial(context: Context, cfg: LauncherConfig): List<Special> = requiredSpecial(cfg).filter {
        when (it.key) {
            "badges" -> !Badges.isEnabled(context)
            "dnd" -> !context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
            else -> false
        }
    }

    fun openSpecial(context: Context, s: Special) {
        runCatching { context.startActivity(Intent(s.settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
