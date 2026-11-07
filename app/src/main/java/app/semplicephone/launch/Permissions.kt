package app.semplicephone.launch

import app.semplicephone.R
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

    fun required(context: Context, cfg: LauncherConfig): List<Need> {
        fun s(id: Int, vararg a: Any) = context.getString(id, *a)
        val tiles = cfg.pages.flatMap { it.tiles }
        val out = linkedMapOf<String, String>()
        if (tiles.any { it.type == TileType.CALL }) out[Manifest.permission.CALL_PHONE] = s(R.string.perm_call_tiles)
        if (tiles.any { it.type == TileType.WA_VIDEO || it.type == TileType.TG_VIDEO || it.icon.kind == IconKind.PHOTO ||
                ((it.type == TileType.CALL || it.type == TileType.SMS) && it.icon.kind == IconKind.AUTO) }
        ) out[Manifest.permission.READ_CONTACTS] = s(R.string.perm_contacts)
        if (tiles.any { it.type == TileType.MISSED_CALLS } && BuildConfig.CALL_LOG) {
            out[Manifest.permission.READ_CALL_LOG] = s(R.string.perm_missed_calls)
            out.putIfAbsent(Manifest.permission.READ_CONTACTS, s(R.string.perm_missed_contacts))
            out.putIfAbsent(Manifest.permission.CALL_PHONE, s(R.string.perm_call_tiles))
        }
        // Il filtro chiamate riconosce i contatti solo leggendo la rubrica: senza, non blocca nessuno.
        if (cfg.phone.blockUnknown) out.putIfAbsent(Manifest.permission.READ_CONTACTS, s(R.string.perm_block_contacts))
        if (cfg.phone.autoAnswer) {
            out[Manifest.permission.ANSWER_PHONE_CALLS] = s(R.string.perm_auto_answer)
            out[Manifest.permission.READ_PHONE_STATE] = s(R.string.perm_auto_answer)
        }
        if (cfg.phone.announceCaller) out.putIfAbsent(Manifest.permission.READ_CONTACTS, s(R.string.perm_announce_contacts))
        if (tiles.any { it.type == TileType.SOS }) {
            out.putIfAbsent(Manifest.permission.CALL_PHONE, s(R.string.perm_sos_call))
            if (BuildConfig.SEND_SMS) out[Manifest.permission.SEND_SMS] = s(R.string.perm_sos_sms)
            out[Manifest.permission.ACCESS_FINE_LOCATION] = s(R.string.perm_sos_location)
            out[Manifest.permission.ACCESS_COARSE_LOCATION] = s(R.string.perm_sos_location)
        }
        if (BuildConfig.SEND_SMS && cfg.phone.batteryAlertPercent > 0 && cfg.sos.numbers.isNotEmpty()) {
            out.putIfAbsent(Manifest.permission.SEND_SMS, s(R.string.perm_battery_sms))
        }
        if (cfg.phone.smsUpdateCommand && BuildConfig.FULL) out[Manifest.permission.RECEIVE_SMS] = s(R.string.perm_sms_command, s(R.string.sms_keyword))
        if (cfg.reminders.isNotEmpty() && Build.VERSION.SDK_INT >= 33) {
            out[Manifest.permission.POST_NOTIFICATIONS] = s(R.string.perm_reminders)
        }
        return out.map { (p, w) -> Need(p, w) }
    }

    fun missing(context: Context, cfg: LauncherConfig): List<Need> =
        required(context, cfg).filter { !granted(context, it.permission) }

    fun requiredSpecial(context: Context, cfg: LauncherConfig): List<Special> = buildList {
        val messagesTile = cfg.pages.any { p -> p.tiles.any { it.type == TileType.MESSAGES } }
        if ((cfg.phone.badges || messagesTile) && BuildConfig.NOTIFICATIONS) add(
            Special("badges", context.getString(R.string.special_badges),
                context.getString(R.string.special_badges_why) + (if (restricted()) " " + context.getString(R.string.special_badges_restricted) else ""),
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )
        if (cfg.phone.homeButton) add(
            Special("overlay", context.getString(R.string.special_overlay), context.getString(R.string.special_overlay_why),
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )
        if (cfg.phone.ringerLock) add(
            Special("dnd", context.getString(R.string.special_dnd), context.getString(R.string.special_dnd_why),
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
        )
    }

    fun missingSpecial(context: Context, cfg: LauncherConfig): List<Special> = requiredSpecial(context, cfg).filter {
        when (it.key) {
            "badges" -> !Badges.isEnabled(context)
            "dnd" -> !context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
            "overlay" -> !Settings.canDrawOverlays(context)
            else -> false
        }
    }

    fun openSpecial(context: Context, s: Special) {
        val intent = Intent(s.settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // La pagina della sovrapposizione va aperta direttamente sull'app, non sull'elenco.
        if (s.key == "overlay") intent.data = android.net.Uri.parse("package:${context.packageName}")
        // Accesso alle notifiche: direttamente sulla pagina di SemplicePhone; se Android lo tiene
        // bloccato (app scaricata dal browser) toccando l'interruttore spiega come sbloccarlo.
        if (s.key == "badges") {
            intent.setAction(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    android.content.ComponentName(context, "app.semplicephone.phone.BadgeListener").flattenToString())
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(s.settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Android 13+ blocca l'accesso alle notifiche delle app installate dal browser finché non lo si sblocca. */
    private fun restricted(): Boolean = android.os.Build.VERSION.SDK_INT >= 33 && BuildConfig.EDITION == "standard"

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
