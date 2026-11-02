package app.semplicephone.sos

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.semplicephone.launch.Permissions
import app.semplicephone.model.SosConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

/** Posizione per l'SMS di emergenza: fix fresco se arriva in tempo, altrimenti l'ultima nota. */
class LocationFetcher(private val context: Context) {

    private val lm = context.getSystemService(LocationManager::class.java)

    fun canUse(): Boolean = Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    @Suppress("MissingPermission")
    fun lastKnown(): Location? {
        if (!canUse()) return null
        return providers().mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    @Suppress("MissingPermission")
    suspend fun current(timeoutMs: Long): Location? {
        if (!canUse()) return null
        val provider = providers().firstOrNull() ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } catch (e: Exception) {
                    Log.w("LocationFetcher", "getCurrentLocation fallito", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /** In ordine di preferenza: fused (API 31+), GPS, rete; solo quelli attivi. */
    private fun providers(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
}

/** Invio degli SMS SOS. Restituisce quanti numeri sono stati raggiunti dall'invio. */
object SosSms {

    fun text(cfg: SosConfig, location: Location?): String {
        val pos = if (location == null) "Posizione non disponibile."
        else {
            val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)
                .format(Instant.ofEpochMilli(location.time).atZone(ZoneId.systemDefault()))
            val acc = if (location.hasAccuracy()) " (±${location.accuracy.toInt()} m, ore $time)" else " (ore $time)"
            "Posizione: https://maps.google.com/?q=%.6f,%.6f".format(Locale.ROOT, location.latitude, location.longitude) + acc
        }
        return "SOS - ${cfg.message.trim()}\n$pos"
    }

    /** Apre l'app Messaggi con destinatari e testo già pronti (resta da premere «Invia»). */
    fun openComposer(context: Context, numbers: List<String>, text: String) {
        val to = numbers.joinToString(";") { n -> n.filter { it.isDigit() || it == '+' } }
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$to"))
                    .putExtra("sms_body", text)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun send(context: Context, numbers: List<String>, text: String): Int {
        if (!Permissions.granted(context, Manifest.permission.SEND_SMS)) return 0
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        var sent = 0
        numbers.forEach { n ->
            try {
                sms.sendMultipartTextMessage(n.filter { it.isDigit() || it == '+' }, null, parts, null, null)
                sent++
            } catch (e: Exception) {
                Log.w("SosSms", "invio a $n fallito", e)
            }
        }
        return sent
    }
}
