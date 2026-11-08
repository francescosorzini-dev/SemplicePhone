package app.semplicephone.phone

import android.content.Context
import android.net.Uri
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.telephony.TelephonyManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.semplicephone.R
import app.semplicephone.data.ConfigRepository
import app.semplicephone.model.LauncherConfig
import app.semplicephone.remote.Notice
import app.semplicephone.remote.RemoteEvents
import java.util.concurrent.TimeUnit

/** Regole pure (testabili) sulle chiamate in uscita. */
object OutgoingRules {
    /** Numeri a pagamento italiani: servizi a sovrapprezzo, informazioni, chat e quiz. */
    private val PREMIUM = listOf("899", "892", "893", "894", "895", "144", "166", "709", "4")

    private fun national(number: String): String {
        val d = number.filter { it.isDigit() || it == '+' }
        return when {
            d.startsWith("+39") -> d.removePrefix("+39")
            d.startsWith("0039") -> d.removePrefix("0039")
            else -> d
        }
    }

    fun isInternational(number: String): Boolean {
        val d = number.filter { it.isDigit() || it == '+' }
        return (d.startsWith("+") && !d.startsWith("+39")) || (d.startsWith("00") && !d.startsWith("0039"))
    }

    /** «4…» solo per i numeri brevi a pagamento (es. 4xxxx dei concorsi), non per i normali. */
    fun isPremium(number: String): Boolean {
        val n = national(number)
        return PREMIUM.any { p -> if (p == "4") n.startsWith("4") && n.length in 4..8 else n.startsWith(p) }
    }

    /** null = chiamata ammessa, altrimenti il motivo del blocco. */
    fun blockReason(cfg: LauncherConfig, number: String): Int? {
        if (number.isBlank() || number.filter(Char::isDigit) in setOf("112", "113", "115", "118", "1515", "1530")) return null
        if (CallFilter.allowed(number, CallFilter.knownNumbers(cfg))) return null
        if (cfg.phone.blockPremium && isPremium(number)) return R.string.call_out_blocked_premium
        if (cfg.phone.blockInternational && isInternational(number)) return R.string.call_out_blocked_international
        return null
    }
}

/**
 * Chiamate in uscita (ruolo «reindirizzamento chiamate»): blocca i numeri a pagamento/esteri
 * scelti dalla famiglia, anche se composti da un'altra app, e avvia il controllo «chiamata lunga».
 */
class CallGuard : CallRedirectionService() {
    override fun onPlaceCall(handle: Uri, initialPhoneAccount: PhoneAccountHandle, allowInteractiveResponse: Boolean) {
        val cfg = ConfigRepository.readCurrent(this)
        val number = handle.schemeSpecificPart.orEmpty()
        val reason = OutgoingRules.blockReason(cfg, number)
        if (reason != null) {
            cancelCall()
            Notice.show(this, getString(R.string.call_out_blocked_title), getString(reason, number), "alert")
            RemoteEvents.enqueue(this, "call_out_blocked", app.semplicephone.remote.Res("call_out_blocked_event", R.string.call_out_blocked_event), number, extra = mapOf("number" to number))
            return
        }
        placeCallUnmodified()
        LongCall.started(this, cfg, number)
    }
}

/** Avviso alla famiglia se una chiamata con un numero non in rubrica dura troppo (truffe telefoniche). */
object LongCall {
    fun started(context: Context, cfg: LauncherConfig, number: String) {
        val min = cfg.phone.longCallMinutes
        if (min <= 0 || number.isBlank()) return
        if (CallFilter.allowed(number, CallFilter.knownNumbers(cfg))) return
        if (app.semplicephone.data.Contacts(context).isInContacts(number) != false) return
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<LongCallWorker>().setInitialDelay(min.toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf("number" to number, "minutes" to min)).build(),
        )
    }
}

class LongCallWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val tm = applicationContext.getSystemService(TelephonyManager::class.java)
        @Suppress("DEPRECATION", "MissingPermission")
        val inCall = runCatching { tm.callState == TelephonyManager.CALL_STATE_OFFHOOK }.getOrDefault(false)
        if (inCall) {
            val number = inputData.getString("number").orEmpty()
            RemoteEvents.enqueue(
                applicationContext, "long_call",
                app.semplicephone.remote.Res("long_call_event", R.string.long_call_event), number, inputData.getInt("minutes", 0), extra = mapOf("number" to number),
            )
        }
        return Result.success()
    }
}
