package app.semplicephone.launch

import app.semplicephone.R
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import app.semplicephone.data.Contacts
import app.semplicephone.model.SosConfig
import app.semplicephone.model.Tile
import app.semplicephone.model.TileType

/**
 * Traduce un tile in un Intent e lo avvia. Nessuna eccezione arriva alla UI.
 * [onSos] apre la schermata di conto alla rovescia (gestita dall'Activity).
 */
class TileLauncher(private val context: Context, val contacts: Contacts, private val onSos: () -> Unit = {}) {

    private val pm: PackageManager = context.packageManager
    private var lastLaunch = 0L

    fun launch(tile: Tile, sos: SosConfig) {
        // Antirimbalzo: un doppio tocco (frequente con mani poco ferme) non apre due volte.
        val now = SystemClock.elapsedRealtime()
        if (tile.type != TileType.SOS && now - lastLaunch < DEBOUNCE_MS) return
        lastLaunch = now
        val phone = app.semplicephone.data.ConfigRepository.get(context).config.value.phone
        val announce = if (phone.voiceConfirm) announcement(tile) else null
        if (announce != null) app.semplicephone.ui.Voice.speak(context, announce) { perform(tile, sos) }
        else perform(tile, sos)
    }

    /** Frase detta prima dell'azione («Chiamo Maria»), null per i riquadri senza azione. */
    fun announcement(tile: Tile): String? {
        val name = tile.label.ifBlank { return null }
        return when (tile.type) {
            TileType.CALL -> context.getString(R.string.voice_call, name)
            TileType.WA_VIDEO -> context.getString(R.string.voice_video, name)
            TileType.SMS -> context.getString(R.string.voice_sms, name)
            TileType.APP, TileType.URL, TileType.SHORTCUT -> context.getString(R.string.voice_open, name)
            else -> null
        }
    }

    /** Legge [text] se «leggi i riquadri» è attivo (meteo, foto…). */
    fun speakText(text: String) {
        if (app.semplicephone.data.ConfigRepository.get(context).config.value.phone.speakTiles) app.semplicephone.ui.Voice.speak(context, text)
    }

    /** Pressione lunga con «leggi i riquadri» attivo: dice il nome del riquadro. */
    fun speakLabel(tile: Tile): Boolean {
        if (!app.semplicephone.data.ConfigRepository.get(context).config.value.phone.speakTiles) return false
        app.semplicephone.ui.Voice.speak(context, tile.label)
        return true
    }

    private fun perform(tile: Tile, sos: SosConfig) {
        if (tile.type in HOME_BUTTON_TYPES) HomeButton.forNextApp(tile.homeButton)
        try {
            when (tile.type) {
                TileType.APP -> {
                    val intent = appIntent(tile.target) ?: return notAvailable(tile)
                    // CLEAR_TASK chiude le pagine lasciate aperte nell'app: si riparte sempre dall'elenco
                    // (chat di WhatsApp, conversazioni di Messaggi…), non dall'ultima schermata vista.
                    if (tileFreshStart(tile, app.semplicephone.data.ConfigRepository.get(context).config.value.phone.freshStart)) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    start(intent)
                }
                TileType.CALL -> call(tile.target)
                TileType.SMS -> start(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${tile.target}")).putExtra("sms_body", tile.body),
                )
                TileType.URL -> start(Intent(Intent.ACTION_VIEW, Uri.parse(tile.target)))
                TileType.SHORTCUT -> startShortcut(tile)
                TileType.SOS -> if (sos.numbers.isEmpty()) toast(context.getString(R.string.launch_no_sos_numbers)) else onSos()
                TileType.WA_VIDEO -> whatsAppVideo(tile)
                TileType.MISSED_CALLS -> openCallLog()
                TileType.CLOCK, TileType.WEATHER, TileType.PHOTOS, TileType.UNKNOWN -> Unit
            }
        } catch (e: ActivityNotFoundException) {
            notAvailable(tile)
        } catch (e: SecurityException) {
            Log.w(TAG, "avvio negato per ${tile.id}", e)
            notAvailable(tile)
        }
    }

