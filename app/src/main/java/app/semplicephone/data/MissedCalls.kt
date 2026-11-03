package app.semplicephone.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import app.semplicephone.BuildConfig
import app.semplicephone.remote.SmsCommand

/** Una riga del registro chiamate, quanto basta per decidere cosa è ancora da richiamare. */
data class CallRow(val number: String, val type: Int, val at: Long)

/** Chiamata persa ancora da richiamare: [count] tentativi dallo stesso numero, l'ultimo alle [at]. */
data class Missed(val number: String, val count: Int, val at: Long)

/**
 * Chiamate perse dal registro di sistema (READ_CALL_LOG; assente nell'edizione Play).
 * Una chiamata persa resta in elenco finché non c'è una chiamata più recente con lo stesso
 * numero, in uscita o risposta: richiamare (o essere richiamati) la toglie da sola.
 */
object MissedCalls {
    private const val TAG = "MissedCalls"
    /** Oltre questo intervallo le chiamate perse non contano più. */
    const val WINDOW_MS = 3 * 24 * 3_600_000L

    fun supported(): Boolean = BuildConfig.CALL_LOG

    fun canRead(context: Context): Boolean = supported() &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** Regola pura (testabile). [rows] in qualsiasi ordine; risultato dalla più recente. */
    fun pending(rows: List<CallRow>, now: Long): List<Missed> {
        val out = LinkedHashMap<String, Missed>()
        val closed = HashSet<String>()
        for (r in rows.sortedByDescending { it.at }) {
            if (now - r.at > WINDOW_MS) break
            val key = SmsCommand.normalize(r.number)
            if (key.length < 6 || key in closed) continue
            when (r.type) {
                CallLog.Calls.MISSED_TYPE -> {
                    val m = out[key]
                    out[key] = if (m == null) Missed(r.number, 1, r.at) else m.copy(count = m.count + 1)
                }
                // Rifiutate dal filtro o bloccate: non sono «perse», non vanno richiamate.
                CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> Unit
                // Risposta o chiamata fatta: le perse più vecchie dello stesso numero sono sistemate.
                else -> closed += key
            }
        }
        return out.values.toList()
    }

    fun load(context: Context, now: Long = System.currentTimeMillis()): List<Missed> {
        if (!canRead(context)) return emptyList()
        return try {
            val rows = ArrayList<CallRow>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} > ?", arrayOf((now - WINDOW_MS).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && rows.size < 300) rows += CallRow(c.getString(0).orEmpty(), c.getInt(1), c.getLong(2))
            }
            pending(rows, now)
        } catch (e: Exception) {
            Log.w(TAG, "registro chiamate non leggibile", e)
            emptyList()
        }
    }
}
