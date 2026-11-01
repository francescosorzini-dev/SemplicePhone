package app.semplicephone.launch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import app.semplicephone.model.SosConfig
import app.semplicephone.model.Tile
import app.semplicephone.model.TileType

/** Traduce un tile in un Intent e lo avvia. Nessuna eccezione arriva alla UI. */
class TileLauncher(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    fun launch(tile: Tile, sos: SosConfig) {
        try {
            when (tile.type) {
                TileType.APP -> {
                    val intent = appIntent(tile.target) ?: return notAvailable(tile)
                    start(intent)
                }
                // M2: chiamata diretta con CALL_PHONE; per ora apre il dialer col numero.
                TileType.CALL -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${tile.target}")))
                TileType.SMS -> start(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${tile.target}"))
                        .putExtra("sms_body", tile.body),
                )
                TileType.URL -> start(Intent(Intent.ACTION_VIEW, Uri.parse(tile.target)))
                TileType.SHORTCUT -> startShortcut(tile)
                // M4: countdown + chiamata diretta + SMS con posizione.
                TileType.SOS -> sos.numbers.firstOrNull()?.let {
                    start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it")))
                } ?: toast("Nessun numero SOS configurato")
                // M2: videochiamata WhatsApp via data row del contatto.
                TileType.WA_VIDEO -> toast("Videochiamata: disponibile a breve")
                TileType.CLOCK -> Unit
            }
        } catch (e: ActivityNotFoundException) {
            notAvailable(tile)
        } catch (e: SecurityException) {
            Log.w(TAG, "avvio negato per ${tile.id}", e)
            notAvailable(tile)
        }
    }

    /** Package name o ruolo "@xxx" → intent di avvio, null se non risolvibile. */
    fun appIntent(target: String): Intent? = when (target) {
        "@dialer" -> Intent(Intent.ACTION_DIAL)
        "@sms" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
        "@camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        "@gallery" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
        "@browser" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        else -> pm.getLaunchIntentForPackage(target)
    }?.takeIf { it.resolveActivity(pm) != null }

    /** Icona dell'app che gestirebbe il tile, per IconKind.AUTO/APP. */
    fun appIcon(target: String): Drawable? = try {
        appIntent(target)?.resolveActivity(pm)?.let { pm.getActivityIcon(it) }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun isAvailable(tile: Tile): Boolean = when (tile.type) {
        TileType.APP -> appIntent(tile.target) != null
        TileType.SHORTCUT -> tile.target.contains('/')
        else -> true
    }

    private fun startShortcut(tile: Tile) {
        val (pkg, id) = tile.target.split('/', limit = 2).takeIf { it.size == 2 }
            ?: return notAvailable(tile)
        // Consentito solo se SemplicePhone è il launcher predefinito.
        val la = context.getSystemService(LauncherApps::class.java)
        if (!la.hasShortcutHostPermission()) return toast("Imposta SemplicePhone come Home")
        la.startShortcut(pkg, id, null, null, Process.myUserHandle())
    }

    private fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun notAvailable(tile: Tile) = toast("${tile.label.ifBlank { "Funzione" }} non disponibile")

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "TileLauncher"
    }
}
