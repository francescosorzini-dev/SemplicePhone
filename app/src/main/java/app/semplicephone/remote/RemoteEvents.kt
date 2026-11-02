package app.semplicephone.remote

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Eventi urgenti verso il portale (SOS, batteria scarica, promemoria non confermato), che li
 * inoltra subito via email/Telegram. Coda su file + WorkManager: se manca la rete partono appena torna.
 */
object RemoteEvents {

    private val lock = Any()
    private const val WORK = "remote-events"
    private const val TAG = "RemoteEvents"

    private fun queue(context: Context) = File(context.filesDir, "events_queue.jsonl")

    fun enqueue(context: Context, type: String, text: String, extra: Map<String, Any?> = emptyMap()) {
        if (!RemoteSettings(context).enabled) return
        val o = JSONObject().put("type", type).put("text", text).put("at", System.currentTimeMillis())
        extra.forEach { (k, v) -> o.put(k, v) }
        synchronized(lock) {
            val f = queue(context)
            // Tetto: un telefono senza rete per giorni non deve riempire la memoria.
            if (f.length() > 128 * 1024) f.writeText(f.readLines().takeLast(200).joinToString("\n", postfix = "\n"))
            f.appendText(o.toString() + "\n")
        }
        val req = OneTimeWorkRequestBuilder<EventWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Invia la coda; true se vuota o inviata, false se va ritentato. */
    internal fun flush(context: Context): Boolean {
        val s = RemoteSettings(context)
        val f = queue(context)
        if (!s.enabled || !s.resolvedUrl().startsWith("https://")) {
            synchronized(lock) { f.delete() }
            return true
        }
        val lines = synchronized(lock) { if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList() }.take(200)
        if (lines.isEmpty()) return true
        val events = JSONArray().also { arr -> lines.forEach { l -> runCatching { arr.put(JSONObject(l)) } } }
        val body = JSONObject().put("device", s.deviceId).put("events", events).toString().toByteArray()
        val url = s.resolvedUrl() + (if ('?' in s.resolvedUrl()) "&" else "?") + "event=1"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Device-Secret", s.deviceSecret)
            }
            conn.outputStream.use { it.write(body) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            if (ok) synchronized(lock) {
                val rest = f.readLines().filter { it.isNotBlank() }.drop(lines.size)
                if (rest.isEmpty()) f.delete() else f.writeText(rest.joinToString("\n", postfix = "\n"))
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "invio eventi fallito", e)
            false
        }
    }
}

class EventWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (RemoteEvents.flush(applicationContext)) Result.success() else Result.retry()
}
