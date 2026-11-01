package app.semplicephone.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import app.semplicephone.MainActivity
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.Reminder
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime

/**
 * Per ogni promemoria due allarmi indipendenti: "daily" (prossima occorrenza) e "repeat"
 * (ripetizione finché non viene confermato). setAlarmClock è esente da Doze e mostra
 * l'icona sveglia nella barra: è il meccanismo pensato per gli allarmi a orario fisso.
 */
class ReminderScheduler(private val context: Context) {

    private val am = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    fun canFullScreen(): Boolean = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun scheduleAll(cfg: LauncherConfig) {
        ensureChannel(context)
        val active = cfg.reminders.filter { it.enabled }
        val previous = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty()
        (previous - active.map { it.id }.toSet()).forEach { cancel(it) }
        active.forEach { scheduleDaily(it) }
        prefs.edit().putStringSet(KEY_IDS, active.map { it.id }.toSet()).apply()
    }

    fun scheduleDaily(r: Reminder, now: ZonedDateTime = ZonedDateTime.now()) {
        val at = ReminderTime.next(r, now) ?: return
        set(at.toInstant().toEpochMilli(), pending(r.id, attempt = 0, daily = true))
    }

    fun scheduleRepeat(id: String, attempt: Int, atMillis: Long) {
        set(atMillis, pending(id, attempt, daily = false))
    }

    fun cancelRepeat(id: String) = am.cancel(pending(id, 0, daily = false))

    fun cancel(id: String) {
        am.cancel(pending(id, 0, daily = true))
        cancelRepeat(id)
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
    }

    private fun set(atMillis: Long, op: PendingIntent) {
        try {
            if (canScheduleExact()) {
                val show = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), op)
            } else {
                // Senza permesso esatto (solo Android 12) l'allarme può slittare di qualche minuto.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, op)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "programmazione allarme negata", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, op)
        }
    }

    private fun pending(id: String, attempt: Int, daily: Boolean): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(ReminderReceiver.EXTRA_ID, id)
            .putExtra(ReminderReceiver.EXTRA_ATTEMPT, attempt)
        val code = notificationId(id) * 2 + if (daily) 0 else 1
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val TAG = "ReminderScheduler"
        private const val KEY_IDS = "scheduled_ids"
        const val CHANNEL = "reminders_alarm"

        fun notificationId(id: String): Int = (id.hashCode() and 0x3FFFFFFF)

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) != null) return
            val ch = NotificationChannel(CHANNEL, "Promemoria farmaci", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Allarmi dei promemoria"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                setBypassDnd(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
            }
            nm.createNotificationChannel(ch)
        }
    }
}

/** Registro esiti (preso / rinviato / non confermato), una riga JSON per evento. */
object ReminderLog {
    fun append(context: Context, id: String, outcome: String) {
        runCatching {
            val line = JSONObject().put("id", id).put("outcome", outcome).put("at", System.currentTimeMillis()).toString()
            val f = File(context.filesDir, "reminders_log.jsonl")
            // Tetto alla dimensione: si tengono le ultime ~2000 righe.
            if (f.length() > 256 * 1024) f.writeText(f.readLines().takeLast(2000).joinToString("\n", postfix = "\n"))
            f.appendText(line + "\n")
        }
    }
}
