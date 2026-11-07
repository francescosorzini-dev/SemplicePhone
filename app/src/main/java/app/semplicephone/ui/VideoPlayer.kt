package app.semplicephone.ui

import android.media.AudioManager
import android.net.Uri
import android.widget.VideoView
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.viewinterop.AndroidView
import app.semplicephone.R

/**
 * Video a tutto schermo per chi fatica con i comandi: parte da solo, volume alzato almeno ai tre
 * quarti (rimesso com'era all'uscita), un tocco sul video lo ferma o lo riprende; sotto, «DI NUOVO»
 * e un pulsante enorme per chiudere. [extra] può aggiungere un testo sopra i pulsanti.
 */
@Composable
fun VideoPlayer(uri: Uri, closeLabel: String, onClose: () -> Unit, title: String = "", extra: String = "") {
    val context = LocalContext.current
    var view by remember { mutableStateOf<VideoView?>(null) }
    var ended by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val am = context.getSystemService(AudioManager::class.java)
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val loud = (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 3 + 3) / 4
        if (before < loud) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, loud, 0) }
        onDispose {
            view?.stopPlayback()
            if (before < loud) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (title.isNotBlank()) Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (failed) Text(stringResource(R.string.video_error), fontSize = 26.sp, color = Color.White, textAlign = TextAlign.Center)
            else AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(uri)
                        setOnPreparedListener { start() }
                        setOnCompletionListener { ended = true }
                        setOnErrorListener { _, _, _ -> failed = true; true }
                        setOnClickListener { if (isPlaying) pause() else { ended = false; start() } }
                        view = this
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (extra.isNotBlank()) Text(extra, fontSize = 26.sp, lineHeight = 32.sp, color = Color.White, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!failed) OutlinedButton(
                onClick = { ended = false; view?.seekTo(0); view?.start() },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f).heightIn(min = 96.dp),
            ) { Text(stringResource(R.string.video_again), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center) }
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = if (ended || failed) Color(0xFF1B7A34) else Color(0xFFC62828)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1.6f).heightIn(min = 96.dp),
            ) { Text(closeLabel, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
        }
    }
}
