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
        val types = tiles.map { it.type }.toSet()
        if (TileType.MAGNIFIER in types) out[Manifest.permission.CAMERA] = s(R.string.perm_magnifier)
        if (TileType.WHERE_AM_I in types) {
            out.putIfAbsent(Manifest.permission.ACCESS_FINE_LOCATION, s(R.string.perm_where))
            out.putIfAbsent(Manifest.permission.ACCESS_COARSE_LOCATION, s(R.string.perm_where))
        }
        if (TileType.CALL_ME in types && BuildConfig.SEND_SMS) out.putIfAbsent(Manifest.permission.SEND_SMS, s(R.string.perm_call_me))
        if (TileType.CALL_ME in types || TileType.TG_VIDEO in types) out.putIfAbsent(Manifest.permission.READ_CONTACTS, s(R.string.perm_contacts))
        if (cfg.phone.simAlert || cfg.phone.longCallMinutes > 0) out.putIfAbsent(Manifest.permission.READ_PHONE_STATE, s(R.string.perm_phone_state))
        if (cfg.phone.longCallMinutes > 0 || cfg.phone.scamGuard) out.putIfAbsent(Manifest.permission.READ_CONTACTS, s(R.string.perm_contacts))
        if (BuildConfig.FULL && cfg.phone.safeZoneRadius > 0) {
            out.putIfAbsent(Manifest.permission.ACCESS_FINE_LOCATION, s(R.string.perm_safe_zone))
            out[Manifest.permission.ACCESS_BACKGROUND_LOCATION] = s(R.string.perm_safe_zone)
        }
        val notices = cfg.phone.heatAlert || cfg.phone.morningBrief || cfg.phone.fallDetection || cfg.phone.remoteAppAlert
        if ((cfg.reminders.isNotEmpty() || notices) && Build.VERSION.SDK_INT >= 33) {
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
        if (cfg.phone.fontScale > 0f || cfg.phone.screenTimeoutSeconds > 0 || cfg.phone.brightnessPercent > 0) add(
            Special("write_settings", context.getString(R.string.special_write_settings), context.getString(R.string.special_write_settings_why),
                Settings.ACTION_MANAGE_WRITE_SETTINGS),
        )
        if (cfg.phone.blockPremium || cfg.phone.blockInternational || cfg.phone.longCallMinutes > 0) add(
            Special("redirect", context.getString(R.string.special_redirect), context.getString(R.string.special_redirect_why), ""),
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
            "write_settings" -> !Settings.System.canWrite(context)
            "redirect" -> context.getSystemService(android.app.role.RoleManager::class.java).let {
                it.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_REDIRECTION) && !it.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_REDIRECTION)
            }
            else -> false
        }
    }

    /**
     * Apre una pagina di sistema; se Android non la offre (o la rifiuta) apre almeno le
     * «Informazioni app» di SemplicePhone, così il pulsante non resta mai senza effetto.
     */
    fun openSystemPage(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Intent per chiedere il ruolo (va lanciato con startActivityForResult: con startActivity Android lo chiude subito). */
    fun roleIntent(context: Context, s: Special): Intent? =
        if (s.key != "redirect") null
        else context.getSystemService(android.app.role.RoleManager::class.java)
            .takeIf { it.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_REDIRECTION) }
            ?.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_REDIRECTION)

    fun openSpecial(context: Context, s: Special) {
        if (s.key == "redirect") return // vedi roleIntent: lo lancia l'Activity con un launcher
        val intent = Intent(s.settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // La pagina della sovrapposizione va aperta direttamente sull'app, non sull'elenco.
        if (s.key == "overlay" || s.key == "write_settings") intent.data = android.net.Uri.parse("package:${context.packageName}")
        // Accesso alle notifiche: direttamente sulla pagina di SemplicePhone; se Android lo tiene
        // bloccato (app scaricata dal browser) toccando l'interruttore spiega come sbloccarlo.
        if (s.key == "badges") {
            intent.setAction(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    android.content.ComponentName(context, "app.semplicephone.phone.BadgeListener").flattenToString())
        }
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) openSystemPage(context, Intent(s.settingsAction))
    }

    /** Android 13+ blocca l'accesso alle notifiche delle app installate dal browser finché non lo si sblocca. */
    private fun restricted(): Boolean = android.os.Build.VERSION.SDK_INT >= 33 && BuildConfig.EDITION == "standard"

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
