package app.semplicephone.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.Location
import android.telephony.SmsManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.semplicephone.BuildConfig
import app.semplicephone.R
import app.semplicephone.model.Tile
import app.semplicephone.remote.RemoteEvents
import app.semplicephone.sos.LocationFetcher
import app.semplicephone.ui.Palette
import app.semplicephone.ui.Symbols
import app.semplicephone.ui.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val ToolBg = Color(0xFFF6F4EF)

@Composable
private fun ToolFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(ToolBg).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.search_back), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        content()
    }
}

@Composable
private fun BigButton(text: String, color: Color, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Button(
        onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(24.dp), modifier = modifier.heightIn(min = 96.dp),
    ) { Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

private fun granted(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

// ------------------------------------------------------------------ Lente d'ingrandimento

/**
 * Lente: anteprima della fotocamera a tutto schermo, zoom con pulsanti grandi, luce, e «FERMA»
 * per bloccare l'immagine e leggerla con calma (scatola delle medicine, scadenze, bollette).
 */
@Composable
fun MagnifierScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(0.5f) }
    var light by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    var view by remember { mutableStateOf<PreviewView?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } } }

    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (!allowed) Text(stringResource(R.string.magnifier_no_camera), color = Color.White, fontSize = 24.sp, textAlign = TextAlign.Center)
            else {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { pv ->
                            view = pv
                            val future = ProcessCameraProvider.getInstance(ctx)
                            future.addListener({
                                runCatching {
                                    val provider = future.get()
                                    val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                                    provider.unbindAll()
                                    camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview).also {
                                        it.cameraControl.setLinearZoom(zoom)
                                    }
                                }.onFailure { Log.w("Magnifier", "fotocamera non avviata", it) }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                frozen?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(Color.Black)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("−", Color(0xFF1F4FA8), Modifier.weight(1f)) { zoom = (zoom - 0.15f).coerceIn(0f, 1f); camera?.cameraControl?.setLinearZoom(zoom) }
            BigButton("+", Color(0xFF1F4FA8), Modifier.weight(1f)) { zoom = (zoom + 0.15f).coerceIn(0f, 1f); camera?.cameraControl?.setLinearZoom(zoom) }
            BigButton(stringResource(if (light) R.string.magnifier_light_off else R.string.magnifier_light_on), Color(0xFFFFA726), Modifier.weight(1.4f)) {
                light = !light; camera?.cameraControl?.enableTorch(light)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(stringResource(if (frozen == null) R.string.magnifier_freeze else R.string.magnifier_live), Color(0xFF1B7A34), Modifier.weight(1.4f)) {
                frozen = if (frozen == null) view?.bitmap else null
            }
            BigButton(stringResource(R.string.video_close), Color(0xFFC62828), Modifier.weight(1f)) { onClose() }
        }
    }
}

// ------------------------------------------------------------------ Dove sono?

object WhereAmI {
    /** Indirizzo leggibile della posizione: Geocoder del telefono, altrimenti OpenStreetMap. */
    suspend fun address(context: Context, loc: Location): String? = withContext(Dispatchers.IO) {
        val lang = Locale.getDefault()
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, lang).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(
                    listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifBlank { null },
                    a.locality ?: a.subAdminArea,
                ).joinToString(", ").ifBlank { a.getAddressLine(0) }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: runCatching {
            val url = URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&lat=${loc.latitude}&lon=${loc.longitude}&accept-language=${lang.language}")
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000; setRequestProperty("User-Agent", "SemplicePhone (launcher)")
            }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val a = j.optJSONObject("address")
            if (a != null) listOfNotNull(
                listOf(a.optString("road"), a.optString("house_number")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
                listOf("city", "town", "village", "hamlet").map { a.optString(it) }.firstOrNull { it.isNotBlank() },
            ).joinToString(", ").ifBlank { j.optString("display_name") }
            else j.optString("display_name").ifBlank { null }
        }.getOrNull()
    }
}

/** «Dove sono?»: indirizzo grande e detto a voce, poi un pulsante per chiamare il primo familiare SOS. */
@Composable
fun WhereAmIScreen(sosNumbers: List<String>, nameOf: (String) -> String, onCall: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> allowed = r.values.any { it } }
    var text by remember { mutableStateOf<String?>(null) }
    var tries by remember { mutableIntStateOf(0) }
    LaunchedEffect(allowed, tries) {
        if (!allowed) { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return@LaunchedEffect }
        text = null
        val loc = LocationFetcher(context).current(15_000) ?: LocationFetcher(context).lastKnown()
        val addr = loc?.let { WhereAmI.address(context, it) }
        text = when {
            addr != null -> context.getString(R.string.where_you_are, addr)
            loc != null -> context.getString(R.string.where_no_address)
            else -> context.getString(R.string.where_no_location)
        }
        Voice.speak(context, text!!)
    }
    ToolFrame(stringResource(R.string.label_where), onClose) {
        Icon(Symbols.get("pin"), null, Modifier.size(96.dp), tint = Palette.Ink)
        Text(text ?: stringResource(R.string.where_searching), fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
        if (text != null) OutlinedButton(onClick = { Voice.speak(context, text!!) }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp), shape = RoundedCornerShape(20.dp)) {
            Text(stringResource(R.string.where_repeat), fontSize = 22.sp, color = Palette.Ink)
        }
        sosNumbers.firstOrNull()?.let { n ->
            BigButton(stringResource(R.string.where_call, nameOf(n)), Color(0xFF1B7A34)) { onCall(n) }
        }
    }
}

// ------------------------------------------------------------------ Mi chiami?

