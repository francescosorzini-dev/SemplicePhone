package app.semplicephone.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat

/** Contatto scelto dal picker (righe "Phone": accesso temporaneo concesso dal picker, senza permesso). */
data class PickedContact(val name: String, val number: String, val lookupKey: String?)

/**
 * Accesso alla rubrica: foto per i tile, risoluzione numero → contatto, data row WhatsApp.
 * Tutto ciò che legge la rubrica oltre al picker richiede READ_CONTACTS e degrada senza.
 */
class Contacts(private val context: Context) {

    private val cr get() = context.contentResolver
    private val photos = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Risultato di Intent(ACTION_PICK, Phone.CONTENT_URI). */
    fun readPicked(uri: Uri): PickedContact? = try {
        cr.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else PickedContact(c.getString(0).orEmpty(), c.getString(1).orEmpty().filter { it.isDigit() || it == '+' }, c.getString(2))
        }
    } catch (e: Exception) {
        Log.w(TAG, "lettura contatto scelto fallita", e)
        null
    }

    /** Contact URI da "contact:<lookupKey>" oppure dal numero. */
    fun contactUri(ref: String, number: String): Uri? {
        if (!canRead()) return null
        return try {
            val key = ref.removePrefix("contact:").takeIf { ref.startsWith("contact:") && it.isNotBlank() }
            if (key != null) {
                ContactsContract.Contacts.lookupContact(cr, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key))
                    ?.let { return it }
            }
            if (number.isBlank()) return null
            val filter = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            cr.query(filter, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, c.getLong(0)) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "risoluzione contatto fallita", e)
            null
        }
    }

    /**
     * Il numero è in rubrica? null = non si può sapere (manca READ_CONTACTS o errore).
     * Prima la ricerca di sistema (PhoneLookup, gestisce prefissi e formati), poi un confronto
     * sulle ultime 9 cifre di tutti i numeri salvati: copre i formati che PhoneLookup non abbina
     * (0039…, spazi, numeri salvati senza prefisso o con prefisso diverso).
     */
    fun isInContacts(number: String): Boolean? {
        if (!canRead()) return null
        val n = app.semplicephone.remote.SmsCommand.normalize(number)
        if (n.length < 6) return false
        return try {
            val filter = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val direct = cr.query(filter, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } ?: false
            direct || (cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER),
                null, null, null,
            )?.use { c ->
                var found = false
                while (!found && c.moveToNext()) {
                    found = (0..1).any { i -> c.getString(i)?.let { app.semplicephone.remote.SmsCommand.normalize(it) == n } == true }
                }
                found
            } ?: false)
        } catch (e: Exception) {
            Log.w(TAG, "ricerca in rubrica fallita", e)
            null
        }
    }

    /**
     * Nome in rubrica per [number], o il numero stesso se sconosciuto o senza permesso.
     * PhoneLookup abbina i formati secondo il paese del telefono; se non trova nulla si confrontano
     * le ultime 9 cifre (3331234567 contro +39 333 123 4567, 0039…).
     */
    fun displayName(number: String): String {
        if (!canRead() || number.isBlank()) return number
        return try {
            val filter = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            cr.query(filter, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: nameByDigits(number) ?: number
        } catch (e: Exception) {
            number
        }
    }

    private fun nameByDigits(number: String): String? {
        val n = app.semplicephone.remote.SmsCommand.normalize(number)
        if (n.length < 6) return null
        return cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if ((0..1).any { i -> c.getString(i)?.let { app.semplicephone.remote.SmsCommand.normalize(it) == n } == true }) return@use c.getString(2)
            }
            null
        }
    }

    /** Foto del contatto ridotta a [px] di lato; null senza foto o senza permesso. */
    fun photo(ref: String, number: String, px: Int): Bitmap? {
        val cacheKey = "$ref|$number|$px"
        photos.get(cacheKey)?.let { return it }
        val uri = contactUri(ref, number) ?: return null
        return try {
            val bytes = ContactsContract.Contacts.openContactPhotoInputStream(cr, uri, true)?.use { it.readBytes() } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.also { photos.put(cacheKey, it) }
        } catch (e: Exception) {
            Log.w(TAG, "foto contatto non leggibile", e)
            null
        }
    }

    /**
     * Intent per la videochiamata WhatsApp verso [number]: usa la riga «Videochiamata» che WhatsApp
     * aggiunge ai contatti in rubrica (non documentata, quindi null se manca).
     * La riga si cerca dal numero WhatsApp che contiene (DATA1 = «393331234567@s.whatsapp.net»),
     * confrontando le ultime 9 cifre: così funziona qualunque formato abbia il numero in rubrica o nel
     * riquadro (+39, 0039, spazi), e con più numeri sullo stesso contatto prende quello giusto.
     */
    fun whatsAppVideoIntent(number: String): Intent? {
        if (!canRead()) return null
        val want = number.filter(Char::isDigit).takeLast(9)
        if (want.length < 6) return null
        return try {
            cr.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(WA_VIDEO_MIME),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val jid = c.getString(1).orEmpty().substringBefore('@').filter(Char::isDigit)
                    if (jid.takeLast(9) == want) {
                        return Intent(Intent.ACTION_VIEW)
                            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, c.getLong(0)), WA_VIDEO_MIME)
                            .setPackage(WHATSAPP)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "ricerca riga WhatsApp fallita", e)
            null
        }
    }

    /**
     * Intent per la videochiamata Telegram verso [number]. Telegram (con «Sincronizza contatti»
     * attivo) aggiunge ai contatti righe «Videochiamata»; la riga non contiene il numero in modo
     * affidabile, quindi si cercano i contatti con quel numero (ultime 9 cifre) e poi la riga di
     * Telegram dello stesso contatto. null se manca.
     */
    fun telegramVideoIntent(number: String): Intent? {
        if (!canRead()) return null
        val want = number.filter(Char::isDigit).takeLast(9)
        if (want.length < 6) return null
        return try {
            val ids = mutableSetOf<Long>()
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c -> while (c.moveToNext()) if (c.getString(1).orEmpty().filter(Char::isDigit).takeLast(9) == want) ids += c.getLong(0) }
            cr.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA3),
                "${ContactsContract.Data.MIMETYPE} IN (?, ?)", TG_VIDEO_MIMES.toTypedArray(), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val match = c.getLong(1) in ids || c.getString(3).orEmpty().filter(Char::isDigit).takeLast(9) == want
                    if (match) return Intent(Intent.ACTION_VIEW)
                        .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, c.getLong(0)), c.getString(2))
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "ricerca riga Telegram fallita", e)
            null
        }
    }

    companion object {
        private const val TAG = "Contacts"
        const val WHATSAPP = "com.whatsapp"
        /** Telegram e la sua versione dal sito (stessa app, altro nome di pacchetto). */
        val TELEGRAM_PACKAGES = listOf("org.telegram.messenger", "org.telegram.messenger.web")
        private val TG_VIDEO_MIMES = listOf(
            "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call.video",
            "vnd.android.cursor.item/vnd.org.telegram.messenger.web.android.call.video",
        )
        private const val WA_VIDEO_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
    }
}
