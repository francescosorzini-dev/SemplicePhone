package app.semplicephone.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

@Composable
fun TileView(
    tile: Tile,
    sos: SosConfig,
    launcher: TileLauncher,
    modifier: Modifier = Modifier,
    onSecretTap: () -> Unit = {},
) {
    when (tile.type) {
        TileType.CLOCK -> ClockTile(tile, modifier, onSecretTap)
        TileType.SOS -> SosTile(tile, sos, launcher, modifier)
        else -> ActionTile(tile, sos, launcher, modifier)
    }
}

@Composable
private fun ActionTile(tile: Tile, sos: SosConfig, launcher: TileLauncher, modifier: Modifier) {
    val available = remember(tile) { launcher.isAvailable(tile) }
    val bg = parseColor(tile.color).let { if (available) it else it.copy(alpha = 0.35f) }
    val fg = contentColorFor(parseColor(tile.color))
    val wide = tile.w > tile.h

    Box(
        modifier
            .clip(TileShape)
            .background(bg)
            .border(if (bg.isLight()) 2.dp else 0.dp, Palette.Ink, TileShape)
            .clickable(role = Role.Button, onClickLabel = tile.label) { launcher.launch(tile, sos) }
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
        // PHOTO: foto contatto da M2; per ora simbolo persona.
        tile.icon.kind == IconKind.PHOTO -> Icon(Symbols.get("person"), null, Modifier.size(size), tint = tint)
        else -> {
            val name = tile.icon.ref.takeIf { it in Symbols.names } ?: Symbols.defaultFor(tile.type)
            Icon(Symbols.get(name), null, Modifier.size(size), tint = tint)
        }
    }
}

@Composable
private fun ClockTile(tile: Tile, modifier: Modifier, onSecretTap: () -> Unit) {
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
    val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN))
        .replaceFirstChar { it.titlecase(Locale.ITALIAN) }

    BoxWithConstraints(
        modifier
            .clip(TileShape)
            .background(bg)
            .border(if (bg.isLight()) 2.dp else 0.dp, Palette.Ink, TileShape)
            .secretTaps(taps) { secretTap() }
            .semantics { contentDescription = "Sono le $time, $date" },
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

    Box(
        modifier
            .clip(TileShape)
            .background(bg)
            .semantics {
                role = Role.Button
                contentDescription = "${tile.label.ifBlank { "SOS" }}: tieni premuto ${sos.holdSeconds} secondi"
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
                                Toast.makeText(context, "Tieni premuto per chiamare aiuto", Toast.LENGTH_SHORT).show()
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
                Text("tieni premuto", color = fg, fontSize = 18.dp.fixedSp())
            }
        }
    }
}

private fun Color.isLight() = contentColorFor(this) == Palette.Ink

/**
 * 5 tocchi entro 3 s → [onTrigger]. Usato sull'orologio e sullo sfondo della griglia,
 * così le impostazioni restano raggiungibili anche se l'orologio viene tolto.
 */
fun Modifier.secretTaps(taps: ArrayDeque<Long>, onTrigger: () -> Unit): Modifier = pointerInput(Unit) {
    detectTapGestures(onTap = {
        val t = System.currentTimeMillis()
        taps.addLast(t)
        while (taps.isNotEmpty() && t - taps.first() > 3_000) taps.removeFirst()
        if (taps.size >= 5) {
            taps.clear()
            onTrigger()
        }
    })
}
