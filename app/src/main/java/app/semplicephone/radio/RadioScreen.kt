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
import app.semplicephone.ui.BigButton
import app.semplicephone.ui.BigOutlined
import app.semplicephone.ui.BigScreen
import app.semplicephone.ui.EmptyState
import app.semplicephone.ui.Palette
import app.semplicephone.ui.TextSize
import app.semplicephone.ui.Tone
import app.semplicephone.ui.Ui
import app.semplicephone.ui.Symbols

/**
 * Radio dentro il launcher: nome della stazione grande, AVVIA/FERMA enorme, stazione precedente e
 * successiva, volume. La radio continua anche uscendo da qui o spegnendo lo schermo.
 */
@Composable
fun RadioScreen(tile: Tile, onClose: () -> Unit, songs: List<app.semplicephone.model.Station>? = null) {
    val context = LocalContext.current
    // Musica: i brani delle cartelle si suonano uno dopo l'altro; Radio: le stazioni scelte.
    val stations = songs ?: tile.stations.filter { it.url.isNotBlank() }
    val playlist = songs.orEmpty()
    val state by RadioService.state.collectAsState()
    var index by remember { mutableIntStateOf(stations.indexOfFirst { it.url == state.station?.url }.coerceAtLeast(0)) }
    // Il brano successivo parte da solo: la schermata segue quello in riproduzione.
    androidx.compose.runtime.LaunchedEffect(state.station?.url) {
        stations.indexOfFirst { it.url == state.station?.url }.takeIf { it >= 0 }?.let { index = it }
    }
    val current = stations.getOrNull(index)
    val am = remember { context.getSystemService(AudioManager::class.java) }
    val active = state.station?.url == current?.url && (state.playing || state.loading)

    fun select(i: Int) {
        index = (i + stations.size) % stations.size
        if (active || state.playing) RadioService.play(context, stations[index], playlist)
    }

    val music = songs != null
    BigScreen(if (music) tile.label.ifBlank { stringResource(R.string.label_music) } else tile.label.ifBlank { stringResource(R.string.label_radio) }, if (music) "music" else "radio", onClose) {
        if (current == null) {
            EmptyState(if (music) "music" else "radio", stringResource(if (music) R.string.music_no_songs else R.string.radio_no_stations))
            return@BigScreen
        }
        Text(current.name.ifBlank { stringResource(R.string.label_radio) }, fontSize = TextSize.Big, lineHeight = 42.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
        Text(
            when {
                state.error && state.station?.url == current.url -> stringResource(R.string.radio_error)
                active && state.loading -> stringResource(R.string.radio_loading)
                active -> stringResource(R.string.radio_playing)
                else -> " "
            },
            fontSize = TextSize.Label, color = if (state.error) Palette.Warn else Palette.Ink, textAlign = TextAlign.Center,
        )
        BigButton(
            stringResource(if (active) R.string.radio_stop else R.string.radio_play), if (active) Tone.Warn else Tone.Ok,
            symbol = if (active) "close" else "play", height = Ui.HeightHuge, textSize = TextSize.Huge,
        ) { if (active) RadioService.stop(context) else RadioService.play(context, current, playlist) }
        if (stations.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigOutlined("‹ " + stringResource(R.string.radio_prev), Modifier.weight(1f)) { select(index - 1) }
            BigOutlined(stringResource(R.string.radio_next) + " ›", Modifier.weight(1f)) { select(index + 1) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigOutlined(stringResource(R.string.radio_quieter), Modifier.weight(1f), symbol = "minus") {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
            BigOutlined(stringResource(R.string.radio_louder), Modifier.weight(1f), symbol = "plus") {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }
        }
    }
}
