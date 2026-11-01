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
     * Intent per la videochiamata WhatsApp verso [number]: usa la data row che WhatsApp
     * aggiunge al contatto. Non documentato da WhatsApp, quindi null se non trovato.
     */
    fun whatsAppVideoIntent(number: String): Intent? {
        val contact = contactUri("", number) ?: return null
        val contactId = ContentUris.parseId(contact)
        return try {
            cr.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(contactId.toString(), WA_VIDEO_MIME),
                null,
            )?.use { c ->
                if (!c.moveToFirst()) null
                else Intent(Intent.ACTION_VIEW)
                    .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, c.getLong(0)), WA_VIDEO_MIME)
                    .setPackage(WHATSAPP)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ricerca data row WhatsApp fallita", e)
            null
        }
    }

    companion object {
        private const val TAG = "Contacts"
        const val WHATSAPP = "com.whatsapp"
        private const val WA_VIDEO_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
    }
}
