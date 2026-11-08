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

/** Messaggio non letto da mostrare nel riquadro «Messaggi da leggere» (resta solo sul telefono). */
data class UnreadMessage(
    val key: String,
    val pkg: String,
    val app: String,
    val sender: String,
    val text: String,
    val at: Long,
    val open: android.app.PendingIntent?,
    /** Da sconosciuto e con un link: possibile truffa (non si apre col tocco). */
    val suspicious: Boolean = false,
)

/** Regole pure (testabili) per riconoscere i messaggi sospetti. */
object ScamRules {
    private val LINK = Regex("""(https?://|www\.|\b[a-z0-9-]+\.(it|com|net|org|info|top|xyz|ly|me|co|link|site|online|click)(/|\b))""", RegexOption.IGNORE_CASE)

    fun hasLink(text: String) = LINK.containsMatchIn(text)

    /** Mittente che sembra un numero (non un nome di rubrica) o una sigla breve tipo «INFO». */
    fun looksUnknown(sender: String): Boolean {
        val s = sender.trim()
        val digits = s.count(Char::isDigit)
        return digits >= 6 && digits >= s.count(Char::isLetter) || (s.isNotEmpty() && ' ' !in s && s.length <= 11 && s == s.uppercase() && s.any(Char::isLetter))
    }

    fun suspicious(sender: String, text: String, inContacts: (String) -> Boolean?): Boolean {
        if (!hasLink(text) || !looksUnknown(sender)) return false
        return sender.count(Char::isDigit) < 6 || inContacts(sender) != true
    }
}

/** Notifiche non lette per pacchetto, alimentate da [BadgeListener]. */
object Badges {
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    /** Pacchetti che pubblicano le chiamate perse al posto (o oltre) del dialer. */
    val CALL_PACKAGES = setOf("com.android.server.telecom", "com.android.phone")

    /** Messaggi non letti (SMS, WhatsApp, Telegram…) dalle notifiche, dal più recente. */
    private val _messages = MutableStateFlow<List<UnreadMessage>>(emptyList())
    val messages: StateFlow<List<UnreadMessage>> = _messages.asStateFlow()

    /** App di messaggistica conosciute, oltre all'app SMS predefinita. */
    val MESSAGE_PACKAGES = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.facebook.orca",
    )

    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Messaggi leggibili da una notifica: stile «conversazione» se c'è, altrimenti titolo e testo. */
    internal fun messagesOf(context: Context, sbn: StatusBarNotification, smsPackage: String?): List<UnreadMessage> {
        val n = sbn.notification
        val isMessage = sbn.packageName in MESSAGE_PACKAGES || sbn.packageName == smsPackage || n.category == Notification.CATEGORY_MESSAGE ||
            n.extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name
        if (!isMessage || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()
        val app = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val style = runCatching { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val chat = style?.conversationTitle?.toString()
        val fromStyle = style?.messages.orEmpty().takeLast(3).mapNotNull { m ->
            val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val who = m.person?.name?.toString() ?: chat ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            UnreadMessage(sbn.key + "#" + m.timestamp, sbn.packageName, app, who, text, if (m.timestamp > 0) m.timestamp else sbn.postTime, n.contentIntent)
        }
        if (fromStyle.isNotEmpty()) return fromStyle
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return emptyList()
        return listOf(UnreadMessage(sbn.key, sbn.packageName, app, title, text, sbn.postTime, n.contentIntent))
    }

    internal fun updateMessages(context: Context, active: Array<StatusBarNotification>?) {
        val sms = runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        val guard = runCatching { app.semplicephone.data.ConfigRepository.readCurrent(context).phone.scamGuard }.getOrDefault(true)
        val contacts = app.semplicephone.data.Contacts(context)
        val before = _messages.value.filter { it.suspicious }.map { it.key }.toSet()
        _messages.value = active.orEmpty().flatMap { messagesOf(context, it, sms) }.sortedByDescending { it.at }.take(20)
            .map { m -> if (guard && ScamRules.suspicious(m.sender, m.text) { contacts.isInContacts(it) }) m.copy(suspicious = true) else m }
        // Nuovo messaggio sospetto: la famiglia lo sa (solo mittente, non il testo).
        _messages.value.filter { it.suspicious && it.key !in before }.forEach { m ->
            app.semplicephone.remote.RemoteEvents.enqueue(context, "scam_message", app.semplicephone.remote.Res("scam_event", app.semplicephone.R.string.scam_event), m.sender, m.app)
        }
    }

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
    override fun onListenerDisconnected() {
        Badges.update(packageName, null)
        Badges.updateMessages(this, null)
    }

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull()
        Badges.update(packageName, active)
        Badges.updateMessages(this, active)
    }
}
