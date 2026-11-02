package app.semplicephone.update

import app.semplicephone.R
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import app.semplicephone.BuildConfig
import app.semplicephone.admin.Protection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Descrittore pubblicato accanto all'APK (vedi .github/workflows/release.yml). */
@Serializable
data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String)

/**
 * Aggiornamento dell'app installata da APK. Android accetta l'aggiornamento solo se firmato con
 * la stessa chiave; in più si verifica lo SHA-256 dichiarato. Con Device Owner l'installazione
 * è silenziosa, altrimenti chiede conferma (avviata solo dalle impostazioni).
 */
class Updater(private val context: Context) {

    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var url: String
        get() = prefs.getString("url", null) ?: BuildConfig.UPDATE_URL
        set(v) = prefs.edit().putString("url", v.trim()).apply()
    var lastResult: String
        get() = prefs.getString("last", "").orEmpty()
        set(v) = prefs.edit().putString("last", v).apply()

    /**
     * Aggiornamento in-app solo per gli APK presi dal sito. Se l'app è arrivata da uno store
     * (Play, F-Droid, IzzyOnDroid…) gli aggiornamenti li porta lo store: niente doppioni.
     */
    val selfUpdate: Boolean get() = BuildConfig.UPDATE_URL.isNotEmpty() && store == null

    /** Nome dello store che ha installato l'app, o null se installata da file/ADB. */
    val store: String? by lazy { storeName(installer(context)) }

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!selfUpdate || !url.startsWith("https://")) return@withContext null
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
            }
            if (conn.responseCode != 200) {
                lastResult = context.getString(R.string.upd_none_published, conn.responseCode)
                return@withContext null
            }
            val info = json.decodeFromString(UpdateInfo.serializer(), conn.inputStream.bufferedReader().use { it.readText() })
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                lastResult = context.getString(R.string.upd_available, info.versionName)
                info
            } else {
                lastResult = context.getString(R.string.upd_up_to_date, BuildConfig.VERSION_NAME)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "controllo aggiornamenti fallito", e)
            lastResult = context.getString(R.string.upd_check_failed, e.javaClass.simpleName)
            null
        }
    }

    /**
     * Edizione completa: scarica, verifica lo SHA-256 e installa (in silenzio con Device Owner).
     * Edizione standard: niente permesso di installare APK (Play Protect lo considera sospetto),
     * quindi apre il download nel browser e l'installazione la conferma l'utente.
     */
    suspend fun install(info: UpdateInfo): Boolean = withContext(Dispatchers.IO) {
        if (!BuildConfig.FULL) {
            return@withContext runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                lastResult = context.getString(R.string.upd_browser_started, info.versionName)
                true
            }.getOrElse { lastResult = context.getString(R.string.upd_browser_failed, it.message.orEmpty()); false }
        }
        val apk = File(context.cacheDir, "update.apk")
        try {
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            }
            require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n); out.write(buf, 0, n)
                        total += n
                        require(total < 150L * 1024 * 1024) { context.getString(R.string.upd_too_big) }
                    }
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            require(hash.equals(info.sha256.trim(), ignoreCase = true)) { context.getString(R.string.upd_bad_hash) }

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val status = PendingIntent.getBroadcast(
                    context, id, Intent(context, InstallStatusReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                s.commit(status.intentSender)
            }
            lastResult = context.getString(R.string.upd_install_started, info.versionName)
            true
        } catch (e: Exception) {
            Log.w(TAG, "installazione fallita", e)
            lastResult = context.getString(R.string.upd_failed, e.message ?: e.javaClass.simpleName)
            false
        } finally {
            apk.delete()
        }
    }

    /** Da chiamare in background: aggiorna da solo solo se l'installazione è silenziosa. */
    suspend fun autoUpdateIfSilent() {
        if (!BuildConfig.FULL || !selfUpdate || !Protection(context).isDeviceOwner) return
        check()?.let { install(it) }
    }

    companion object {
        private const val TAG = "Updater"

        private val STORES = mapOf(
            "com.android.vending" to "Google Play",
            "org.fdroid.fdroid" to "F-Droid",
            "org.fdroid.basic" to "F-Droid",
            "com.looker.droidify" to "Droid-ify",
            "com.machiav3lli.fdroid" to "Neo Store",
            "in.sunilpaulmathew.izzyondroid" to "IzzyOnDroid",
            "dev.imranr.obtainium" to "Obtainium",
            "com.aurora.store" to "Aurora Store",
        )

        fun storeName(installer: String?): String? = installer?.let { STORES[it] }

        @Suppress("DEPRECATION")
        private fun installer(context: Context): String? = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else pm.getInstallerPackageName(context.packageName)
        }.getOrNull()
    }
}

/** Esito dell'installazione; se serve conferma (niente Device Owner) mostra la richiesta di sistema. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Log.w("Updater", "installazione: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
