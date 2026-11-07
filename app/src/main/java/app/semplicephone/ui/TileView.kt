package app.semplicephone.ui

import androidx.compose.foundation.combinedClickable
import app.semplicephone.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.semplicephone.launch.TileLauncher
import app.semplicephone.model.IconKind
import app.semplicephone.model.SosConfig
import app.semplicephone.model.Tile
import app.semplicephone.model.TileType
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TileShape = RoundedCornerShape(18.dp)

/**
 * Dimensioni del testo in dp convertite in sp senza fontScale: con il carattere di sistema
 * ingrandito (frequente negli anziani) il testo non deve uscire dal tile, che è già grande.
 */
@Composable
private fun Dp.fixedSp(): TextUnit = with(LocalDensity.current) { this@fixedSp.toSp() }

/** Aspetto della Home: tema (normale, alto contrasto, notte) e apertura dei riquadri tenendo premuto. */
data class TileLook(val contrast: Boolean = false, val night: Boolean = false, val holdToOpen: Boolean = false)

val LocalTileLook = androidx.compose.runtime.staticCompositionLocalOf { TileLook() }

@Composable
fun TileView(
    tile: Tile,
    sos: SosConfig,
    launcher: TileLauncher,
    modifier0: Modifier = Modifier,
    onSecretTap: () -> Unit = {},
    badge: Int = 0,
) {
    val look = LocalTileLook.current
    // Alto contrasto: bordo bianco spesso su fondo nero. Notte: riquadri velati, meno abbaglianti.
    val modifier = modifier0
        .then(if (look.contrast) Modifier.border(4.dp, Color.White, TileShape) else Modifier)
        .then(
            if (look.night) Modifier.clip(TileShape).drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = 0.38f)) }
            else Modifier,
        )
    when (tile.type) {
        TileType.CLOCK -> ClockTile(tile, launcher, modifier, onSecretTap)
        TileType.SOS -> SosTile(tile, sos, launcher, modifier)
        TileType.WEATHER -> WeatherTile(tile, launcher, modifier)
        TileType.PHOTOS -> PhotosTile(tile, launcher, modifier)
        TileType.MISSED_CALLS -> MissedCallsTile(tile, sos, launcher, modifier)
        TileType.MESSAGES -> MessagesTile(tile, sos, launcher, modifier)
        TileType.UNKNOWN -> Box(modifier.clip(TileShape).background(Color(0xFFD9D3C7)))
        else -> ActionTile(tile, sos, launcher, modifier, badge)
    }
}

@Composable
private fun ActionTile(tile: Tile, sos: SosConfig, launcher: TileLauncher, modifier: Modifier, badge: Int) {
    val available = remember(tile) { launcher.isAvailable(tile) }
    val bg = parseColor(tile.color).let { if (available) it else it.copy(alpha = 0.35f) }
    val fg = contentColorFor(parseColor(tile.color))
    val wide = tile.w > tile.h
    val badgeDesc = stringResource(R.string.tile_badge_desc, badge)
    val hold = LocalTileLook.current.holdToOpen

    Box(
        modifier
            .clip(TileShape)
            .background(bg)
            .border(if (bg.isLight()) 2.dp else 0.dp, Palette.Ink, TileShape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = tile.label,
                // «Tieni premuto per aprire» (tremore): il tocco breve dice solo il nome.
                onLongClick = { if (hold) launcher.launch(tile, sos) else launcher.speakLabel(tile) },
                onClick = { if (hold) launcher.holdHint(tile) else launcher.launch(tile, sos) },
            )
            .semantics { if (badge > 0) stateDescription = badgeDesc },
        contentAlignment = Alignment.Center,
    ) {
        val fullPhoto = tile.icon.fill &&
            (tile.icon.kind == IconKind.PHOTO || (tile.icon.kind == IconKind.AUTO && tile.type in PersonTypes))
        if (fullPhoto) FullPhoto(tile, launcher) { StandardContent(tile, launcher, fg, wide) }
        else StandardContent(tile, launcher, fg, wide)
        if (badge > 0) Badge(badge, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

/** Icona (o foto piccola) e nome, centrati nel riquadro. */
@Composable
private fun StandardContent(tile: Tile, launcher: TileLauncher, fg: Color, wide: Boolean) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            val iconSize = (minOf(maxWidth, maxHeight) * if (wide) 0.62f else 0.42f).coerceAtMost(96.dp)
            val textSize = (if (wide) 30.dp else 22.dp).fixedSp()
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TileIconView(tile, launcher, fg, iconSize)
                    TileLabel(tile.label, fg, textSize, TextAlign.Start, Modifier.weight(1f))
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TileIconView(tile, launcher, fg, iconSize)
                    TileLabel(tile.label, fg, textSize, TextAlign.Center, Modifier.fillMaxWidth())
                }
            }
        }
}

