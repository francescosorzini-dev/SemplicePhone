package app.semplicephone.remote

import app.semplicephone.R
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
    /** Esito dell'ultimo controllo: configurazione applicata o già aggiornata. */
    /** Consenso, dato sul telefono, alle richieste di posizione dal portale («Dov'è?»). */
    var allowLocate: Boolean
        get() = prefs.getBoolean("allow_locate", false)
        set(v) = prefs.edit().putBoolean("allow_locate", v).apply()
    var lastOk: Boolean
        get() = prefs.getBoolean("last_ok", false)
        set(v) = prefs.edit().putBoolean("last_ok", v).apply()
    var reportOffset: Long
        get() = prefs.getLong("report_offset", 0)
        set(v) = prefs.edit().putLong("report_offset", v).apply()
    var etag: String
        get() = prefs.getString("etag", "").orEmpty()
        set(v) = prefs.edit().putString("etag", v).apply()
    var lastReport: Long
        get() = prefs.getLong("last_report", 0)
        set(v) = prefs.edit().putLong("last_report", v).apply()
    var appsHash: Int
        get() = prefs.getInt("apps_hash", 0)
        set(v) = prefs.edit().putInt("apps_hash", v).apply()
    var appsSentAt: Long
        get() = prefs.getLong("apps_sent_at", 0)
        set(v) = prefs.edit().putLong("apps_sent_at", v).apply()
    /** Impronta della config già mandata al portale: si rimanda solo se cambia. */
    var configSentHash: String
        get() = prefs.getString("config_sent_hash", "").orEmpty()
        set(v) = prefs.edit().putString("config_sent_hash", v).apply()
    /** Impronta dell'ultima config arrivata dal portale: se coincide, la config non è stata toccata sul telefono. */
    var configRemoteHash: String
        get() = prefs.getString("config_remote_hash", "").orEmpty()
        set(v) = prefs.edit().putString("config_remote_hash", v).apply()
    /** Ultimo salvataggio fatto dalle impostazioni del telefono (ms). */
    var configLocalAt: Long
        get() = prefs.getLong("config_local_at", 0)
        set(v) = prefs.edit().putLong("config_local_at", v).apply()

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

    /**
     * Scarica, verifica e applica. Restituisce un esito leggibile, salvato anche in lastResult.
     * [force]: controllo manuale («Aggiorna ora», SMS) che ignora l'ETag e invia sempre il report.
     */
    /** Esito leggibile (nella lingua del telefono); [ok] = applicata o già aggiornata, [error] = da ritentare. */
    data class SyncResult(val text: String, val ok: Boolean = false, val error: Boolean = false)

    private fun r(id: Int, vararg args: Any, ok: Boolean = false) = SyncResult(context.getString(id, *args), ok)

    suspend fun sync(force: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        val result = try {
            syncInternal(force)
        } catch (e: Exception) {
            Log.w(TAG, "sync fallita", e)
            SyncResult(context.getString(R.string.remote_r_error, e.javaClass.simpleName + (e.message?.let { " – $it" } ?: "")), error = true)
        }
        settings.lastCheck = System.currentTimeMillis()
        settings.lastResult = result.text
        settings.lastOk = result.ok
        runCatching { report(force) }.onFailure { Log.w(TAG, "report fallito", it) }
        runCatching { RemoteEvents.flush(context) }
        result
    }

    /** Posizione attuale (o l'ultima nota) inviata al portale come evento «location». */
    private suspend fun sendLocation() {
        val fetcher = app.semplicephone.sos.LocationFetcher(context)
        val loc = fetcher.current(20_000) ?: fetcher.lastKnown()
        val text = app.semplicephone.sos.SosSms.locationText(context, loc)
        RemoteEvents.enqueue(context, "location", text, mapOf("lat" to loc?.latitude, "lon" to loc?.longitude, "acc" to loc?.accuracy))
    }

    private suspend fun syncInternal(force: Boolean): SyncResult {
        if (!settings.enabled) return r(R.string.remote_r_disabled)
        val url = settings.resolvedUrl()
        if (!url.startsWith("https://")) return r(R.string.remote_r_bad_url)
        if (settings.publicKey.isBlank()) return r(R.string.remote_r_no_key)

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("X-Device-Secret", settings.deviceSecret)
            setRequestProperty("Accept", "application/json")
            // Controllo ogni 15 minuti: se non è cambiato nulla il server risponde 304 senza corpo.
            if (!force && settings.etag.isNotBlank()) setRequestProperty("If-None-Match", settings.etag)
        }
        val code = conn.responseCode
        // «Dov'è?» dal portale: arriva come intestazione anche con 304. Solo se consentito sul telefono.
        if (conn.getHeaderField("X-Locate") == "1" && settings.allowLocate) sendLocation()
        if (code == 304) return r(R.string.remote_r_up_to_date, settings.lastSeq, ok = true)
        if (code == 404) return r(R.string.remote_r_not_found, settings.deviceId)
        if (code != 200) return SyncResult(context.getString(R.string.remote_r_http, code), error = code >= 500)
        val body = conn.inputStream.use { readLimited(it, MAX_BYTES) }
        val etag = conn.getHeaderField("ETag").orEmpty()

        val env = json.decodeFromString(Envelope.serializer(), body)
        val payloadBytes = Base64.decode(env.payload, Base64.DEFAULT)
        if (!Signature.verify(settings.publicKey, payloadBytes, env.sig)) return r(R.string.remote_r_bad_sig)

        val p = json.decodeFromString(RemotePayload.serializer(), payloadBytes.toString(Charsets.UTF_8))
        if (p.device != "*" && p.device != settings.deviceId) return r(R.string.remote_r_other_device)
        if (p.seq <= settings.lastSeq) {
            settings.etag = etag
            return r(R.string.remote_r_up_to_date, settings.lastSeq, ok = true)
        }

        p.config?.let { cfg ->
            val problems = ConfigRepository.validate(cfg)
            if (problems.isNotEmpty()) return r(R.string.remote_r_invalid, problems.first())
            ConfigRepository.get(context).save(cfg)
            configHash()?.let { settings.configRemoteHash = it }
            // Immagini della config (foto di famiglia, foto dei promemoria): scaricate e verificate con l'hash.
            app.semplicephone.data.Media.fetchMissing(context, cfg, settings.resolvedUrl(), settings.deviceSecret)
            app.semplicephone.data.Media.cleanup(context, cfg)
            ReminderScheduler(context).scheduleAll(cfg)
            app.semplicephone.admin.Protection(context)
                .grantRuntimePermissions(app.semplicephone.launch.Permissions.required(context, cfg).map { n -> n.permission })
            app.semplicephone.launch.ShortcutPins.sync(context, cfg)
            app.semplicephone.phone.PhoneGuard.checkAll(context)
        }
        p.pinHash?.let { PinStore(context).setEncoded(it) }
        settings.lastSeq = p.seq
        settings.etag = etag
        return r(R.string.remote_r_applied, p.seq, ok = true)
    }

    /**
     * Stato verso il server: batteria, versione, esiti promemoria, elenco app (solo se cambiato).
     * Al massimo una volta l'ora, salvo novità o controllo manuale: il contatto ogni 15 minuti
     * basta già al server per sapere che il telefono è vivo.
     */
    /**
     * Accessi che servono alle funzioni attive e che si concedono solo sul telefono: il portale
     * avvisa il familiare se mancano (pulsante Casa, chiamate perse, filtro chiamate).
     */
    private fun accessReport(): JSONObject {
        val cfg = app.semplicephone.data.ConfigRepository.readCurrent(context)
        val out = JSONObject()
        if (cfg.phone.homeButton) out.put("overlay", android.provider.Settings.canDrawOverlays(context))
        if (app.semplicephone.data.MissedCalls.supported() &&
            cfg.pages.any { p -> p.tiles.any { it.type == app.semplicephone.model.TileType.MISSED_CALLS } }
        ) out.put("callLog", app.semplicephone.data.MissedCalls.canRead(context))
        if (cfg.phone.blockUnknown) {
            val roles = context.getSystemService(android.app.role.RoleManager::class.java)
            out.put("screening", runCatching { roles.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false))
            out.put("contacts", app.semplicephone.launch.Permissions.granted(context, android.Manifest.permission.READ_CONTACTS))
        }
        return out
    }

    private fun configFile() = File(context.filesDir, "config.json")

    private fun configHash(): String? = configFile().takeIf { it.exists() }?.readBytes()?.let { b ->
        java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }

    /**
     * Foto della config che il portale non ha ancora (scelte o scattate sul telefono): si caricano,
     * così l'editor web le mostra e una pubblicazione successiva non le perde.
     */
    private fun uploadMedia(hashes: List<String>) {
        for (h in hashes.take(30)) {
            val f = app.semplicephone.data.Media.file(context, "media:$h")?.takeIf { it.exists() } ?: continue
            val url = settings.resolvedUrl() + (if ('?' in settings.resolvedUrl()) "&" else "?") + "media=" + h
            runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Content-Type", "image/jpeg")
                    setRequestProperty("X-Device-Secret", settings.deviceSecret)
                    setFixedLengthStreamingMode(f.length())
                }
                f.inputStream().use { input -> c.outputStream.use { input.copyTo(it) } }
                c.responseCode
                c.disconnect()
            }.onFailure { Log.w(TAG, "foto $h non caricata", it) }
        }
    }

    private fun report(force: Boolean) {
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
        val apps = runCatching { app.semplicephone.data.AppCatalog(context).load().filterNot { it.target.startsWith("@") } }.getOrDefault(emptyList())
        val appsHash = apps.joinToString("|") { it.target + "=" + it.label }.hashCode()
        // Elenco app: se cambia, a ogni controllo manuale e comunque una volta al giorno, così il
        // portale lo riceve anche se in passato lo aveva scartato (versione vecchia, telefono rimosso).
        val appsChanged = apps.isNotEmpty() &&
            (force || appsHash != settings.appsHash || System.currentTimeMillis() - settings.appsSentAt > 24 * 3_600_000L)
        val due = System.currentTimeMillis() - settings.lastReport > 60 * 60_000L
        // Config: a ogni cambiamento, così il portale vede anche le modifiche fatte sul telefono.
        val cfgHash = configHash()
        val sendConfig = cfgHash != null && cfgHash != settings.configSentHash
        if (!force && !due && events.length() == 0 && !appsChanged && !sendConfig) return

        val body = JSONObject()
            .put("device", settings.deviceId)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("seq", settings.lastSeq)
            .put("battery", level)
            .put("charging", charging)
            .put("lastResult", settings.lastResult)
            .put("reminderEvents", events)
            .put("edition", BuildConfig.EDITION)
            .apply { if (BuildConfig.FULL) put("badgesAccess", app.semplicephone.phone.Badges.isEnabled(context)) }
            .put("access", accessReport())
            .apply {
                if (sendConfig) {
                    runCatching { JSONObject(configFile().readText()) }.getOrNull()?.let { put("config", it) }
                    put("configSource", if (cfgHash == settings.configRemoteHash) "remote" else "local")
                    put("configAt", settings.configLocalAt.takeIf { it > 0 } ?: configFile().lastModified())
                }
            }
            .apply {
                if (appsChanged) put("apps", JSONArray().also { a -> apps.forEach { a.put(JSONObject().put("pkg", it.target).put("label", it.label)) } })
            }
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
        val code = conn.responseCode
        if (code in 200..299) {
            if (log.exists()) settings.reportOffset = log.length()
            if (appsChanged) {
                settings.appsHash = appsHash
                settings.appsSentAt = System.currentTimeMillis()
            }
            settings.lastReport = System.currentTimeMillis()
            // Il portale dice se l'elenco gli manca: si azzera così parte al prossimo report.
            val reply = if (code == 200) runCatching { conn.inputStream.use { readLimited(it, 4096) } }.getOrNull() else null
            if (sendConfig && cfgHash != null) settings.configSentHash = cfgHash
            val needMedia = reply?.let { r -> runCatching { JSONObject(r).optJSONArray("needMedia") }.getOrNull() }
            if (needMedia != null && needMedia.length() > 0) uploadMedia((0 until needMedia.length()).map { needMedia.getString(it) })
            if (reply != null && runCatching { JSONObject(reply).optBoolean("needApps") }.getOrDefault(false)) {
                settings.appsHash = 0
                settings.appsSentAt = 0
                settings.lastReport = 0
            }
        }
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

        /**
         * Controllo periodico ogni 15 minuti (il minimo di Android), solo con rete. Grazie all'ETag
         * ogni controllo senza novità costa una richiesta di pochi byte.
         */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!RemoteSettings(context).enabled) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val req = PeriodicWorkRequestBuilder<RemoteWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Controllo immediato fuori programma (comando SMS). */
        fun syncNow(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<RemoteWorker>()
                .setInputData(androidx.work.workDataOf(KEY_FORCE to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm(context).enqueueUniqueWork("remote-sync-now", androidx.work.ExistingWorkPolicy.REPLACE, req)
        }

        private fun wm(context: Context) = WorkManager.getInstance(context)
        const val KEY_FORCE = "force"
    }
}

class RemoteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(RemoteClient.KEY_FORCE, false)
        val res = RemoteClient(applicationContext).sync(force)
        // Gli aggiornamenti dell'app si controllano al massimo ogni 6 ore, non a ogni sync.
        val prefs = applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("last_auto", 0) > 6 * 3_600_000L) {
            prefs.edit().putLong("last_auto", System.currentTimeMillis()).apply()
            runCatching { app.semplicephone.update.Updater(applicationContext).autoUpdateIfSilent() }
        }
        return if (res.error) Result.retry() else Result.success()
    }
}
