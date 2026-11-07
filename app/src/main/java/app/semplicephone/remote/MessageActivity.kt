package app.semplicephone.remote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.R
import app.semplicephone.ui.Palette
import app.semplicephone.ui.SempliceTheme
import app.semplicephone.ui.Symbols
import app.semplicephone.ui.Voice

/**
 * Messaggio della famiglia a tutto schermo, anche a telefono bloccato, letto ad alta voce.
 * Gira nel processo «:alarm» come l'allarme dei promemoria; «Ho letto» avvisa il processo principale.
 */
class MessageActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        app.semplicephone.launch.HomeButton.notifyAlarm(this, true)
    }

    override fun onStop() {
        app.semplicephone.launch.HomeButton.notifyAlarm(this, false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Avviso del telefono (es. «Metti in carica») o messaggio della famiglia.
        val notice = if (intent.getBooleanExtra(Notice.EXTRA, false)) Notice.read(this) else null
        val m = if (notice == null) FamilyMessages.unread(this) ?: return finish() else null
        val title = notice?.title ?: if (m!!.from.isNotBlank()) getString(R.string.message_from, m.from) else getString(R.string.message_title)
        val body = notice?.text ?: m!!.text
        val symbol = notice?.symbol ?: "message"
        val spoken = "$title. $body"
        val video = m?.let { FamilyMessages.videoFile(this, it) }?.takeIf { it.exists() }

        if (video != null) {
            // Videomessaggio: il video parte da solo (senza voce sopra), poi «HO VISTO».
            setContent {
                SempliceTheme {
                    app.semplicephone.ui.VideoPlayer(
                        android.net.Uri.fromFile(video), getString(R.string.message_seen),
                        onClose = {
                            sendBroadcast(Intent(this@MessageActivity, FamilyReceiver::class.java).setAction(FamilyMessages.ACTION_READ))
                            finishAndRemoveTask()
                        },
                        title = title, extra = body,
                        footer = { VoiceReplyButton(m!!.id) },
                    )
                }
            }
            return
        }

        setContent {
            LaunchedEffect(spoken) { Voice.speak(this@MessageActivity, spoken) }
            SempliceTheme {
                Column(
                    Modifier.fillMaxSize().background(Color(0xFFE8F1FB)).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Spacer(Modifier.heightIn(min = 12.dp))
                    Icon(Symbols.get(symbol), null, Modifier.size(96.dp), tint = Palette.Ink)
                    Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(body, fontSize = 34.sp, lineHeight = 42.sp, color = Palette.Ink, textAlign = TextAlign.Center)
                    }
                    OutlinedButton(
                        onClick = { Voice.speak(this@MessageActivity, spoken) },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    ) { Text(stringResource(R.string.message_read_again), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink) }
                    // Risposta a voce alla famiglia (solo per i messaggi, non per gli avvisi del telefono).
                    if (m != null) VoiceReplyButton(m.id)
                    Button(
                        onClick = {
                            if (notice != null) Notice.dismiss(this@MessageActivity)
                            else sendBroadcast(Intent(this@MessageActivity, FamilyReceiver::class.java).setAction(FamilyMessages.ACTION_READ))
                            finishAndRemoveTask()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B6E34)),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    ) { Text(stringResource(if (notice != null) R.string.notice_ok else R.string.message_ok), fontSize = 40.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
