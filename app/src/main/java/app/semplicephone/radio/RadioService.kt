package app.semplicephone.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.semplicephone.MainActivity
import app.semplicephone.R
import app.semplicephone.model.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stato della radio per la schermata: stazione, in riproduzione, in caricamento, errore. */
data class RadioState(val station: Station? = null, val playing: Boolean = false, val loading: Boolean = false, val error: Boolean = false)

/**
 * Radio in streaming: servizio in primo piano (continua a schermo spento), notifica con «Ferma».
 * MediaPlayer di sistema: MP3/AAC e HLS (m3u8), senza librerie in più. Una telefonata la ferma.
 */
class RadioService : Service() {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAll()
            ACTION_PLAY -> {
                val st = Station(intent.getStringExtra(EXTRA_NAME).orEmpty(), intent.getStringExtra(EXTRA_URL).orEmpty())
                foreground(st)
                play(st)
            }
            else -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun foreground(st: Station) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.radio_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(st.name.ifBlank { getString(R.string.label_radio) })
            .setContentText(getString(R.string.radio_playing))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.radio_stop), stop)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun play(st: Station) {
        release()
        _state.value = RadioState(st, loading = true)
        val am = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stopAll()
            }.build().also { am.requestAudioFocus(it) }
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(st.url)
                setOnPreparedListener { it.start(); _state.value = RadioState(st, playing = true) }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "errore radio $what/$extra")
                    fail(st)
                    true
                }
                prepareAsync()
            }
        }.onFailure { Log.w(TAG, "stazione non valida", it); fail(st) }.getOrNull()
    }

    private fun fail(st: Station) {
        release()
        _state.value = RadioState(st, error = true)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun stopAll() {
        release()
        _state.value = _state.value.copy(playing = false, loading = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        release()
        _state.value = _state.value.copy(playing = false, loading = false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Radio"
        private const val CHANNEL = "radio"
        private const val NOTIFICATION_ID = 0x5E10
        const val ACTION_PLAY = "app.semplicephone.RADIO_PLAY"
        const val ACTION_STOP = "app.semplicephone.RADIO_STOP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"

        private val _state = MutableStateFlow(RadioState())
        val state: StateFlow<RadioState> = _state.asStateFlow()

        fun play(context: Context, st: Station) {
            val i = Intent(context, RadioService::class.java).setAction(ACTION_PLAY).putExtra(EXTRA_NAME, st.name).putExtra(EXTRA_URL, st.url)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, i) }
                .onFailure { Log.w(TAG, "radio non avviata", it) }
        }

        fun stop(context: Context) {
            if (!_state.value.playing && !_state.value.loading) return
            runCatching { context.startService(Intent(context, RadioService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
