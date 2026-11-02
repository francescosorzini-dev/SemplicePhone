package app.semplicephone.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.semplicephone.data.ConfigRepository

/** Regola pura (testabile): «#AGGIORNA» in testa al messaggio, mittente tra i numeri SOS. */
object SmsCommand {
    const val KEYWORD = "#AGGIORNA"

    /** Confronto sulle ultime 9 cifre: «+39 333…» e «333…» sono lo stesso numero. */
    fun normalize(number: String): String = number.filter(Char::isDigit).takeLast(9)

    fun matches(body: String, sender: String, allowed: List<String>): Boolean {
        if (!body.trim().uppercase().startsWith(KEYWORD)) return false
        val s = normalize(sender)
        if (s.length < 6) return false
        return allowed.any { normalize(it) == s }
    }
}

/**
 * Comando SMS dai familiari per applicare subito una configurazione appena pubblicata.
 * Avvia solo un controllo: quello che si applica resta comunque verificato dalla firma.
 */
class SmsCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val cfg = ConfigRepository.readCurrent(context)
        if (!app.semplicephone.BuildConfig.FULL || !cfg.phone.smsUpdateCommand || !RemoteSettings(context).enabled) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bySender = msgs.groupBy { it.originatingAddress.orEmpty() }
            .mapValues { (_, parts) -> parts.joinToString("") { it.messageBody.orEmpty() } }
        if (bySender.any { (from, body) -> SmsCommand.matches(body, from, cfg.sos.numbers) }) {
            RemoteClient.syncNow(context)
        }
    }
}
