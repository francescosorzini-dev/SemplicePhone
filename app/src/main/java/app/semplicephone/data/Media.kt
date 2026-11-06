package app.semplicephone.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import app.semplicephone.model.LauncherConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Immagini della configurazione (foto di famiglia, foto del farmaco). Nella config compaiono come
 * «media:<sha256>»: il riferimento è firmato insieme al resto, quindi un'immagine scaricata dal
 * portale si accetta solo se il suo hash corrisponde.
 */
object Media {
    const val PREFIX = "media:"
    private const val TAG = "Media"
    private val HASH = Regex("[0-9a-f]{64}")
    private const val MAX_SIDE = 1600
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun hashOf(ref: String): String? = ref.removePrefix(PREFIX).takeIf { ref.startsWith(PREFIX) && HASH.matches(it) }

    private fun dir(context: Context) = File(context.filesDir, "media").apply { mkdirs() }

    fun file(context: Context, ref: String): File? = hashOf(ref)?.let { File(dir(context), "$it.img") }

    fun exists(context: Context, ref: String): Boolean = file(context, ref)?.exists() == true

    /** Riferimenti usati dalla config: riquadri foto e foto dei promemoria. */
    fun referenced(cfg: LauncherConfig): Set<String> =
        (cfg.pages.flatMap { p -> p.tiles.flatMap { it.photos + it.icon.ref } } + cfg.reminders.map { it.photo })
            .filter { hashOf(it) != null }.toSet()

    /** Importa un'immagine scelta sul telefono: ridotta a 1600 px, JPEG, nominata col suo hash. */
    fun import(context: Context, uri: Uri): String? = runCatching {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val s = info.size
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(s.width, s.height))
            dec.setTargetSize((s.width * scale).toInt().coerceAtLeast(1), (s.height * scale).toInt().coerceAtLeast(1))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bytes = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
        store(context, bytes)
    }.onFailure { Log.w(TAG, "import non riuscito", it) }.getOrNull()

    private fun store(context: Context, bytes: ByteArray): String {
        val hash = sha256(bytes)
        val f = File(dir(context), "$hash.img")
        if (!f.exists()) {
            val tmp = File(dir(context), "$hash.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        }
        return PREFIX + hash
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Bitmap ridotto per stare in [maxPx] (lato lungo), null se manca il file. */
    fun bitmap(context: Context, ref: String, maxPx: Int): Bitmap? {
        val f = file(context, ref)?.takeIf { it.exists() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Scarica dal portale le immagini della config che mancano sul telefono. [baseUrl] è l'indirizzo
     * del telefono (…?device=ID); si accetta solo un file con lo stesso hash del riferimento firmato.
     */
    fun fetchMissing(context: Context, cfg: LauncherConfig, baseUrl: String, secret: String) {
        for (ref in referenced(cfg)) {
            if (exists(context, ref)) continue
            val hash = hashOf(ref) ?: continue
            runCatching {
                val conn = (URL("$baseUrl&media=$hash").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("X-Device-Secret", secret)
                }
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val bytes = conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        require(out.size() <= MAX_BYTES) { "immagine troppo grande" }
                    }
                    out.toByteArray()
                }
                require(sha256(bytes) == hash) { "hash diverso" }
                store(context, bytes)
            }.onFailure { Log.w(TAG, "download $hash non riuscito", it) }
        }
    }

    /** Toglie le immagini non più usate dalla config salvata. */
    fun cleanup(context: Context, cfg: LauncherConfig) {
        val keep = referenced(cfg).mapNotNull { hashOf(it) }.toSet()
        dir(context).listFiles()?.forEach { f ->
            if (f.name.substringBefore('.') !in keep) f.delete()
        }
    }
}