/**
 * Foto del contatto a tutto riquadro, con il nome in basso su una sfumatura scura (leggibile su
 * qualsiasi foto). Senza foto in rubrica si torna al contenuto normale.
 */
@Composable
private fun FullPhoto(tile: Tile, launcher: TileLauncher, fallback: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val px = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }
        val photo by produceState<ImageBitmap?>(null, tile.icon.ref, tile.target, px) {
            value = withContext(Dispatchers.IO) { tilePhoto(launcher, tile, px)?.asImageBitmap() }
        }
        val p = photo
        if (p == null) fallback()
        else Image(p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (p != null && tile.label.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                    .padding(start = 10.dp, end = 10.dp, top = 28.dp, bottom = 10.dp),
            ) {
                val size = (if (tile.w > tile.h) 30.dp else 24.dp).fixedSp()
                TileLabel(tile.label, Color.White, size, TextAlign.Center, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Pallino rosso con numero: grande e con bordo bianco, leggibile su qualsiasi colore del riquadro. */
@Composable
private fun Badge(count: Int, modifier: Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White)
            .padding(3.dp)
            .clip(CircleShape)
            .background(Palette.Sos),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            color = Color.White,
            fontSize = (if (count > 9) 15.dp else 19.dp).fixedSp(),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TileLabel(text: String, color: Color, size: TextUnit, align: TextAlign, modifier: Modifier) {
    if (text.isBlank()) return
    Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = size,
        lineHeight = size * 1.1f,
        fontWeight = FontWeight.Bold,
        textAlign = align,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TileIconView(tile: Tile, launcher: TileLauncher, tint: Color, size: Dp) {
    val useApp = tile.icon.kind == IconKind.APP ||
        (tile.icon.kind == IconKind.AUTO && (tile.type == TileType.APP || tile.type == TileType.SHORTCUT))
    when {
        tile.icon.kind == IconKind.NONE -> Unit
        useApp -> {
            val px = with(LocalDensity.current) { size.roundToPx() }
            val bmp: ImageBitmap? = remember(tile.target, px) {
                val pkgTarget = if (tile.type == TileType.SHORTCUT) tile.target.substringBefore('/') else tile.target
                launcher.appIcon(pkgTarget)?.toBitmap(px, px)?.asImageBitmap()
            }
            if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(size))
            else Icon(Symbols.get("grid"), null, Modifier.size(size), tint = tint)
        }
        tile.icon.kind == IconKind.PHOTO || (tile.icon.kind == IconKind.AUTO && tile.type in PersonTypes) ->
            ContactPhoto(tile, launcher, tint, size, initialsFallback = tile.icon.kind == IconKind.PHOTO)
        else -> {
            val name = tile.icon.ref.takeIf { it in Symbols.names } ?: Symbols.defaultFor(tile.type)
            Icon(Symbols.get(name), null, Modifier.size(size), tint = tint)
        }
    }
}

private val PersonTypes = setOf(TileType.CALL, TileType.WA_VIDEO, TileType.SMS)

/**
 * Foto del contatto in un cerchio con bordo. Senza foto: iniziali (icona "foto" scelta
 * esplicitamente) oppure il simbolo del tipo di tile (icona automatica).
 */
@Composable
private fun ContactPhoto(tile: Tile, launcher: TileLauncher, tint: Color, size: Dp, initialsFallback: Boolean) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val photo by produceState<ImageBitmap?>(null, tile.icon.ref, tile.target, px) {
        value = withContext(Dispatchers.IO) { tilePhoto(launcher, tile, px)?.asImageBitmap() }
    }
    val p = photo
    when {
        p != null -> Image(
            p, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).border(3.dp, tint, CircleShape),
        )
        initialsFallback -> Box(
            Modifier.size(size).clip(CircleShape).background(Color(0xFFD9D3C7)).border(3.dp, tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(tile.label), color = Palette.Ink, fontSize = (size * 0.38f).fixedSp(), fontWeight = FontWeight.Bold)
        }
        else -> Icon(Symbols.get(Symbols.defaultFor(tile.type)), null, Modifier.size(size), tint = tint)
    }
}

/** Foto del riquadro: caricata dal familiare («media:…», dal portale o dal telefono) o dalla rubrica. */
private fun tilePhoto(launcher: TileLauncher, tile: Tile, px: Int): android.graphics.Bitmap? =
    if (tile.icon.ref.startsWith("media:")) app.semplicephone.data.Media.bitmap(launcher.appContext, tile.icon.ref, px)
    else launcher.contacts.photo(tile.icon.ref, tile.target, px)

private fun initials(label: String): String =
    label.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

@Composable
private fun ClockTile(tile: Tile, launcher: TileLauncher, modifier: Modifier, onSecretTap: () -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    // Accesso impostazioni: 5 tocchi entro 3 s. Nessun feedback visivo, per non invitare a provare.
    val taps = remember { ArrayDeque<Long>() }
    val secretTap by androidx.compose.runtime.rememberUpdatedState(onSecretTap)
    LaunchedEffect(Unit) {
        while (true) {
            // Allineato al cambio minuto.
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = LocalDateTime.now()
        }
    }
    val bg = parseColor(tile.color)
    val fg = contentColorFor(bg)
    val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
    // Formato del giorno nella lingua dell'app (es. «Lunedì 2 novembre», «Monday, November 2»).
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
    val date = now.format(DateTimeFormatter.ofPattern(pattern, locale)).replaceFirstChar { it.titlecase(locale) }
    val clockDesc = stringResource(R.string.clock_desc, time, date)
    // Pressione lunga (con «leggi i riquadri» attivo): il telefono dice ora e data.
    val spoken by androidx.compose.runtime.rememberUpdatedState(stringResource(R.string.clock_spoken, time, date))

    BoxWithConstraints(
        modifier
            .clip(TileShape)
            .background(bg)
            .border(if (bg.isLight()) 2.dp else 0.dp, Palette.Ink, TileShape)
            .secretTaps(taps, onLongPress = { launcher.speakText(spoken) }) { secretTap() }
            .semantics { contentDescription = clockDesc },
        contentAlignment = Alignment.Center,
    ) {
        val timeSize = minOf(maxHeight * 0.5f, maxWidth * 0.28f)
        val dateSize = (timeSize * 0.32f).coerceAtLeast(18.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, color = fg, fontSize = timeSize.fixedSp(), lineHeight = timeSize.fixedSp(), fontWeight = FontWeight.Bold)
            Text(date, color = fg, fontSize = dateSize.fixedSp(), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * SOS: parte solo tenendo premuto per sos.holdSeconds; la barra mostra l'avanzamento.
 * Un tocco breve spiega cosa fare invece di non reagire.
 */
@Composable
private fun SosTile(tile: Tile, sos: SosConfig, launcher: TileLauncher, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val holdMs = sos.holdSeconds.coerceIn(1, 10) * 1000
    val bg = parseColor(tile.color)
    val fg = contentColorFor(bg)
    val sosDesc = stringResource(R.string.sos_tile_desc, tile.label.ifBlank { "SOS" }, sos.holdSeconds)
    val holdToast = stringResource(R.string.sos_hold_toast)

    Box(
        modifier
            .clip(TileShape)
            .background(bg)
            .semantics {
                role = Role.Button
                contentDescription = sosDesc
            }
            .pointerInput(holdMs) {
                detectTapGestures(
                    onPress = {
                        val job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                            launcher.launch(tile, sos)
                        }
                        val released = tryAwaitRelease()
                        if (job.isActive) {
                            job.cancel()
                            if (released) {
                                Toast.makeText(context, holdToast, Toast.LENGTH_SHORT).show()
                            }
                        }
                        scope.launch { progress.snapTo(0f) }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(Color.Black.copy(alpha = 0.3f)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Symbols.get("alert"), null, Modifier.size(40.dp), tint = fg)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(tile.label.ifBlank { "SOS" }, color = fg, fontSize = 32.dp.fixedSp(), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.sos_hold), color = fg, fontSize = 18.dp.fixedSp())
            }
        }
    }
}

private fun Color.isLight() = contentColorFor(this) == Palette.Ink

/**
 * 5 tocchi entro 3 s → [onTrigger]. Usato sull'orologio e sullo sfondo della griglia,
 * così le impostazioni restano raggiungibili anche se l'orologio viene tolto.
 */
fun Modifier.secretTaps(taps: ArrayDeque<Long>, onLongPress: (() -> Unit)? = null, onTrigger: () -> Unit): Modifier = pointerInput(Unit) {
    detectTapGestures(onLongPress = onLongPress?.let { f -> { _ -> f() } }, onTap = {
        val t = System.currentTimeMillis()
        taps.addLast(t)
        while (taps.isNotEmpty() && t - taps.first() > 3_000) taps.removeFirst()
        if (taps.size >= 5) {
            taps.clear()
            onTrigger()
        }
    })
}

/** Meteo della località del riquadro: simbolo, temperatura, minima e massima. Aggiornato ogni 30 minuti. */
@Composable
private fun WeatherTile(tile: Tile, launcher: TileLauncher, modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf<app.semplicephone.data.Weather.Now?>(null) }
    LaunchedEffect(tile.target) {
        while (true) {
            now = withContext(Dispatchers.IO) { app.semplicephone.data.Weather.current(context, tile.target) }
            delay(30 * 60_000L)
        }
    }
    val bg = parseColor(tile.color)
    val fg = contentColorFor(bg)
    val w = now
    val spoken = w?.let {
        stringResource(R.string.weather_spoken, tile.label, it.temp, stringResource(app.semplicephone.data.Weather.description(it.code)))
    } ?: tile.label
    Box(
        modifier
            .clip(TileShape)
            .background(bg)
            .border(if (bg.isLight()) 2.dp else 0.dp, Palette.Ink, TileShape)
            .combinedClickable(onLongClick = { launcher.speakText(spoken) }, onClick = { launcher.speakText(spoken) })
            .semantics { contentDescription = spoken }
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Misure ricavate dallo spazio reale: in un riquadro 1×1 entrano simbolo, temperatura e
            // località; minima e massima solo se c'è posto.
            val wide = maxWidth > maxHeight * 1.5f
            val sym = Symbols.get(w?.let { app.semplicephone.data.Weather.symbol(it.code) } ?: "weather")
            val tempText = w?.let { "${it.temp}°" } ?: "–"
            val range = w?.let { "${it.min}° / ${it.max}°" }.orEmpty()
            if (wide) {
                val icon = (maxHeight * 0.75f).coerceAtMost(110.dp)
                val temp = (maxHeight * 0.34f).coerceIn(20.dp, 44.dp)
                val roomForRange = maxHeight > 110.dp
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(sym, null, Modifier.size(icon), tint = fg)
                    Column {
                        Text(tempText, color = fg, fontSize = temp.fixedSp(), lineHeight = temp.fixedSp(), fontWeight = FontWeight.Bold, maxLines = 1)
                        if (range.isNotEmpty() && roomForRange) Text(range, color = fg, fontSize = 18.dp.fixedSp(), maxLines = 1)
                        TileLabel(tile.label, fg, 20.dp.fixedSp(), TextAlign.Start, Modifier)
                    }
                }
            } else {
                val showRange = range.isNotEmpty() && maxHeight > 190.dp
                val icon = (maxHeight * if (showRange) 0.3f else 0.34f).coerceAtMost(96.dp)
                val temp = (maxHeight * 0.22f).coerceIn(18.dp, 40.dp)
                val labelSize = (maxHeight * 0.15f).coerceIn(15.dp, 22.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(sym, null, Modifier.size(icon), tint = fg)
                        Text(tempText, color = fg, fontSize = temp.fixedSp(), lineHeight = temp.fixedSp(), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                    if (showRange) Text(range, color = fg, fontSize = 18.dp.fixedSp(), maxLines = 1)
                    TileLabel(tile.label, fg, labelSize.fixedSp(), TextAlign.Center, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * Album: foto caricate e foto/video delle cartelle del telefono, che scorrono ogni 8 secondi.
 * Un tocco su una foto passa alla successiva; su un video (simbolo ▶) lo apre a tutto schermo.
 */
@Composable
private fun PhotosTile(tile: Tile, launcher: TileLauncher, modifier: Modifier) {
    val context = LocalContext.current
    var reload by remember { mutableStateOf(0) }
    val items by produceState(emptyList<app.semplicephone.data.AlbumItem>(), tile.photos, tile.folders, reload) {
        value = withContext(Dispatchers.IO) { app.semplicephone.data.Album.items(context, tile) }
    }
    // Le foto nuove nelle cartelle (es. arrivate su WhatsApp) compaiono senza riaprire nulla.
    LaunchedEffect(tile.folders) { while (tile.folders.isNotEmpty()) { delay(10 * 60_000L); reload++ } }
    var index by remember(items) { mutableStateOf(0) }
    LaunchedEffect(items) {
        while (items.size > 1) {
            delay(8_000)
            index = (index + 1) % items.size
        }
    }
    val bg = parseColor(tile.color)
    val fg = contentColorFor(bg)
    val current = items.getOrNull(index % items.size.coerceAtLeast(1))
    Box(
        modifier
            .clip(TileShape)
            .background(bg)
            .combinedClickable(
                onLongClick = { launcher.speakLabel(tile) },
                onClick = {
                    when {
                        current?.video == true -> launcher.openVideo(current.ref)
                        items.isNotEmpty() -> index = (index + 1) % items.size
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (items.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Symbols.get("photo"), null, Modifier.size(56.dp), tint = fg)
                TileLabel(tile.label, fg, 22.dp.fixedSp(), TextAlign.Center, Modifier.fillMaxWidth())
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val px = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }
                androidx.compose.animation.Crossfade(current, label = "foto") { item ->
                    val bmp by produceState<ImageBitmap?>(null, item, px) {
                        value = item?.let { withContext(Dispatchers.IO) { app.semplicephone.data.Album.bitmap(context, it, px)?.asImageBitmap() } }
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        if (item?.video == true) Box(
                            Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xCC000000)),
                            contentAlignment = Alignment.Center,
                        ) { Text("▶", color = Color.White, fontSize = 34.dp.fixedSp(), fontWeight = FontWeight.Bold) }
                    }
                }
                if (tile.label.isNotBlank()) {
                    Box(
                        Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                            .padding(start = 10.dp, end = 10.dp, top = 28.dp, bottom = 10.dp),
                    ) { TileLabel(tile.label, Color.White, 22.dp.fixedSp(), TextAlign.Center, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

/** Riga dell'elenco: chi ha chiamato (nome in rubrica o numero) e quando. */
private data class MissedRow(val missed: app.semplicephone.data.Missed, val name: String)

/**
 * Chiamate perse non ancora richiamate, dalla più recente: un tocco su una riga richiama.
 * Senza permesso sul registro chiamate (edizione Play) il riquadro apre il registro del telefono.
 */
@Composable
private fun MissedCallsTile(tile: Tile, sos: SosConfig, launcher: TileLauncher, modifier: Modifier) {
    val context = LocalContext.current
    val readable = app.semplicephone.data.MissedCalls.canRead(context)
    var rows by remember { mutableStateOf<List<MissedRow>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    if (readable) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { tick++ }
            }
            runCatching { context.contentResolver.registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, observer) }
            onDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
        }
        LaunchedEffect(tick) {
            rows = withContext(Dispatchers.IO) {
                app.semplicephone.data.MissedCalls.load(context).map { MissedRow(it, launcher.contacts.displayName(it.number)) }
            }
        }
        // Ricarica periodica: orari («ieri») e chiamate che escono dalla finestra di 3 giorni.
        LaunchedEffect(Unit) { while (true) { delay(5 * 60_000L); tick++ } }
    }
    val bg = parseColor(tile.color)
    val fg = contentColorFor(bg)
    val label = tile.label.ifBlank { stringResource(R.string.label_missed_calls) }
    val spoken = if (!readable) label
        else if (rows.isEmpty()) stringResource(R.string.missed_none)
        else stringResource(R.string.missed_spoken, rows.size, rows.joinToString(", ") { it.name })
    val base = modifier
        .clip(TileShape)
        .background(bg)
        .border(if (bg.isLight()) 2.dp else 0.dp, Palette.Ink, TileShape)
        .semantics { contentDescription = spoken }

    if (!readable || rows.isEmpty()) {
        Box(
            base.combinedClickable(
                role = Role.Button,
                onLongClick = { launcher.speakText(spoken) },
                onClick = { if (readable) launcher.speakText(spoken) else launcher.launch(tile, sos) },
            ).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val icon = (minOf(maxWidth, maxHeight) * 0.42f).coerceAtMost(96.dp)
                val tall = maxHeight > 140.dp
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Symbols.get(if (readable) "check" else "missed"), null, Modifier.size(icon), tint = fg)
                    TileLabel(label, fg, 22.dp.fixedSp(), TextAlign.Center, Modifier.fillMaxWidth())
                    if (readable && tall) {
                        Text(stringResource(R.string.missed_none), color = fg, fontSize = 17.dp.fixedSp(), textAlign = TextAlign.Center)
                    }
                }
            }
        }
        return
    }

    BoxWithConstraints(base.padding(8.dp)) {
        val rowHeight = 64.dp
        val headerHeight = 34.dp
        val fit = ((maxHeight - headerHeight) / (rowHeight + 6.dp)).toInt().coerceAtLeast(1)
        val shown = rows.take(fit)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                    .combinedClickable(onLongClick = { launcher.speakText(spoken) }, onClick = { launcher.speakText(spoken) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Symbols.get("missed"), null, Modifier.size(26.dp), tint = fg)
                Text(
                    "$label (${rows.size})", color = fg, fontSize = 19.dp.fixedSp(), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            shown.forEach { row ->
                val call = stringResource(R.string.missed_call_back_desc, row.name)
                Row(
                    Modifier.fillMaxWidth().height(rowHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .border(2.dp, Palette.Ink, RoundedCornerShape(14.dp))
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = call,
                            onLongClick = { launcher.speakText(row.name) },
                            onClick = { launcher.callBack(row.missed.number, row.name) },
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.name, color = Palette.Ink, fontSize = 20.dp.fixedSp(), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            missedWhen(row.missed),
                            color = Palette.Ink, fontSize = 15.dp.fixedSp(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF1E7B34)), contentAlignment = Alignment.Center) {
                        Icon(Symbols.get("phone"), null, Modifier.size(26.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}

/** «oggi 10:42», «ieri 18:03» o «lun 9:15», più il numero di tentativi se più d'uno. */
@Composable
private fun missedWhen(m: app.semplicephone.data.Missed): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val zone = java.time.ZoneId.systemDefault()
    val at = java.time.Instant.ofEpochMilli(m.at).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val time = at.format(DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale))
    val day = when (at.toLocalDate()) {
        today -> stringResource(R.string.missed_today, time)
        today.minusDays(1) -> stringResource(R.string.missed_yesterday, time)
        else -> at.format(DateTimeFormatter.ofPattern("EEE", locale)) + " " + time
    }
    return if (m.count > 1) day + " · " + stringResource(R.string.missed_times, m.count) else day
}

/**
 * Messaggi da leggere (edizione Completa, dalle notifiche): chi ha scritto, con quale app, l'inizio
 * del testo e l'ora. Un tocco apre quella conversazione, una pressione lunga lo legge ad alta voce.
 * Senza accesso alle notifiche il riquadro apre l'app Messaggi.
 */
@Composable
private fun MessagesTile(tile: Tile, sos: SosConfig, launcher: TileLauncher, modifier: Modifier) {
    val context = LocalContext.current
    val readable = app.semplicephone.BuildConfig.NOTIFICATIONS && app.semplicephone.phone.Badges.isEnabled(context)
    val messages by app.semplicephone.phone.Badges.messages.collectAsState()
    val bg = parseColor(tile.color)
    val fg = contentColorFor(bg)
    val label = tile.label.ifBlank { stringResource(R.string.label_messages_unread) }
    val spokenAll = if (!readable) label
        else if (messages.isEmpty()) stringResource(R.string.messages_none)
        else stringResource(R.string.messages_spoken_count, messages.size)
    val base = modifier
        .clip(TileShape)
        .background(bg)
        .border(if (bg.isLight()) 2.dp else 0.dp, Palette.Ink, TileShape)
        .semantics { contentDescription = spokenAll }

    if (!readable || messages.isEmpty()) {
        Box(
            base.combinedClickable(
                role = Role.Button,
                onLongClick = { launcher.speakText(spokenAll) },
                onClick = { if (readable) launcher.speakText(spokenAll) else launcher.launch(tile, sos) },
            ).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val icon = (minOf(maxWidth, maxHeight) * 0.42f).coerceAtMost(96.dp)
                val tall = maxHeight > 140.dp
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Symbols.get(if (readable) "check" else "message"), null, Modifier.size(icon), tint = fg)
                    TileLabel(label, fg, 22.dp.fixedSp(), TextAlign.Center, Modifier.fillMaxWidth())
                    if (readable && tall) Text(stringResource(R.string.messages_none), color = fg, fontSize = 17.dp.fixedSp(), textAlign = TextAlign.Center)
                }
            }
        }
        return
    }

    BoxWithConstraints(base.padding(8.dp)) {
        val rowHeight = 72.dp
        val fit = ((maxHeight - 34.dp) / (rowHeight + 6.dp)).toInt().coerceAtLeast(1)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                    .combinedClickable(onLongClick = { launcher.speakText(spokenAll) }, onClick = { launcher.speakText(spokenAll) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Symbols.get("message"), null, Modifier.size(26.dp), tint = fg)
                Text("$label (${messages.size})", color = fg, fontSize = 19.dp.fixedSp(), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            messages.take(fit).forEach { m ->
                val spoken = stringResource(R.string.messages_spoken_one, m.sender.ifBlank { m.app }, m.app, m.text)
                Row(
                    Modifier.fillMaxWidth().height(rowHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .border(2.dp, Palette.Ink, RoundedCornerShape(14.dp))
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = spoken,
                            onLongClick = { launcher.speakText(spoken) },
                            onClick = { launcher.openMessage(m) },
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.sender.ifBlank { m.app } + " · " + m.app + " · " +
                                java.time.Instant.ofEpochMilli(m.at).atZone(java.time.ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)),
                            color = Palette.Ink, fontSize = 17.dp.fixedSp(), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(m.text, color = Palette.Ink, fontSize = 17.dp.fixedSp(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
