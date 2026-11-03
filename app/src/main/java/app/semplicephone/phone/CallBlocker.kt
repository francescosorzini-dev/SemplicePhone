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
        val allow = CallResponse.Builder().build()
        val cfg = ConfigRepository.readCurrent(this)
        if (!cfg.phone.blockUnknown || details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, allow); return
        }
        val number = details.handle?.schemeSpecificPart.orEmpty()
        if (CallFilter.allowed(number, CallFilter.knownNumbers(cfg))) {
            respondToCall(details, allow); return
        }
        val inContacts = if (number.isBlank()) false else app.semplicephone.data.Contacts(this).isInContacts(number)
        if (inContacts != false) {
            respondToCall(details, allow); return
        }
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
    }
}