    /** Richiamata dal riquadro «Chiamate perse»: stessa conferma vocale e antirimbalzo dei riquadri. */
    fun callBack(number: String, name: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < DEBOUNCE_MS) return
        lastLaunch = now
        val phone = app.semplicephone.data.ConfigRepository.get(context).config.value.phone
        if (phone.voiceConfirm && name.isNotBlank()) app.semplicephone.ui.Voice.speak(context, context.getString(R.string.voice_call, name)) { call(number) }
        else call(number)
    }

    /** Registro chiamate del telefono (edizione Play o permesso non concesso); in mancanza, il tastierino. */
    fun openCallLog() {
        try {
            start(Intent(Intent.ACTION_VIEW).setType(android.provider.CallLog.Calls.CONTENT_TYPE))
        } catch (e: ActivityNotFoundException) {
            start(Intent(Intent.ACTION_DIAL))
        }
    }

    /** Chiamata diretta se c'è CALL_PHONE, altrimenti dialer col numero già composto. */
    fun call(number: String) {
        if (number.isBlank()) return toast(context.getString(R.string.launch_no_number))
        val uri = Uri.parse("tel:${Uri.encode(number)}")
        val speaker = app.semplicephone.data.ConfigRepository.get(context).config.value.phone.speakerCalls
        if (Permissions.granted(context, Manifest.permission.CALL_PHONE)) {
            if (speaker) {
                // Volume della chiamata al massimo e vivavoce chiesto al sistema (extra ufficiale di Telecom).
                runCatching {
                    val am = context.getSystemService(android.media.AudioManager::class.java)
                    am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL), 0)
                }
                val extras = android.os.Bundle().apply { putBoolean(android.telecom.TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true) }
                val ok = runCatching {
                    @Suppress("MissingPermission")
                    context.getSystemService(android.telecom.TelecomManager::class.java).placeCall(uri, extras)
                }.isSuccess
                if (ok) return
            }
            start(Intent(Intent.ACTION_CALL, uri))
        } else {
            start(Intent(Intent.ACTION_DIAL, uri).putExtra(android.telecom.TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, speaker))
        }
    }

    private fun whatsAppVideo(tile: Tile) {
        if (tile.target.isBlank()) return toast(context.getString(R.string.launch_no_number))
        if (pm.getLaunchIntentForPackage(Contacts.WHATSAPP) == null) return toast(context.getString(R.string.launch_no_whatsapp))
        val intent = contacts.whatsAppVideoIntent(tile.target)
        if (intent != null) {
            start(intent)
        } else {
            // Contatto non in rubrica o senza WhatsApp: apre almeno la chat.
            toast(context.getString(R.string.launch_whatsapp_chat))
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${tile.target.filter(Char::isDigit)}")).setPackage(Contacts.WHATSAPP))
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

    /** Pacchetti le cui notifiche vanno contate nel pallino del riquadro. */
    fun badgePackages(tile: Tile): Set<String> = when (tile.type) {
        TileType.APP -> {
            val pkg = appIntent(tile.target)?.resolveActivity(pm)?.packageName
            when {
                pkg == null -> emptySet()
                tile.target == "@dialer" -> setOf(pkg) + app.semplicephone.phone.Badges.CALL_PACKAGES
                else -> setOf(pkg)
            }
        }
        TileType.SHORTCUT -> setOf(tile.target.substringBefore('/'))
        else -> emptySet()
    }

    fun isAvailable(tile: Tile): Boolean = when (tile.type) {
        TileType.APP -> appIntent(tile.target) != null
        TileType.SHORTCUT -> tile.target.contains('/')
        TileType.WA_VIDEO -> pm.getLaunchIntentForPackage(Contacts.WHATSAPP) != null
        else -> true
    }

    private fun startShortcut(tile: Tile) {
        val (pkg, id) = tile.target.split('/', limit = 2).takeIf { it.size == 2 }
            ?: return notAvailable(tile)
        // Consentito solo se SemplicePhone è il launcher predefinito.
        val la = context.getSystemService(LauncherApps::class.java)
        if (!la.hasShortcutHostPermission()) return toast(context.getString(R.string.launch_set_home))
        la.startShortcut(pkg, id, null, null, Process.myUserHandle())
    }

    fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun notAvailable(tile: Tile) = toast(context.getString(R.string.launch_unavailable, tile.label.ifBlank { context.getString(R.string.label_feature) }))

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "TileLauncher"
        private const val DEBOUNCE_MS = 1200L
        /** Riquadri che aprono un'altra app: per questi si può nascondere il pulsante «Casa». */
        val HOME_BUTTON_TYPES = setOf(TileType.APP, TileType.SHORTCUT, TileType.URL, TileType.SMS, TileType.WA_VIDEO)
    }
}

/** Apertura dell'app di un riquadro: la scelta del riquadro, altrimenti quella generale del telefono. */
fun tileFreshStart(tile: Tile, phoneDefault: Boolean): Boolean = when (tile.open) {
    app.semplicephone.model.OPEN_FRESH -> true
    app.semplicephone.model.OPEN_RESUME -> false
    else -> phoneDefault
}
