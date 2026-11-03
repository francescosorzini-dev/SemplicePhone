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
 * Filtro chiamate (ruolo di sistema «app per filtrare le chiamate»). Android lo interpella solo per
 * i numeri che NON sono in rubrica: i contatti squillano sempre. Qui si lasciano passare anche i
 * numeri SOS e dei riquadri; gli altri vengono rifiutati senza squillo e segnalati al portale.
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
