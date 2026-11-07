package app.semplicephone.safety

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioManager
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.semplicephone.BuildConfig
import app.semplicephone.R
import app.semplicephone.data.ConfigRepository
import app.semplicephone.data.Weather
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.Reminder
import app.semplicephone.model.TileType
import app.semplicephone.remote.Notice
import app.semplicephone.remote.RemoteEvents
import app.semplicephone.reminders.ReminderTime
import app.semplicephone.sos.LocationFetcher
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Controlli di sicurezza fatti ogni 15 minuti (e all'avvio): app di controllo remoto, SIM, uso del
 * telefono, caldo, zona sicura, volume delle chiamate, impostazioni di sistema, «buongiorno».
 * Ogni avviso parte una volta sola per evento (stato in prefs) e va nella coda degli eventi del portale.
 */
object Safety {
    private const val TAG = "Safety"
    private fun prefs(c: Context) = c.getSharedPreferences("safety", Context.MODE_PRIVATE)
    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun checkAll(context: Context, cfg: LauncherConfig = ConfigRepository.readCurrent(context)) {
        listOf<(Context, LauncherConfig) -> Unit>(
            ::remoteApps, ::sim, ::inactivity, ::callVolume, ::systemSettings, ::heat, ::safeZone, ::morning,
        ).forEach { step -> runCatching { step(context, cfg) }.onFailure { Log.w(TAG, "controllo non riuscito", it) } }
    }

    // ------------------------------------------------------------ 1. app di controllo remoto

    /** App usate dai finti tecnici/finte banche per prendere il controllo del telefono. */
    val REMOTE_APPS = mapOf(
        "com.anydesk.anydeskandroid" to "AnyDesk",
        "com.teamviewer.quicksupport.market" to "TeamViewer QuickSupport",
        "com.teamviewer.teamviewer.market.mobile" to "TeamViewer",
        "com.teamviewer.host.market" to "TeamViewer Host",
        "com.carriez.flutter_hbb" to "RustDesk",
        "com.rustdesk.rustdesk" to "RustDesk",
        "com.sand.airdroid" to "AirDroid",
        "com.sand.airdroidbiz" to "AirDroid Business",
        "com.sand.airmirror" to "AirMirror",
        "com.splashtop.remote.pad.v2" to "Splashtop",
        "com.splashtop.sos" to "Splashtop SOS",
        "com.logmein.rescuemobile" to "LogMeIn Rescue",
        "com.zoho.assist.agent" to "Zoho Assist",
        "com.supremocontrol.supremo" to "Supremo",
        "com.realvnc.viewer.android" to "VNC Viewer",
        "com.microsoft.rdc.androidx" to "Remote Desktop",
        "com.google.chromeremotedesktop" to "Chrome Remote Desktop",
        "com.iiordanov.freebVNC" to "bVNC",
        "com.ammyy.admin" to "Ammyy Admin",
        "net.ultraviewer.android" to "UltraViewer",
    )

    fun remoteApps(context: Context, cfg: LauncherConfig) {
        val pm = context.packageManager
        val present = REMOTE_APPS.keys.filter { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }.toSet()
        val p = prefs(context)
        val known = p.getStringSet("remoteApps", null)
        p.edit().putStringSet("remoteApps", present).apply()
        if (known == null || !cfg.phone.remoteAppAlert) return // primo controllo: si prende nota di ciò che c'è già
        (present - known).forEach { pkg ->
            val name = REMOTE_APPS.getValue(pkg)
            Notice.show(context, context.getString(R.string.safety_remote_title), context.getString(R.string.safety_remote_text, name), "alert")
            RemoteEvents.enqueue(context, "remote_app", context.getString(R.string.safety_remote_event, name), mapOf("pkg" to pkg))
        }
    }

    // ------------------------------------------------------------ 5. SIM tolta o cambiata

    fun sim(context: Context, cfg: LauncherConfig) {
        if (!cfg.phone.simAlert || !granted(context, Manifest.permission.READ_PHONE_STATE)) return
        val tm = context.getSystemService(TelephonyManager::class.java)
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val absent = tm.simState == TelephonyManager.SIM_STATE_ABSENT
        // Ogni SIM nuova riceve un numero di abbonamento nuovo: basta confrontare quelli.
        @Suppress("MissingPermission")
        val ids = runCatching { sm.activeSubscriptionInfoList.orEmpty().map { "${it.subscriptionId}:${it.carrierName}" }.sorted().joinToString(",") }.getOrDefault("")
        val now = if (absent) "absent" else ids
        val p = prefs(context)
        val before = p.getString("sim", null)
        p.edit().putString("sim", now).apply()
        if (before == null || before == now || now.isBlank()) return
        val text = context.getString(if (absent) R.string.safety_sim_removed else R.string.safety_sim_changed)
        RemoteEvents.enqueue(context, "sim_changed", text)
    }

    // ------------------------------------------------------------ 6. telefono non usato

    /** Da chiamare a ogni uso: schermo acceso, Home aperta, tocchi. */
    fun markUsed(context: Context) {
        val p = prefs(context)
        val wasAlerted = p.getBoolean("inactiveAlerted", false)
        p.edit().putLong("lastUse", System.currentTimeMillis()).putBoolean("inactiveAlerted", false).apply()
        if (wasAlerted) RemoteEvents.enqueue(context, "active_again", context.getString(R.string.safety_active_again))
    }

    fun inactivity(context: Context, cfg: LauncherConfig) {
        val hours = cfg.phone.inactivityHours
        if (context.getSystemService(PowerManager::class.java).isInteractive) { markUsed(context); return }
        if (hours <= 0) return
        val p = prefs(context)
        val last = p.getLong("lastUse", 0L).takeIf { it > 0 } ?: run { markUsed(context); return }
        val hour = LocalTime.now().hour
        if (hour !in 8..20 || p.getBoolean("inactiveAlerted", false)) return
        val idle = System.currentTimeMillis() - last
        if (idle < hours * 3_600_000L) return
        p.edit().putBoolean("inactiveAlerted", true).apply()
        RemoteEvents.enqueue(context, "inactive", context.getString(R.string.safety_inactive_event, idle / 3_600_000L))
    }

    // ------------------------------------------------------------ 13. volume delle chiamate

    fun callVolume(context: Context, cfg: LauncherConfig) {
        val pct = cfg.phone.callVolumePercent
        if (pct <= 0) return
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val want = (max * pct + 99) / 100
        if (am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) < want) runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, want, 0) }
    }

    // ------------------------------------------------------------ 14. testo grande, schermo, luminosità

    fun canWriteSettings(context: Context) = Settings.System.canWrite(context)

    fun systemSettings(context: Context, cfg: LauncherConfig) {
        val p = cfg.phone
        if ((p.fontScale <= 0f && p.screenTimeoutSeconds <= 0 && p.brightnessPercent <= 0) || !canWriteSettings(context)) return
        val cr = context.contentResolver
        if (p.fontScale > 0f && Settings.System.getFloat(cr, Settings.System.FONT_SCALE, 1f) != p.fontScale) {
            runCatching { Settings.System.putFloat(cr, Settings.System.FONT_SCALE, p.fontScale) }
        }
        if (p.screenTimeoutSeconds > 0) runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, p.screenTimeoutSeconds * 1000) }
        if (p.brightnessPercent > 0) runCatching {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (255 * p.brightnessPercent / 100).coerceIn(10, 255))
        }
    }

    // ------------------------------------------------------------ 11. caldo estremo

    private fun weatherTarget(cfg: LauncherConfig) = cfg.pages.flatMap { it.tiles }.firstOrNull { it.type == TileType.WEATHER && it.target.isNotBlank() }?.target

    fun heat(context: Context, cfg: LauncherConfig) {
        if (!cfg.phone.heatAlert) return
        val target = weatherTarget(cfg) ?: return
        val p = prefs(context)
        val today = LocalDate.now().toString()
        if (p.getString("heatDay", "") != today) {
            val w = Weather.current(context, target) ?: return
            val hot = w.max >= cfg.phone.heatThreshold
            p.edit().putString("heatDay", today).putBoolean("heatHot", hot).putInt("heatMax", w.max).putLong("heatLast", 0).apply()
            if (hot) RemoteEvents.enqueue(context, "heat", context.getString(R.string.safety_heat_event, w.max))
        }
        if (!p.getBoolean("heatHot", false)) return
        val hour = LocalTime.now().hour
        if (hour !in 10..18 || System.currentTimeMillis() - p.getLong("heatLast", 0) < 2 * 3_600_000L) return
        p.edit().putLong("heatLast", System.currentTimeMillis()).apply()
        Notice.show(context, context.getString(R.string.safety_heat_title), context.getString(R.string.safety_heat_text, p.getInt("heatMax", 0)), "water")
    }

    // ------------------------------------------------------------ 9. zona sicura (solo completa)

    fun safeZone(context: Context, cfg: LauncherConfig) {
        val p = cfg.phone
        if (!BuildConfig.FULL || p.safeZoneRadius <= 0 || (p.safeZoneLat == 0.0 && p.safeZoneLon == 0.0)) return
        if (!granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return
        val loc = kotlinx.coroutines.runBlocking { LocationFetcher(context).current(20_000) } ?: LocationFetcher(context).lastKnown() ?: return
        val d = FloatArray(1)
        Location.distanceBetween(p.safeZoneLat, p.safeZoneLon, loc.latitude, loc.longitude, d)
        val outside = d[0] > p.safeZoneRadius + loc.accuracy.coerceAtMost(200f)
        val pr = prefs(context)
        val was = pr.getBoolean("zoneOutside", false)
        if (outside == was) return
        pr.edit().putBoolean("zoneOutside", outside).apply()
        RemoteEvents.enqueue(
            context, if (outside) "zone_left" else "zone_back",
            context.getString(if (outside) R.string.safety_zone_left else R.string.safety_zone_back, d[0].toInt()),
            mapOf("lat" to loc.latitude, "lon" to loc.longitude),
        )
    }

    // ------------------------------------------------------------ 15. buongiorno parlato

    fun morning(context: Context, cfg: LauncherConfig) {
        if (!cfg.phone.morningBrief) return
        val at = ReminderTime.parse(cfg.phone.morningTime) ?: return
        val now = LocalTime.now()
        val start = at
        if (now.isBefore(start) || now.isAfter(start.plusHours(3))) return
        val p = prefs(context)
        val today = LocalDate.now().toString()
        if (p.getString("morningDay", "") == today) return
        p.edit().putString("morningDay", today).apply()
        Notice.show(context, context.getString(R.string.morning_title), morningText(context, cfg), "weather")
    }

    fun morningText(context: Context, cfg: LauncherConfig): String {
        val date = LocalDate.now()
        val loc = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val day = date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(loc))
        val parts = mutableListOf(context.getString(R.string.morning_today, day))
        weatherTarget(cfg)?.let { Weather.current(context, it) }?.let { w ->
            parts += context.getString(R.string.morning_weather, context.getString(Weather.description(w.code)), w.min, w.max)
        }
        val todays = cfg.reminders.filter { it.enabled && isToday(it, date) }.sortedBy { it.time }
        if (todays.isNotEmpty()) parts += context.getString(R.string.morning_agenda, todays.joinToString("; ") { "${it.time} ${it.title}" })
        return parts.joinToString(" ")
    }

    private fun isToday(r: Reminder, date: LocalDate): Boolean =
        if (r.date.isNotBlank()) r.date == date.toString() else r.days.isEmpty() || date.dayOfWeek.value in r.days

    // ------------------------------------------------------------ 19. medicine in esaurimento

    /** Pastiglie rimaste per [r] (null se non si contano). */
    fun stockLeft(context: Context, r: Reminder): Int? {
        if (r.stock < 0) return null
        val taken = prefs(context).getInt("taken:${r.id}:${r.stockSetAt}", 0)
        return (r.stock - taken * r.perDose.coerceAtLeast(1)).coerceAtLeast(0)
    }

    /** Da chiamare a ogni «Presa»: conta e, sotto la soglia, avvisa nonno e famiglia una volta. */
    fun onTaken(context: Context, r: Reminder) {
        if (r.stock < 0) return
        val p = prefs(context)
        val key = "taken:${r.id}:${r.stockSetAt}"
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
        val left = stockLeft(context, r) ?: return
        val warned = "stockWarned:${r.id}:${r.stockSetAt}"
        if (left > r.lowAt || p.getBoolean(warned, false)) return
        p.edit().putBoolean(warned, true).apply()
        Notice.show(context, context.getString(R.string.stock_title), context.getString(R.string.stock_text, r.title, left), "pill")
        RemoteEvents.enqueue(context, "pills_low", context.getString(R.string.stock_event, r.title, left), mapOf("reminder" to r.id, "left" to left))
    }
}
