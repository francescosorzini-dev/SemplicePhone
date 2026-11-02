package app.semplicephone.phone

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Notifiche non lette per pacchetto, alimentate da [BadgeListener]. */
object Badges {
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    /** Pacchetti che pubblicano le chiamate perse al posto (o oltre) del dialer. */
    val CALL_PACKAGES = setOf("com.android.server.telecom", "com.android.phone")

    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    internal fun update(own: String, active: Array<StatusBarNotification>?) {
        val out = HashMap<String, Int>()
        active.orEmpty().forEach { sbn ->
            val n = sbn.notification
            val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
            if (sbn.isOngoing || summary || sbn.packageName == own || sbn.packageName == "android") return@forEach
            out[sbn.packageName] = (out[sbn.packageName] ?: 0) + n.number.coerceAtLeast(1)
        }
        _counts.value = out
    }

    fun component(context: Context) = ComponentName(context, BadgeListener::class.java)
}

class BadgeListener : NotificationListenerService() {
    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
    override fun onListenerDisconnected() = Badges.update(packageName, null)

    private fun refresh() {
        Badges.update(packageName, runCatching { activeNotifications }.getOrNull())
    }
}
