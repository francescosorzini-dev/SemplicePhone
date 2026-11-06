package app.semplicephone.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import app.semplicephone.R
import app.semplicephone.data.ConfigRepository
import app.semplicephone.model.PhoneConfig
import app.semplicephone.remote.Notice
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * «Metti in carica» la sera: all'ora scelta, se la batteria è sotto la soglia e il telefono non è
 * in carica, avviso a tutto schermo e a voce. Se dopo un'ora ancora niente, lo ripete una volta.
 */
object ChargeReminder {
    private const val ACTION = "app.semplicephone.CHARGE_REMINDER"
    private const val EXTRA_RETRY = "retry"

    /** Pura (testabile): prossimo momento dell'avviso dopo [now]. */
    fun nextAt(phone: PhoneConfig, now: ZonedDateTime): ZonedDateTime {
        val t = runCatching { LocalTime.parse(phone.chargeTime) }.getOrDefault(LocalTime.of(20, 0))
        val today = now.with(t).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** Pura (testabile): l'avviso va mostrato? */
    fun shouldWarn(phone: PhoneConfig, level: Int, charging: Boolean): Boolean =
        phone.chargeReminder && !charging && level in 0 until phone.chargeBelow

    private fun pending(context: Context, retry: Boolean) = PendingIntent.getBroadcast(
        context, if (retry) 0xC401 else 0xC400,
        Intent(context, ChargeReceiver::class.java).setAction(ACTION).putExtra(EXTRA_RETRY, retry),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(context: Context, phone: PhoneConfig) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context, false))
        if (!phone.chargeReminder) { am.cancel(pending(context, true)); return }
        val at = nextAt(phone, ZonedDateTime.now()).toInstant().toEpochMilli()
        // Finestra di 10 minuti: precisione sufficiente senza sveglie esatte.
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pending(context, false))
    }

    internal fun fired(context: Context, retry: Boolean) {
        val phone = ConfigRepository.readCurrent(context).phone
        if (!retry) schedule(context, phone)
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        if (!shouldWarn(phone, level, charging)) return
        Notice.show(
            context,
            context.getString(R.string.charge_title),
            context.getString(R.string.charge_text, level),
            symbol = "battery",
        )
        if (!retry) {
            context.getSystemService(AlarmManager::class.java)
                .setWindow(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60 * 60_000L, 10 * 60_000L, pending(context, true))
        }
    }
}

class ChargeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ChargeReminder.fired(context, intent.getBooleanExtra("retry", false))
    }
}
