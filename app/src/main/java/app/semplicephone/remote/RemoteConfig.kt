package app.semplicephone.remote

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Base64
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.semplicephone.BuildConfig
import app.semplicephone.data.ConfigRepository
import app.semplicephone.model.LauncherConfig
import app.semplicephone.reminders.ReminderScheduler
import app.semplicephone.security.PinStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Busta firmata servita dal server: payload = JSON di [RemotePayload] (byte esatti firmati),
 * sig = firma Ed25519 dei byte del payload. Entrambi in Base64 standard.
 */
@Serializable
data class Envelope(val v: Int = 1, val payload: String, val sig: String)

@Serializable
data class RemotePayload(
    /** Monotono: una busta con seq <= ultimo applicato viene ignorata (anti-replay/rollback). */
    val seq: Long,
    /** ID del dispositivo destinatario, o "*" per tutti. */
    val device: String = "*",
    val issuedAt: Long = 0,
    val config: LauncherConfig? = null,
    /** "pbkdf2-sha256$iter$salt$hash" per impostare il PIN da remoto. */
    val pinHash: String? = null,
)

/** Impostazioni locali della config remota. L'ID e il segreto del dispositivo nascono qui. */
class RemoteSettings(context: Context) {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()
    var url: String
        get() = prefs.getString("url", null) ?: BuildConfig.REMOTE_URL
        set(v) = prefs.edit().putString("url", v.trim()).apply()
    var publicKey: String
        get() = prefs.getString("pubkey", null) ?: BuildConfig.REMOTE_PUBKEY
        set(v) = prefs.edit().putString("pubkey", v.trim()).apply()
    var lastSeq: Long
        get() = prefs.getLong("last_seq", 0)
        set(v) = prefs.edit().putLong("last_seq", v).apply()
    var lastCheck: Long
        get() = prefs.getLong("last_check", 0)
        set(v) = prefs.edit().putLong("last_check", v).apply()
    var lastResult: String
        get() = prefs.getString("last_result", "").orEmpty()
        set(v) = prefs.edit().putString("last_result", v).apply()
    var reportOffset: Long
        get() = prefs.getLong("report_offset", 0)
        set(v) = prefs.edit().putLong("report_offset", v).apply()

    val deviceId: String get() = stable("device_id", 10)
    val deviceSecret: String get() = stable("device_secret", 40)

    private fun stable(key: String, length: Int): String = prefs.getString(key, null) ?: run {
        // Senza caratteri ambigui (0/o, 1/l): l'ID va letto e ricopiato a mano sul server.
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val v = (1..length).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        prefs.edit().putString(key, v).apply()
        v
    }

    fun resolvedUrl(): String = url.replace("{device}", deviceId)
}

object Signature {
    fun verify(publicKeyB64: String, message: ByteArray, sigB64: String): Boolean = try {
        // java.util.Base64 (non android.util): verificabile anche nei test JVM.
        val pk = java.util.Base64.getMimeDecoder().decode(publicKeyB64.trim())
        val sig = java.util.Base64.getMimeDecoder().decode(sigB64.trim())
        if (pk.size != 32 || sig.size != 64) false
        else Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(pk, 0))
            update(message, 0, message.size)
            verifySignature(sig)
        }
    } catch (e: Exception) {
        false
    }
}

class RemoteClient(private val context: Context) {

    private val settings = RemoteSettings(context)
    private val json = Json { ignoreUnknownKeys = true }

    /** Scarica, verifica e applica. Restituisce un esito leggibile, salvato anche in lastResult. */
    suspend fun sync(): String = withContext(Dispatchers.IO) {
        val result = try {
            syncInternal()
        } catch (e: Exception) {
            Log.w(TAG, "sync fallita", e)
            "Errore: ${e.javaClass.simpleName}${e.message?.let { " – $it" } ?: ""}"
        }
        settings.lastCheck = System.currentTimeMillis()
        settings.lastResult = result
        runCatching { report() }.onFailure { Log.w(TAG, "report fallito", it) }
        result
    }

    private suspend fun syncInternal(): String {
        if (!settings.enabled) return "Disattivata"
        val url = settings.resolvedUrl()
        if (!url.startsWith("https://")) return "Indirizzo non valido (serve https)"
        if (settings.publicKey.isBlank()) return "Chiave pubblica mancante"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("X-Device-Secret", settings.deviceSecret)
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        if (code == 404) return "Nessuna configurazione pubblicata per ${settings.deviceId}"
        if (code != 200) return "Server: HTTP $code"
        val body = conn.inputStream.use { readLimited(it, MAX_BYTES) }

        val env = json.decodeFromString(Envelope.serializer(), body)
        val payloadBytes = Base64.decode(env.payload, Base64.DEFAULT)
        if (!Signature.verify(settings.publicKey, payloadBytes, env.sig)) return "Firma NON valida: configurazione rifiutata"

        val p = json.decodeFromString(RemotePayload.serializer(), payloadBytes.toString(Charsets.UTF_8))
        if (p.device != "*" && p.device != settings.deviceId) return "Configurazione destinata a un altro dispositivo"
        if (p.seq <= settings.lastSeq) return "Già aggiornata (versione ${settings.lastSeq})"

        p.config?.let { cfg ->
            val problems = ConfigRepository.validate(cfg)
            if (problems.isNotEmpty()) return "Configurazione non valida: ${problems.first()}"
            ConfigRepository.get(context).save(cfg)
            ReminderScheduler(context).scheduleAll(cfg)
        }
        p.pinHash?.let { PinStore(context).setEncoded(it) }
        settings.lastSeq = p.seq
        return "Applicata versione ${p.seq}"
    }

    /** Stato verso il server: batteria, versione, esiti promemoria dall'ultimo invio. */
    private fun report() {
        if (!settings.enabled || !settings.resolvedUrl().startsWith("https://")) return
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else -1
        } ?: -1
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false

        val log = File(context.filesDir, "reminders_log.jsonl")
        val events = JSONArray()
        var offset = settings.reportOffset
        if (log.exists()) {
            if (offset > log.length()) offset = 0 // file ruotato
            log.inputStream().use { input ->
                input.skip(offset)
                input.bufferedReader().readLines().takeLast(500).forEach { line ->
                    runCatching { events.put(JSONObject(line)) }
                }
            }
        }
        val body = JSONObject()
            .put("device", settings.deviceId)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("seq", settings.lastSeq)
            .put("battery", level)
            .put("charging", charging)
            .put("lastResult", settings.lastResult)
            .put("reminderEvents", events)
            .toString().toByteArray()

        val reportUrl = settings.resolvedUrl() + (if ('?' in settings.resolvedUrl()) "&" else "?") + "report=1"
        val conn = (URL(reportUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Device-Secret", settings.deviceSecret)
        }
        conn.outputStream.use { it.write(body) }
        if (conn.responseCode in 200..299 && log.exists()) settings.reportOffset = log.length()
        conn.disconnect()
    }

    private fun readLimited(input: java.io.InputStream, max: Int): String {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            require(buf.size() <= max) { "risposta troppo grande" }
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val TAG = "RemoteClient"
        private const val MAX_BYTES = 512 * 1024
        private const val WORK = "remote-config-sync"

        /** Controllo periodico (6 h, con rete). Senza config remota attiva il lavoro viene tolto. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!RemoteSettings(context).enabled) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val req = PeriodicWorkRequestBuilder<RemoteWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

class RemoteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val res = RemoteClient(applicationContext).sync()
        runCatching { app.semplicephone.update.Updater(applicationContext).autoUpdateIfSilent() }
        return if (res.startsWith("Errore")) Result.retry() else Result.success()
    }
}
