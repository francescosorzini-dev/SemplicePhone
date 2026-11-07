package app.semplicephone.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.semplicephone.model.Tile

/** Una foto o un video dell'album: foto del portale ("media:…") o file di una cartella del telefono. */
data class AlbumItem(val ref: String, val video: Boolean, val at: Long, val name: String = "")

/**
 * Album: foto caricate (portale o telefono) più il contenuto delle cartelle del telefono scelte col
 * selettore di Android (permesso di lettura solo su quelle cartelle, niente permessi sulle foto).
 */
object Album {
    private const val TAG = "Album"
    private const val MAX_ITEMS = 300
    private const val MAX_DEPTH = 6
    private const val MAX_SCAN = 20_000
    /** Foto inviate da WhatsApp (doppioni) e cartelle private: non sono ricordi da mostrare. */
    private val SKIP_DIRS = setOf("Sent", "Private", "Inviati")

    /** Nome leggibile di una cartella scelta (es. «WhatsApp Images»). */
    fun folderName(context: Context, tree: String): String = runCatching {
        val uri = Uri.parse(tree)
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: Uri.parse(tree).lastPathSegment?.substringAfterLast(':').orEmpty()

    /** Tiene il permesso di lettura sulla cartella anche dopo il riavvio. */
    fun keep(context: Context, tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.w(TAG, "permesso cartella non tenuto", it) }
    }

    fun release(context: Context, tree: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Cartelle della config ancora leggibili su questo telefono (le altre vengono ignorate). */
    fun readable(context: Context, tile: Tile): List<String> {
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        return tile.folders.filter { it in granted }
    }

    /** Elementi da mostrare: prima le foto caricate, poi quelle delle cartelle dalla più recente. */
    fun items(context: Context, tile: Tile): List<AlbumItem> {
        val own = tile.photos.filter { Media.exists(context, it) }.map { AlbumItem(it, video = false, at = 0) }
        val fromFolders = readable(context, tile).flatMap { list(context, Uri.parse(it)) }
            .distinctBy { it.ref }.sortedByDescending { it.at }
        if (fromFolders.isNotEmpty()) Log.i(TAG, "dalle cartelle: ${fromFolders.size} (${fromFolders.count { it.video }} video): ${fromFolders.take(10).joinToString { it.name }}")
        return (own + fromFolders).take(MAX_ITEMS)
    }

    /**
     * Foto e video della cartella e di tutte le sue sottocartelle (es. «DCIM» → Camera, Screenshots…,
     * «WhatsApp/Media» → Images, Video…). Si salta ciò che inizia con «.» (cartelle nascoste, .thumbnails,
     * .Statuses) e le cartelle «Sent»/«Private» di WhatsApp; al massimo [MAX_DEPTH] livelli e [MAX_SCAN] file.
     */
    private fun list(context: Context, tree: Uri, audio: Boolean = false): List<AlbumItem> = runCatching {
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val out = mutableListOf<AlbumItem>()
        val queue = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to 0))
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_SCAN) {
            val (docId, depth) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && seen < MAX_SCAN) {
                    seen++
                    val mime = c.getString(1).orEmpty()
                    val name = c.getString(3).orEmpty()
                    if (name.startsWith(".")) continue
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                            if (depth < MAX_DEPTH && name !in SKIP_DIRS) queue += c.getString(0) to depth + 1
                        audio && mime.startsWith("audio/") ->
                            out += AlbumItem(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(), false, c.getLong(2), name)
                        !audio && (mime.startsWith("video/") || mime.startsWith("image/")) ->
                            out += AlbumItem(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(), mime.startsWith("video/"), c.getLong(2), name)
                    }
                }
            }
        }
        out
    }.onFailure { Log.w(TAG, "cartella non leggibile: $tree", it) }.getOrDefault(emptyList())

    /** Canzoni delle cartelle della [tile] Musica, in ordine di nome (come in un album). */
    fun songs(context: Context, tile: Tile): List<AlbumItem> =
        readable(context, tile).flatMap { list(context, Uri.parse(it), audio = true) }.distinctBy { it.ref }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).take(2000)

    /** Immagine ridotta a [maxPx] (lato lungo): la foto stessa o un fotogramma del video. */
    fun bitmap(context: Context, item: AlbumItem, maxPx: Int): Bitmap? = runCatching {
        if (item.ref.startsWith("media:")) return Media.bitmap(context, item.ref, maxPx)
        val uri = Uri.parse(item.ref)
        if (item.video) {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxPx, maxPx)
            }
        } else {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                val s = info.size
                val scale = minOf(1f, maxPx.toFloat() / maxOf(s.width, s.height))
                dec.setTargetSize((s.width * scale).toInt().coerceAtLeast(1), (s.height * scale).toInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }.onFailure { Log.w(TAG, "anteprima non letta", it) }.getOrNull()
}