object CallMe {
    /** Manda la richiesta: SMS diretto dove si può, sempre anche un avviso nel portale. True se è partito l'SMS. */
    fun send(context: Context, tile: Tile): Boolean {
        val text = context.getString(R.string.callme_sms)
        RemoteEvents.enqueue(context, "call_me", context.getString(R.string.callme_event, tile.label.ifBlank { tile.target }), mapOf("number" to tile.target))
        if (!BuildConfig.SEND_SMS || !granted(context, Manifest.permission.SEND_SMS) || tile.target.isBlank()) return false
        return runCatching { context.getSystemService(SmsManager::class.java).sendTextMessage(tile.target, null, text, null, null); true }
            .onFailure { Log.w("CallMe", "SMS non inviato", it) }.getOrDefault(false)
    }
}

/** «Mi chiami?»: conferma grande, poi la richiesta parte e il telefono dice che è stata mandata. */
@Composable
fun CallMeScreen(tile: Tile, onSmsComposer: (String, String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var sent by remember { mutableStateOf(false) }
    val name = tile.label.ifBlank { tile.target }
    ToolFrame(stringResource(R.string.label_call_me), onClose) {
        Icon(Symbols.get("callme"), null, Modifier.size(96.dp), tint = Palette.Ink)
        Text(
            stringResource(if (sent) R.string.callme_sent else R.string.callme_ask, name),
            fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center,
        )
        if (!sent) {
            BigButton(stringResource(R.string.callme_yes), Color(0xFF1B7A34)) {
                val sms = CallMe.send(context, tile)
                sent = true
                Voice.speak(context, context.getString(R.string.callme_sent, name))
                // Senza invio automatico (es. versione Play) si apre il messaggio già scritto.
                if (!sms && tile.target.isNotBlank()) onSmsComposer(tile.target, context.getString(R.string.callme_sms))
            }
            BigButton(stringResource(R.string.callme_no), Color(0xFF6A6A6A)) { onClose() }
        } else BigButton(stringResource(R.string.video_close), Color(0xFF1F4FA8)) { onClose() }
    }
}

// ------------------------------------------------------------------ Notizie lette a voce

object News {
    /** Fonti pronte (nome, indirizzo del feed RSS). */
    val PRESETS = listOf(
        "ANSA" to "https://www.ansa.it/sito/notizie/topnews/topnews_rss.xml",
        "Rai News" to "https://www.rainews.it/rss/tutti",
        "Il Post" to "https://www.ilpost.it/feed/",
        "BBC News" to "https://feeds.bbci.co.uk/news/rss.xml",
        "Tagesschau" to "https://www.tagesschau.de/index~rss2.xml",
        "France Info" to "https://www.francetvinfo.fr/titres.rss",
        "RTVE" to "https://api2.rtve.es/rss/temas_noticias.xml",
    )

    /** Titoli del feed (RSS o Atom), al massimo 20. */
    suspend fun headlines(url: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SemplicePhone (launcher)")
            }
            val parser = android.util.Xml.newPullParser()
            c.inputStream.use { input ->
                parser.setInput(input, null)
                val out = mutableListOf<String>()
                var inItem = false
                while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && out.size < 20) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "item", "entry" -> inItem = true
                            "title" -> if (inItem) parser.nextText().trim().replace(Regex("\\s+"), " ").takeIf { it.isNotBlank() }?.let { out += it }
                        }
                    } else if (parser.eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name in setOf("item", "entry")) inItem = false
                }
                out
            }
        }.onFailure { Log.w("News", "notizie non lette", it) }.getOrDefault(emptyList())
    }
}

/** Notizie: un titolo alla volta, grande, letto a voce; «Avanti» o lettura continua. */
@Composable
fun NewsScreen(tile: Tile, onClose: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<String>?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var reading by remember { mutableStateOf(false) }
    LaunchedEffect(tile.target) { items = News.headlines(tile.target.ifBlank { News.PRESETS.first().second }) }
    fun read(i: Int) {
        val list = items ?: return
        if (i !in list.indices) { reading = false; return }
        index = i
        Voice.speak(context, list[i]) { if (reading) read(i + 1) }
    }
    DisposableEffect(Unit) { onDispose { reading = false; Voice.speak(context, " ") } }
    ToolFrame(tile.label.ifBlank { stringResource(R.string.label_news) }, onClose) {
        val list = items
        when {
            list == null -> Text(stringResource(R.string.news_loading), fontSize = 26.sp, color = Palette.Ink)
            list.isEmpty() -> Text(stringResource(R.string.news_none), fontSize = 26.sp, color = Palette.Ink, textAlign = TextAlign.Center)
            else -> {
                Text("${index + 1} / ${list.size}", fontSize = 18.sp, color = Color(0xFF555555))
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    Text(list[index], fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
                }
                BigButton(stringResource(if (reading) R.string.news_stop else R.string.news_read_all), if (reading) Color(0xFFC62828) else Color(0xFF1B7A34)) {
                    reading = !reading
                    if (reading) read(index) else Voice.speak(context, " ")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { reading = false; read((index - 1).coerceAtLeast(0)) }, modifier = Modifier.weight(1f).heightIn(min = 80.dp), shape = RoundedCornerShape(20.dp)) {
                        Text("‹ " + stringResource(R.string.radio_prev), fontSize = 22.sp, color = Palette.Ink)
                    }
                    OutlinedButton(onClick = { reading = false; read((index + 1).coerceAtMost(list.lastIndex)) }, modifier = Modifier.weight(1f).heightIn(min = 80.dp), shape = RoundedCornerShape(20.dp)) {
                        Text(stringResource(R.string.radio_next) + " ›", fontSize = 22.sp, color = Palette.Ink)
                    }
                }
            }
        }
    }
}
