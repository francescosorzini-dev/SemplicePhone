package app.semplicephone.phone

import app.semplicephone.R
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.util.Log
import app.semplicephone.data.ConfigRepository
import app.semplicephone.model.LauncherConfig
import app.semplicephone.remote.RemoteEvents
import app.semplicephone.sos.SosSms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil

data class BatteryInfo(val level: Int, val charging: Boolean)

/** Decisione pura sull'avviso batteria (testabile): isteresi di 10 punti per non ripetere l'SMS. */
object BatteryPolicy {
    data class Decision(val sendAlert: Boolean, val alerted: Boolean)

    fun decide(level: Int, charging: Boolean, threshold: Int, alreadyAlerted: Boolean): Decision {
        if (threshold <= 0 || level < 0) return Decision(false, false)
        if (charging || level >= threshold + 10) return Decision(false, false)
        if (level <= threshold && !alreadyAlerted) return Decision(true, true)
        return Decision(false, alreadyAlerted)
    }
}

/**
 * Suoneria protetta e controllo batteria. Chiamato dai receiver dinamici dell'Application
 * (cambio suoneria/volume, stato batteria) e dal controllo periodico [GuardWorker].
 */
object PhoneGuard {

    private const val TAG = "PhoneGuard"
    private val _battery = MutableStateFlow<BatteryInfo?>(null)
    val battery: StateFlow<BatteryInfo?> = _battery.asStateFlow()

    fun config(context: Context): LauncherConfig = ConfigRepository.readCurrent(context)

    /** Riporta suoneria e volumi al livello voluto. Non fa nulla se la protezione è spenta. */
    fun enforceRinger(context: Context, cfg: LauncherConfig = config(context)) {
        val p = cfg.phone
        if (!p.ringerLock) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        try {
            // «Non disturbare» blocca suoneria e cambi di modalità: si toglie solo se ne abbiamo l'accesso.
            if (nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            raise(am, AudioManager.STREAM_RING, p.ringVolumePercent)
            raise(am, AudioManager.STREAM_ALARM, p.ringVolumePercent)
            raise(am, AudioManager.STREAM_NOTIFICATION, p.ringVolumePercent)
        } catch (e: SecurityException) {
            Log.w(TAG, "suoneria non modificabile (Non disturbare?)", e)
        }
    }

    private fun raise(am: AudioManager, stream: Int, percent: Int) {
        val max = am.getStreamMaxVolume(stream)
        val target = ceil(max * percent.coerceIn(10, 100) / 100.0).toInt().coerceIn(1, max)
        if (am.getStreamVolume(stream) < target) am.setStreamVolume(stream, target, 0)
    }

    /** Aggiorna lo stato batteria e, se serve, avvisa i numeri SOS (una volta per scarica). */
    fun onBattery(context: Context, intent: Intent? = null, cfg: LauncherConfig = config(context)) {
        val i = intent ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val level = if (l >= 0 && s > 0) l * 100 / s else -1
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        _battery.value = BatteryInfo(level, charging)

        val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
        val was = prefs.getBoolean("battery_alerted", false)
        val d = BatteryPolicy.decide(level, charging, cfg.phone.batteryAlertPercent, was)
        if (d.alerted != was) prefs.edit().putBoolean("battery_alerted", d.alerted).apply()
        if (d.sendAlert) {
            val text = context.getString(R.string.battery_sms, level)
            if (cfg.sos.numbers.isNotEmpty()) SosSms.send(context, cfg.sos.numbers, text)
            RemoteEvents.enqueue(context, "battery", text, mapOf("level" to level))
        }
    }

    fun checkAll(context: Context) {
        val cfg = config(context)
        enforceRinger(context, cfg)
        onBattery(context, null, cfg)
        app.semplicephone.safety.Safety.checkAll(context, cfg)
    }
}
