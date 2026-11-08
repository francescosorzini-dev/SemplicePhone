package app.semplicephone.phone

import androidx.compose.foundation.layout.safeDrawing
import app.semplicephone.ui.Ui
import app.semplicephone.ui.TextSize
import app.semplicephone.ui.Palette
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.semplicephone.MainActivity
import app.semplicephone.R
import app.semplicephone.data.ConfigRepository
import app.semplicephone.remote.RemoteEvents
import app.semplicephone.ui.Voice
import kotlin.math.sqrt

/**
 * Riconoscimento di una caduta dall'accelerometro: breve caduta libera (< 0,5 g), urto forte
 * (> 2,5 g) entro un secondo, poi quasi immobilità per alcuni secondi. Allora chiede «Stai bene?»
 * a tutto schermo con un conto alla rovescia; senza risposta parte l'SOS.
 * Gira come servizio in primo piano solo se l'opzione è attiva.
 */
class FallDetector : Service(), SensorEventListener {
    private var freeFallStart = 0L
    private var freeFallEnd = 0L
    private var impactAt = 0L
    private val still = ArrayList<Float>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.fall_channel), NotificationManager.IMPORTANCE_MIN))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(app.semplicephone.ui.SymbolDrawables.get("heart"))
            .setContentTitle(getString(R.string.fall_active))
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onDestroy() {
        getSystemService(SensorManager::class.java).unregisterListener(this)
        super.onDestroy()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        val now = SystemClock.elapsedRealtime()
        when {
            impactAt > 0 -> {
                // Dopo l'urto: 2 s di assestamento, poi 8 s di osservazione.
                if (now - impactAt < 2000) return
                still += g
                if (now - impactAt >= 10_000) {
                    val mean = still.average().toFloat()
                    val sd = sqrt(still.map { (it - mean) * (it - mean) }.average()).toFloat()
                    impactAt = 0; still.clear()
                    if (sd < 0.12f) onFall(this)
                }
            }
            g < 0.5f -> { if (freeFallStart == 0L) freeFallStart = now; freeFallEnd = now }
            freeFallStart > 0 && g > 2.5f && freeFallEnd - freeFallStart >= 80 && now - freeFallEnd < 1000 -> {
                impactAt = now; freeFallStart = 0; still.clear()
            }
            freeFallStart > 0 && now - freeFallEnd > 1000 -> freeFallStart = 0
        }
    }

    companion object {
        private const val CHANNEL = "fall"
        private const val ID = 0x5E11
        private const val TAG = "FallDetector"

        /** Avvia o ferma il servizio secondo la config. */
        fun sync(context: Context) {
            val on = runCatching { ConfigRepository.readCurrent(context).phone.fallDetection }.getOrDefault(false)
            val i = Intent(context, FallDetector::class.java)
            runCatching { if (on) ContextCompat.startForegroundService(context, i) else context.stopService(i) }
                .onFailure { Log.w(TAG, "servizio cadute non avviato", it) }
        }

        fun onFall(context: Context) {
            Log.i(TAG, "possibile caduta")
            val i = Intent(context, FallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Da sfondo serve la notifica a tutto schermo (come per i promemoria).
            app.semplicephone.reminders.ReminderScheduler.ensureChannel(context)
            val pi = PendingIntent.getActivity(context, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, app.semplicephone.reminders.ReminderScheduler.CHANNEL)
                .setSmallIcon(app.semplicephone.ui.SymbolDrawables.get("alert"))
                .setContentTitle(context.getString(R.string.fall_question))
                .setContentText(context.getString(R.string.fall_reason))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.fall_reason)))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(ID + 1, n)
            runCatching { context.startActivity(i) }
        }
    }
}

/** «Stai bene?» con conto alla rovescia di 30 s, suoneria e vibrazione; poi SOS. */
class FallActivity : ComponentActivity() {
    private var timer: CountDownTimer? = null
    private var ring: android.media.Ringtone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        getSystemService(NotificationManager::class.java).cancel(0x5E12)
        ring = runCatching {
            RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(); play()
            }
        }.getOrNull()
        runCatching { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)) }
        Voice.speak(this, getString(R.string.fall_question) + " " + getString(R.string.fall_reason))
        var left by androidx.compose.runtime.mutableIntStateOf(30)
        timer = object : CountDownTimer(30_000, 1000) {
            override fun onTick(ms: Long) { left = (ms / 1000).toInt() + 1 }
            override fun onFinish() { help(auto = true) }
        }.start()
        setContent {
            Column(
                Modifier.fillMaxSize().background(app.semplicephone.ui.Palette.Sos).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.fall_question), color = Color.White, fontSize = TextSize.Huge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                // Il perché della domanda: senza, sembra un avviso qualsiasi.
                Text(stringResource(R.string.fall_reason), color = Color.White, fontSize = TextSize.Title, lineHeight = 32.sp, textAlign = TextAlign.Center)
                Text(stringResource(R.string.fall_countdown, left), color = Color.White, fontSize = TextSize.Label, textAlign = TextAlign.Center)
                app.semplicephone.ui.BigButton(
                    stringResource(R.string.fall_ok), app.semplicephone.ui.Tone.Ok, symbol = "check",
                    height = app.semplicephone.ui.Ui.HeightHuge, textSize = app.semplicephone.ui.TextSize.Huge,
                ) { stopAlarm(); finishAndRemoveTask() }
                app.semplicephone.ui.BigButton(
                    stringResource(R.string.fall_help), app.semplicephone.ui.Tone.Light, symbol = "alert", textSize = app.semplicephone.ui.TextSize.Title,
                ) { help(auto = false) }
            }
        }
    }

    private fun stopAlarm() {
        timer?.cancel(); timer = null
        runCatching { ring?.stop() }
        runCatching { getSystemService(Vibrator::class.java).cancel() }
    }

    private fun help(auto: Boolean) {
        stopAlarm()
        RemoteEvents.enqueue(this, "fall", if (auto) app.semplicephone.remote.Res("fall_event_auto", R.string.fall_event_auto) else app.semplicephone.remote.Res("fall_event_help", R.string.fall_event_help))
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SOS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finishAndRemoveTask()
    }

    override fun onDestroy() { stopAlarm(); super.onDestroy() }
}
