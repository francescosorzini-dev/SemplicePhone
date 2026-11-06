package app.semplicephone.radio

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.R
import app.semplicephone.model.Tile
import app.semplicephone.ui.Palette
import app.semplicephone.ui.Symbols

/**
 * Radio dentro il launcher: nome della stazione grande, AVVIA/FERMA enorme, stazione precedente e
 * successiva, volume. La radio continua anche uscendo da qui o spegnendo lo schermo.
 */
@Composable
fun RadioScreen(tile: Tile, onClose: () -> Unit) {
    val context = LocalContext.current
    val stations = tile.stations.filter { it.url.isNotBlank() }
    val state by RadioService.state.collectAsState()
    var index by remember { mutableIntStateOf(stations.indexOfFirst { it.url == state.station?.url }.coerceAtLeast(0)) }
    val current = stations.getOrNull(index)
    val am = remember { context.getSystemService(AudioManager::class.java) }
    val active = state.station?.url == current?.url && (state.playing || state.loading)

    fun select(i: Int) {
        index = (i + stations.size) % stations.size
        if (active || state.playing) RadioService.play(context, stations[index])
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFFF6F4EF)).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.search_back), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            }
        }
        Icon(Symbols.get("radio"), null, Modifier.size(110.dp), tint = Palette.Ink)
        if (current == null) {
            Text(stringResource(R.string.radio_no_stations), fontSize = 24.sp, color = Palette.Ink, textAlign = TextAlign.Center)
            return@Column
        }
        Text(current.name.ifBlank { stringResource(R.string.label_radio) }, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
        Text(
            when {
                state.error && state.station?.url == current.url -> stringResource(R.string.radio_error)
                active && state.loading -> stringResource(R.string.radio_loading)
                active -> stringResource(R.string.radio_playing)
                else -> " "
            },
            fontSize = 20.sp, color = if (state.error) Palette.Sos else Palette.Ink, textAlign = TextAlign.Center,
        )
        Button(
            onClick = { if (active) RadioService.stop(context) else RadioService.play(context, current) },
            colors = ButtonDefaults.buttonColors(containerColor = if (active) Color(0xFFC62828) else Color(0xFF1B7A34)),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
        ) { Text(stringResource(if (active) R.string.radio_stop else R.string.radio_play), fontSize = 44.sp, fontWeight = FontWeight.Bold) }
        if (stations.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { select(index - 1) }, modifier = Modifier.weight(1f).heightIn(min = 72.dp), shape = RoundedCornerShape(20.dp)) {
                Text("‹ " + stringResource(R.string.radio_prev), fontSize = 20.sp, color = Palette.Ink)
            }
            OutlinedButton(onClick = { select(index + 1) }, modifier = Modifier.weight(1f).heightIn(min = 72.dp), shape = RoundedCornerShape(20.dp)) {
                Text(stringResource(R.string.radio_next) + " ›", fontSize = 20.sp, color = Palette.Ink)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) },
                modifier = Modifier.weight(1f).heightIn(min = 72.dp), shape = RoundedCornerShape(20.dp),
            ) { Text("🔉 " + stringResource(R.string.radio_quieter), fontSize = 20.sp, color = Palette.Ink) }
            OutlinedButton(
                onClick = { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) },
                modifier = Modifier.weight(1f).heightIn(min = 72.dp), shape = RoundedCornerShape(20.dp),
            ) { Text("🔊 " + stringResource(R.string.radio_louder), fontSize = 20.sp, color = Palette.Ink) }
        }
    }
}
