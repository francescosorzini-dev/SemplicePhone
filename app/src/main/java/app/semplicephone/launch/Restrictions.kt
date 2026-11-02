package app.semplicephone.launch

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * «Impostazioni con restrizioni» di Android: per le app installate da un file scaricato
 * (Android 13+) blocca l'accesso alle notifiche; da Android 15 anche i permessi SMS e
 * l'amministratore del dispositivo, finché l'utente non le consente da Info app.
 */
object Restrictions {

    const val ADB_ALLOW = "adb shell appops set app.semplicephone ACCESS_RESTRICTED_SETTINGS allow"
    private const val OP = "android:access_restricted_settings"

    /** true se l'app è installata da file e le impostazioni con restrizioni non sono ancora consentite. */
    fun likelyRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val fromFile = runCatching {
            val src: InstallSourceInfo = context.packageManager.getInstallSourceInfo(context.packageName)
            src.packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE ||
                src.packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
        }.getOrDefault(true)
        if (!fromFile) return false
        // Operazione non pubblica: se il sistema la riconosce e risulta già consentita, nessuna restrizione.
        val allowed = runCatching {
            context.getSystemService(AppOpsManager::class.java)
                .unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        return !allowed
    }

    /** Permessi runtime soggetti a restrizione sulla versione Android in uso. */
    fun isRestrictedPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT >= 35 && permission in setOf(
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_MMS,
        )

    fun openAppInfo(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
