package app.semplicephone

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.semplicephone.phone.GuardWorker
import app.semplicephone.phone.PhoneGuard
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/**
 * Il processo del launcher resta vivo quasi sempre: qui si ascoltano suoneria, volume e batteria.
 * Se il processo viene chiuso, [GuardWorker] ripete gli stessi controlli ogni 15 minuti.
 */
class SempliceApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val ringer = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = PhoneGuard.enforceRinger(c)
        }
        val ringerFilter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(VOLUME_CHANGED)
        }
        ContextCompat.registerReceiver(this, ringer, ringerFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val battery = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = PhoneGuard.onBattery(c, i)
        }
        ContextCompat.registerReceiver(this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Pulsante «Casa» sopra le altre app: segue le Activity e si aggiorna a ogni cambio di config.
        app.semplicephone.launch.HomeButton.install(this)
        kotlinx.coroutines.MainScope().launch {
            app.semplicephone.data.ConfigRepository.get(this@SempliceApp).config.collect {
                app.semplicephone.launch.HomeButton.update(this@SempliceApp)
            }
        }

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "phone-guard",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GuardWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    companion object {
        /** Non pubblico nell'SDK ma inviato dal sistema a ogni cambio di volume. */
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    }
}
