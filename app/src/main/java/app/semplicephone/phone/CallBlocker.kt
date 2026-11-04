package app.semplicephone.phone

import android.telecom.Call
import android.telecom.CallScreeningService
import app.semplicephone.R
import app.semplicephone.data.ConfigRepository
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.TileType
import app.semplicephone.remote.RemoteEvents
import app.semplicephone.remote.SmsCommand

/** Regola pura (testabile): quali numeri possono chiamare quando il blocco degli sconosciuti è attivo. */
object CallFilter {
    /** Numeri sempre ammessi: SOS e numeri dei riquadri di chiamata, videochiamata e SMS. */
    fun knownNumbers(cfg: LauncherConfig): List<String> =
        cfg.sos.numbers + cfg.pages.flatMap { p ->
            p.tiles.filter { it.type in setOf(TileType.CALL, TileType.WA_VIDEO, TileType.SMS) }.map { it.target }
        }

    /** [number] vuoto = numero nascosto: si blocca. Confronto sulle ultime 9 cifre, come per gli SMS. */
    fun allowed(number: String, known: List<String>): Boolean {
        val n = SmsCommand.normalize(number)
        if (n.length < 6) return false
        return known.any { SmsCommand.normalize(it) == n }
    }
}

/**
 * Filtro chiamate (ruolo di sistema «app per filtrare le chiamate»). Android può interpellarlo anche
 * per i contatti (dipende da versione e produttore), quindi la rubrica si controlla qui: passano
 * contatti, numeri SOS e numeri dei riquadri; gli altri vengono rifiutati e segnalati al portale.
 * Se la rubrica non è leggibile (permesso negato) non si blocca nessuno: meglio una chiamata
 * indesiderata che un familiare respinto.
 */
class CallBlocker : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val cfg = ConfigRepository.readCurrent(this)
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        val number = details.handle?.schemeSpecificPart.orEmpty()
        if (incoming && cfg.phone.blockUnknown && !passes(cfg, number)) {
            respondToCall(
                details,
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipNotification(true)
                    .setSkipCallLog(false)
                    .build(),
            )
            val who = number.ifBlank { getString(R.string.call_hidden_number) }
            RemoteEvents.enqueue(this, "call_blocked", getString(R.string.call_blocked_event, who), mapOf("number" to number))
            return
        }
        respondToCall(details, CallResponse.Builder().build())
        if (incoming) IncomingCall.onRinging(applicationContext, cfg, number)
    }

    /** Rubrica, numeri SOS e numeri dei riquadri passano; senza permesso Contatti passa tutto. */
    private fun passes(cfg: app.semplicephone.model.LauncherConfig, number: String): Boolean {
        if (CallFilter.allowed(number, CallFilter.knownNumbers(cfg))) return true
        val inContacts = if (number.isBlank()) false else app.semplicephone.data.Contacts(this).isInContacts(number)
        return inContacts != false
    }
}

/**
 * Chiamata in arrivo ammessa: annuncio a voce di chi chiama e, per i numeri SOS, risposta
 * automatica in vivavoce dopo qualche squillo (per chi è caduto o non trova il telefono).
 */
object IncomingCall {
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Pura (testabile): rispondere da soli a questo numero? */
    fun shouldAutoAnswer(cfg: app.semplicephone.model.LauncherConfig, number: String): Boolean =
        cfg.phone.autoAnswer && number.isNotBlank() && CallFilter.allowed(number, cfg.sos.numbers)

    fun onRinging(context: android.content.Context, cfg: app.semplicephone.model.LauncherConfig, number: String) {
        if (cfg.phone.announceCaller) {
            val name = if (number.isBlank()) context.getString(R.string.call_hidden_number)
                else app.semplicephone.data.Contacts(context).displayName(number)
            val text = context.getString(R.string.voice_incoming, name)
            // Ripetuto due volte, tra uno squillo e l'altro.
            main.postDelayed({ if (ringing(context)) app.semplicephone.ui.Voice.speak(context, text) }, 1_500)
            main.postDelayed({ if (ringing(context)) app.semplicephone.ui.Voice.speak(context, text) }, 6_500)
        }
        if (shouldAutoAnswer(cfg, number)) {
            val delay = cfg.phone.autoAnswerSeconds.coerceIn(3, 30) * 1000L
            main.postDelayed({ answer(context) }, delay)
        }
    }

    private fun ringing(context: android.content.Context): Boolean =
        context.getSystemService(android.media.AudioManager::class.java)?.mode == android.media.AudioManager.MODE_RINGTONE

    private fun answer(context: android.content.Context) {
        // Solo se sta ancora squillando: se nel frattempo ha risposto qualcuno o la chiamata è finita, niente.
        if (!ringing(context)) return
        if (!app.semplicephone.launch.Permissions.granted(context, android.Manifest.permission.ANSWER_PHONE_CALLS)) return
        runCatching {
            @Suppress("MissingPermission", "DEPRECATION")
            context.getSystemService(android.telecom.TelecomManager::class.java).acceptRingingCall()
        }
        // Vivavoce e volume al massimo, appena la chiamata è attiva.
        main.postDelayed({ speaker(context) }, 1_200)
        main.postDelayed({ speaker(context) }, 3_000)
    }

    private fun speaker(context: android.content.Context) {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return
        runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL), 0) }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        }
    }
}
