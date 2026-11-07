package app.semplicephone.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.semplicephone.R
import app.semplicephone.ui.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Risposte vocali alla famiglia: si registra tenendo premuto, al rilascio il vocale parte per il
 * portale (con il segreto del telefono); se manca la rete resta in coda e riparte alla sincronizzazione.
 */
object VoiceReplies {
    private const val TAG = "VoiceReply"
    private const val MAX_MS = 120_000

    private fun outbox(context: Context) = File(context.filesDir, "voice_out").apply { mkdirs() }

    fun startRecording(context: Context, file: File): MediaRecorder? = runCatching {
        MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1); setAudioSamplingRate(22_050); setAudioEncodingBitRate(48_000)
            setMaxDuration(MAX_MS)
            setOutputFile(file.absolutePath)
            prepare(); start()
        }
    }.onFailure { Log.w(TAG, "registrazione non avviata", it) }.getOrNull()

    /** Mette il vocale in coda (nome = id del messaggio a cui risponde + ora) e prova a mandarlo. */
    fun queue(context: Context, recorded: File, messageId: Long) {
        val dest = File(outbox(context), "${messageId}_${System.currentTimeMillis()}.m4a")
        if (!recorded.renameTo(dest)) { recorded.copyTo(dest, overwrite = true); recorded.delete() }
        flush(context)
    }

    /** Manda i vocali in coda; true se la coda è vuota. */
    fun flush(context: Context): Boolean {
        val s = RemoteSettings(context)
        if (!s.enabled || !s.resolvedUrl().startsWith("https://")) return false
        outbox(context).listFiles().orEmpty().sortedBy { it.name }.forEach { f ->
            val id = f.name.substringBefore('_')
            val ok = runCatching {
                val base = s.resolvedUrl()
                val c = (URL(base + (if ('?' in base) "&" else "?") + "voice=" + id).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("Content-Type", "audio/mp4")
                    setRequestProperty("X-Device-Secret", s.deviceSecret)
                    setFixedLengthStreamingMode(f.length())
                }
                f.inputStream().use { input -> c.outputStream.use { input.copyTo(it) } }
                (c.responseCode in 200..299).also { c.disconnect() }
            }.onFailure { Log.w(TAG, "vocale non mandato", it) }.getOrDefault(false)
            if (ok) f.delete() else return false
        }
        return true
    }
}

/** «🎤 Tieni premuto e rispondi»: verde a riposo, rosso mentre registra; al rilascio parte. */
@Composable
fun VoiceReplyButton(messageId: Long, modifier: Modifier = Modifier.fillMaxWidth()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    var recording by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val sentText = stringResource(R.string.voice_reply_sent)
    val shortText = stringResource(R.string.voice_reply_short)
    Box(
        modifier.heightIn(min = 110.dp).clip(RoundedCornerShape(26.dp))
            .background(if (recording) Color(0xFFC62828) else Color(0xFF1B7A34))
            .pointerInput(allowed) {
                detectTapGestures(onPress = {
                    if (!allowed) { ask.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                    val file = File(context.cacheDir, "reply.m4a").apply { delete() }
                    val rec = VoiceReplies.startRecording(context, file) ?: return@detectTapGestures
                    val started = System.currentTimeMillis()
                    recording = true; status = null
                    tryAwaitRelease()
                    recording = false
                    runCatching { rec.stop() }; rec.release()
                    if (System.currentTimeMillis() - started < 1200) { file.delete(); status = shortText; return@detectTapGestures }
                    status = sentText
                    Voice.speak(context, sentText)
                    scope.launch { withContext(Dispatchers.IO) { VoiceReplies.queue(context, file, messageId) } }
                })
            }
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            status?.takeIf { !recording } ?: stringResource(if (recording) R.string.voice_reply_recording else R.string.voice_reply_hold),
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
    }
}
