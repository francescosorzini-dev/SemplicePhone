package app.semplicephone.data

import android.content.Context
import android.net.Uri
import android.util.Log
import app.semplicephone.model.LauncherConfig
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copie della configurazione senza portale: le ultime versioni salvate sul telefono (una a ogni
 * salvataggio) e un file da esportare o importare (config + foto), per chi cambia telefono.
 */
object Backup {
    private const val TAG = "Backup"
    private const val KEEP = 10
    private const val MAX_IMPORT = 40L * 1024 * 1024

    private fun dir(context: Context) = File(context.filesDir, "backups").apply { mkdirs() }

    /** Copia della config attuale, prima che venga sostituita. */
    fun snapshot(context: Context) {
        val cur = File(context.filesDir, "config.json").takeIf { it.exists() } ?: return
        runCatching {
            val last = list(context).firstOrNull()?.second
            if (last != null && last.readBytes().contentEquals(cur.readBytes())) return
            cur.copyTo(File(dir(context), "${System.currentTimeMillis()}.json"), overwrite = true)
            list(context).drop(KEEP).forEach { it.second.delete() }
        }.onFailure { Log.w(TAG, "copia non salvata", it) }
    }

    /** Copie salvate, dalla più recente: (momento in ms, file). */
    fun list(context: Context): List<Pair<Long, File>> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> f.name.removeSuffix(".json").toLongOrNull()?.let { it to f } }
            .sortedByDescending { it.first }

    fun load(file: File): LauncherConfig? = runCatching { ConfigRepository.parse(file.readText(), "backup") }.getOrNull()

    /** File unico: config.json e le foto usate (media/<hash>.img). */
    fun export(context: Context, cfg: LauncherConfig, uri: Uri): Boolean = runCatching {
        val text = File(context.filesDir, "config.json").takeIf { it.exists() }?.readText()
            ?: ConfigRepository.toJson(cfg)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("config.json")); zip.write(text.toByteArray()); zip.closeEntry()
                Media.referenced(cfg).forEach { ref ->
                    val f = Media.file(context, ref)?.takeIf { it.exists() } ?: return@forEach
                    zip.putNextEntry(ZipEntry("media/${f.name}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        } != null
    }.onFailure { Log.w(TAG, "esportazione non riuscita", it) }.getOrDefault(false)

    /** Legge un file esportato: config valida (null se no); le foto si copiano solo se l'hash torna. */
    fun import(context: Context, uri: Uri): LauncherConfig? = runCatching {
        var cfgText: String? = null
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val bytes = zip.readBytes()
                    total += bytes.size
                    if (total > MAX_IMPORT) error("file troppo grande")
                    when {
                        e.name == "config.json" -> cfgText = bytes.toString(Charsets.UTF_8)
                        e.name.startsWith("media/") && e.name.endsWith(".img") -> {
                            val hash = e.name.removePrefix("media/").removeSuffix(".img")
                            val real = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                            val dest = Media.file(context, "media:$hash")
                            if (real == hash && dest != null && !dest.exists()) dest.writeBytes(bytes)
                        }
                    }
                }
            }
        }
        cfgText?.let { ConfigRepository.parse(it, "import") }
    }.onFailure { Log.w(TAG, "importazione non riuscita", it) }.getOrNull()
}
