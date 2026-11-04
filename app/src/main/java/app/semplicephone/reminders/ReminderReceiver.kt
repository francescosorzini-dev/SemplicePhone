package app.semplicephone.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.semplicephone.R
import app.semplicephone.data.ConfigRepository
import app.semplicephone.model.Reminder

/** Allarme scattato, conferma "preso", rinvio, e riprogrammazione dopo riavvio/cambio ora. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val scheduler = ReminderScheduler(context)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> scheduler.scheduleAll(ConfigRepository.readCurrent(context))
            ACTION_FIRE -> fire(context, scheduler, intent)
            ACTION_TAKEN -> taken(context, intent.getStringExtra(EXTRA_ID) ?: return)
            ACTION_SNOOZE -> snooze(context, intent.getStringExtra(EXTRA_ID) ?: return)
        }
    }

    private fun fire(context: Context, scheduler: ReminderScheduler, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
        val r = app.semplicephone.data.LocalizedDefaults.apply(context, ConfigRepository.readCurrent(context)).reminders.firstOrNull { it.id == id && it.enabled } ?: run {
            scheduler.cancel(id)
            return
        }
        if (attempt == 0) scheduler.scheduleDaily(r)
        if (attempt < r.maxRepeats) {
            scheduler.scheduleRepeat(id, attempt + 1, System.currentTimeMillis() + r.repeatMinutes.coerceAtLeast(1) * 60_000L)
        } else {
            ReminderLog.append(context, id, "non_confermato")
            app.semplicephone.remote.RemoteEvents.enqueue(
                context, "reminder_missed",
                context.getString(R.string.reminder_missed_event, r.time, r.title + if (r.detail.isNotBlank()) " (${r.detail})" else ""),
                mapOf("reminder" to r.id),
            )
        }
        show(context, r)
    }

    companion object {
        const val ACTION_FIRE = "app.semplicephone.REMINDER_FIRE"
        const val ACTION_TAKEN = "app.semplicephone.REMINDER_TAKEN"
        const val ACTION_SNOOZE = "app.semplicephone.REMINDER_SNOOZE"
        const val EXTRA_ID = "id"
        const val EXTRA_ATTEMPT = "attempt"

        fun taken(context: Context, id: String) {
            ReminderScheduler(context).cancelRepeat(id)
            context.getSystemService(NotificationManager::class.java).cancel(ReminderScheduler.notificationId(id))
            ReminderLog.append(context, id, "preso")
        }

        fun snooze(context: Context, id: String) {
            val r = app.semplicephone.data.LocalizedDefaults.apply(context, ConfigRepository.readCurrent(context)).reminders.firstOrNull { it.id == id } ?: return
            val s = ReminderScheduler(context)
            s.cancelRepeat(id)
            // Dopo il rinvio la serie di ripetizioni riparte da capo.
            s.scheduleRepeat(id, 1, System.currentTimeMillis() + r.snoozeMinutes.coerceAtLeast(1) * 60_000L)
            context.getSystemService(NotificationManager::class.java).cancel(ReminderScheduler.notificationId(id))
            ReminderLog.append(context, id, "rinviato")
        }

        private fun show(context: Context, r: Reminder) {
            ReminderScheduler.ensureChannel(context)
            val nid = ReminderScheduler.notificationId(r.id)
            val full = PendingIntent.getActivity(
                context, nid,
                Intent(context, ReminderActivity::class.java).putExtra(EXTRA_ID, r.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            fun action(a: String, code: Int) = PendingIntent.getBroadcast(
                context, nid * 4 + code,
                Intent(context, ReminderReceiver::class.java).setAction(a).putExtra(EXTRA_ID, r.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(r.title)
                .setContentText(r.detail.ifBlank { context.getString(R.string.reminder_at, r.time) })
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .addAction(0, context.getString(if (r.icon == "pill") R.string.reminder_taken else R.string.reminder_done), action(ACTION_TAKEN, 1))
                .addAction(0, context.resources.getQuantityString(R.plurals.reminder_snooze_short, r.snoozeMinutes, r.snoozeMinutes), action(ACTION_SNOOZE, 2))
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(nid, n) }
        }
    }
}
