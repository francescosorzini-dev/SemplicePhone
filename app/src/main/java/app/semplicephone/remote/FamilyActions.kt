package app.semplicephone.remote

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import app.semplicephone.R
import app.semplicephone.reminders.ReminderScheduler
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * «Fai squillare» dal portale: suono di sveglia a tutto volume (anche col telefono in silenzioso)
 * finché qualcuno non tocca «Ferma» o passano [RING_SECONDS] secondi.
 */
object FindPhone {
    private const val TAG = "FindPhone"
    const val RING_SECONDS = 45
    const val NOTIFICATION_ID = 0x5E01
    const val ACTION_STOP = "app.semplicephone.FIND_PHONE_STOP"

    @Volatile private var stopped = false

    fun stop() { stopped = true }

    /** Squilla e restituisce dopo quanti secondi si è fermato (null = nessuno l'ha fermato). */
    suspend fun ring(context: Context): Int? {
        stopped = false
        val am = context.getSystemService(AudioManager::class.java)
        val before = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(context, RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                isLooping = true
                prepare()
            }
        }.onFailure { Log.w(TAG, "suoneria non disponibile", it) }.getOrNull()
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        val vibrator = context.getSystemService(Vibrator::class.java)
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)) }
        notify(context)
        player?.start()
        var elapsed = 0
        while (elapsed < RING_SECONDS && !stopped) { delay(1000); elapsed++ }
        runCatching { player?.stop(); player?.release() }
        runCatching { vibrator?.cancel() }
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, before, 0) }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        return if (stopped) elapsed else null
    }

    private fun notify(context: Context) {
        ReminderScheduler.ensureChannel(context)
        val stop = PendingIntent.getBroadcast(
            context, 1, Intent(context, FamilyReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.find_title))
            .setContentText(context.getString(R.string.find_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .addAction(0, context.getString(R.string.find_stop), stop)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }
}

/** Messaggio della famiglia, firmato dal portale come la configurazione. */
@Serializable
data class FamilyMessage(val device: String = "*", val id: Long, val text: String, val from: String = "", val at: Long = 0)

/**
 * Messaggi dalla famiglia: scaricati dal portale (firma verificata), mostrati a tutto schermo e
 * letti a voce finché non si tocca «Ho letto»; la conferma torna al portale come evento.
 */
object FamilyMessages {
    private const val TAG = "FamilyMessages"
    const val NOTIFICATION_ID = 0x5E02
    const val ACTION_READ = "app.semplicephone.MESSAGE_READ"
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context) = File(context.filesDir, "family_message.json")

    /** Messaggio non letto per la Home, aggiornato subito quando arriva o viene letto (stesso processo). */
    val current = kotlinx.coroutines.flow.MutableStateFlow<FamilyMessage?>(null)

    fun refresh(context: Context) { current.value = unread(context) }

    /** Messaggio non ancora letto, se c'è. */
    fun unread(context: Context): FamilyMessage? =
        runCatching { json.decodeFromString(FamilyMessage.serializer(), file(context).readText()) }.getOrNull()

    /** Verifica la busta firmata; null se non valida o per un altro telefono. */
    fun parse(envelopeJson: String, publicKey: String, deviceId: String): FamilyMessage? = runCatching {
        val env = json.decodeFromString(Envelope.serializer(), envelopeJson)
        val bytes = Base64.decode(env.payload, Base64.DEFAULT)
        if (!Signature.verify(publicKey, bytes, env.sig)) return null
        val m = json.decodeFromString(FamilyMessage.serializer(), bytes.toString(Charsets.UTF_8))
        m.takeIf { (it.device == "*" || it.device == deviceId) && it.text.isNotBlank() }
    }.getOrNull()

    /** Nuovo messaggio: si salva e si mostra (schermata intera anche a telefono bloccato). */
    fun show(context: Context, m: FamilyMessage) {
        file(context).writeText(json.encodeToString(FamilyMessage.serializer(), m))
        current.value = m
        ReminderScheduler.ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MessageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (m.from.isNotBlank()) context.getString(R.string.message_from, m.from) else context.getString(R.string.message_title))
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    /** «Ho letto» (dal processo dell'allarme o dalla Home): via la notifica, conferma al portale. */
    fun markRead(context: Context) {
        val m = unread(context) ?: return
        file(context).delete()
        current.value = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        RemoteEvents.enqueue(context, "message_read", context.getString(R.string.message_read_event, m.text.take(80)), mapOf("message" to m.id))
    }
}

/** Azioni dai pulsanti di notifica e dalla schermata del messaggio (processo principale). */
class FamilyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            FindPhone.ACTION_STOP -> FindPhone.stop()
            FamilyMessages.ACTION_READ -> FamilyMessages.markRead(context)
        }
    }
}
